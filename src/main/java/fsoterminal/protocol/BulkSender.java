package fsoterminal.protocol;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Отправитель файла блочным ARQ. См. docs/BULK_PROTOCOL_DESIGN.md.
 * Чистая Java, без JavaFX. Работает в собственном фоновом потоке.
 *
 * Каждой передаче присваивается tid (transfer-id, rolling) — он во всех кадрах,
 * чтобы приёмник не смешал отменённую и новую передачу.
 *
 * Подключение: кадры NACK/BLOCK_DONE с приёмной стороны направлять в
 * {@link #onControlFrame(FrameCodec.Frame)} (роутер по TYPE на стороне UI/теста).
 */
public class BulkSender {

    private static final int RTT_MARGIN_MS = 2000; // запас сверх времени слива очереди
    private static final int MAX_RETRIES   = 10;   // повторов END без ответа → обрыв

    private final PacedTransmitter tx;
    private final ProtocolConfig   cfg;
    private final BlockingQueue<FrameCodec.Frame> inbox = new LinkedBlockingQueue<>();

    private volatile boolean cancelled = false;
    private volatile int     curTid    = 0;   // tid текущей передачи (читается из onControlFrame)
    private int  nextTid = 0;
    private long delayMs = 20;
    private int  seq     = 0;

    public BulkSender(PacedTransmitter tx, ProtocolConfig cfg) {
        this.tx  = tx;
        this.cfg = cfg;
    }

    /** Принять управляющий кадр (NACK/BLOCK_DONE) от приёмника — только для текущего tid. */
    public void onControlFrame(FrameCodec.Frame fr) {
        if (fr.type != BulkProtocol.TYPE_NACK && fr.type != BulkProtocol.TYPE_BLOCK_DONE) return;
        if (fr.payload.length < 1 || (fr.payload[0] & 0xFF) != (curTid & 0xFF)) return; // чужая передача
        inbox.offer(fr);
    }

    public void cancel() { cancelled = true; }

    /**
     * Запускает передачу в фоновом потоке.
     *
     * @param kind       вид содержимого (BulkProtocol.KIND_*)
     * @param name       имя файла
     * @param data       содержимое
     * @param progress   доля выполнения 0..1 (по блокам), может быть null
     * @param onComplete вызывается при успехе, может быть null
     * @param onError    вызывается с текстом ошибки при обрыве, может быть null
     */
    public void send(int kind, String name, byte[] data,
                     DoubleConsumer progress, Runnable onComplete, Consumer<String> onError) {
        cancelled = false;
        inbox.clear();
        final int tid = nextTid++ & 0xFF;
        curTid = tid;
        Thread t = new Thread(() -> {
            try {
                runTransfer(tid, kind, name, data, progress);
                if (!cancelled && onComplete != null) onComplete.run();
            } catch (TransferException te) {
                if (onError != null) onError.accept(te.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "bulk-send");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------

    private void runTransfer(int tid, int kind, String name, byte[] data, DoubleConsumer progress)
            throws InterruptedException, TransferException {
        int totalFrames = (int) Math.ceil((double) data.length / BulkProtocol.DATA_BYTES);
        int numBlocks   = Math.max(1, (int) Math.ceil((double) totalFrames / BulkProtocol.BLOCK_FRAMES));

        delayMs = BulkProtocol.pacingMs(
            BulkProtocol.dataFrameLen(BulkProtocol.DATA_BYTES), cfg.bulkOverdriveMs);
        tx.setDataDelayMs(delayMs);

        byte[] begin = encodeFileBegin(tid, kind, name, data.length, numBlocks);
        for (int i = 0; i < 3; i++) tx.sendPaced(begin);   // FILE_BEGIN ×3 (идемпотентно)

        for (int b = 0; b < numBlocks; b++) {
            if (cancelled) return;
            int base  = b * BulkProtocol.BLOCK_FRAMES;
            int count = Math.min(BulkProtocol.BLOCK_FRAMES, totalFrames - base);

            int sent = sendBlock(tid, b, base, count, data, null);
            int retries = 0;

            while (true) {
                if (cancelled) return;
                long waitMs = (long) sent * delayMs + RTT_MARGIN_MS;
                FrameCodec.Frame r = inbox.poll(waitMs, TimeUnit.MILLISECONDS);
                if (r == null) {
                    if (++retries > MAX_RETRIES)
                        throw new TransferException("Канал не отвечает (блок " + b + ")");
                    tx.sendPaced(encodeBlockEnd(tid, b, count));   // END/ответ потерян → повторяем
                    sent = 1;
                    continue;
                }
                // payload: [tid][blk]...  (tid уже отфильтрован в onControlFrame)
                if (r.payload.length < 2 || (r.payload[1] & 0xFF) != (b & 0xFF)) continue;
                if (r.type == BulkProtocol.TYPE_BLOCK_DONE) break;
                if (r.type == BulkProtocol.TYPE_NACK) {
                    boolean[] mask = parseNack(r.payload, count);
                    int miss = 0;
                    if (mask != null) for (boolean m : mask) if (m) miss++;
                    if (miss > 0) { sent = sendBlock(tid, b, base, count, data, mask); retries = 0; }
                    else          { sent = 1; }
                }
            }
            if (progress != null) progress.accept((double)(b + 1) / numBlocks);
        }

        byte[] end = FrameCodec.encode(seq++, BulkProtocol.TYPE_FILE_END, new byte[]{ (byte) tid });
        for (int i = 0; i < 3; i++) tx.sendPaced(end);     // FILE_END ×3
    }

    /** DATA-кадры блока (idx 0..count-1) + BLOCK_END. mask=null → все. Возвращает число DATA-кадров. */
    private int sendBlock(int tid, int blk, int base, int count, byte[] data, boolean[] mask) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (mask != null && !mask[i]) continue;
            int off = (base + i) * BulkProtocol.DATA_BYTES;
            int len = Math.min(BulkProtocol.DATA_BYTES, data.length - off);
            byte[] p = new byte[4 + len];
            p[0] = (byte) tid;
            p[1] = (byte) blk;
            p[2] = (byte)(i & 0xFF);
            p[3] = (byte)((i >> 8) & 0xFF);
            System.arraycopy(data, off, p, 4, len);
            tx.sendPaced(FrameCodec.encode(seq++, BulkProtocol.TYPE_DATA, p));
            n++;
        }
        tx.sendPaced(encodeBlockEnd(tid, blk, count));
        return n;
    }

    private byte[] encodeBlockEnd(int tid, int blk, int count) {
        byte[] p = { (byte) tid, (byte) blk, (byte)(count & 0xFF), (byte)((count >> 8) & 0xFF) };
        return FrameCodec.encode(seq++, BulkProtocol.TYPE_BLOCK_END, p);
    }

    private byte[] encodeFileBegin(int tid, int kind, String name, int size, int blocks) {
        byte[] nm = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (nm.length > 200) nm = java.util.Arrays.copyOf(nm, 200);
        byte[] p = new byte[2 + 2 + 4 + 1 + nm.length];
        p[0] = (byte) tid;
        p[1] = (byte) kind;
        p[2] = (byte)(blocks & 0xFF);       p[3] = (byte)((blocks >> 8) & 0xFF);
        p[4] = (byte)(size & 0xFF);         p[5] = (byte)((size >> 8) & 0xFF);
        p[6] = (byte)((size >> 16) & 0xFF); p[7] = (byte)((size >> 24) & 0xFF);
        p[8] = (byte) nm.length;
        System.arraycopy(nm, 0, p, 9, nm.length);
        return FrameCodec.encode(seq++, BulkProtocol.TYPE_FILE_BEGIN, p);
    }

    /** NACK payload: [tid][blk][count 2][bitmap]. Возвращает маску потерянных (true) или null. */
    private static boolean[] parseNack(byte[] payload, int count) {
        if (payload.length < 4) return null;
        int n = (payload[2] & 0xFF) | ((payload[3] & 0xFF) << 8);
        boolean[] mask = new boolean[count];
        boolean any = false;
        for (int i = 0; i < n && i < count; i++) {
            int bytePos = 4 + i / 8;
            if (bytePos >= payload.length) break;
            if ((payload[bytePos] & (1 << (i % 8))) == 0) { mask[i] = true; any = true; }
        }
        return any ? mask : new boolean[count];
    }

    private static final class TransferException extends Exception {
        TransferException(String m) { super(m); }
    }
}
