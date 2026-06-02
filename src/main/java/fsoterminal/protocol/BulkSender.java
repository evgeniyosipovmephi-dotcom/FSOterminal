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
    private long delayMs = 20;   // текущая задержка пейсинга (из pacingMs)
    private int  seq     = 0;

    public BulkSender(PacedTransmitter tx, ProtocolConfig cfg) {
        this.tx  = tx;
        this.cfg = cfg;
    }

    /** Принять управляющий кадр (NACK/BLOCK_DONE) от приёмника. */
    public void onControlFrame(FrameCodec.Frame fr) {
        if (fr.type == BulkProtocol.TYPE_NACK || fr.type == BulkProtocol.TYPE_BLOCK_DONE)
            inbox.offer(fr);
    }

    public void cancel() { cancelled = true; }

    /**
     * Запускает передачу в фоновом потоке.
     *
     * @param name       имя файла
     * @param data       содержимое
     * @param progress   доля выполнения 0..1 (по завершённым блокам), может быть null
     * @param onComplete вызывается при успешном завершении, может быть null
     * @param onError    вызывается с текстом ошибки при обрыве, может быть null
     */
    public void send(int kind, String name, byte[] data,
                     DoubleConsumer progress, Runnable onComplete, Consumer<String> onError) {
        cancelled = false;
        inbox.clear();
        Thread t = new Thread(() -> {
            try {
                runTransfer(kind, name, data, progress);
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

    private void runTransfer(int kind, String name, byte[] data, DoubleConsumer progress)
            throws InterruptedException, TransferException {
        int totalFrames = (int) Math.ceil((double) data.length / BulkProtocol.DATA_BYTES);
        int numBlocks   = Math.max(1, (int) Math.ceil((double) totalFrames / BulkProtocol.BLOCK_FRAMES));

        delayMs = BulkProtocol.pacingMs(
            BulkProtocol.dataFrameLen(BulkProtocol.DATA_BYTES), cfg.bulkOverdriveMs);
        tx.setDataDelayMs(delayMs);

        // FILE_BEGIN ×3 (идемпотентно, дублёр на случай потери первого)
        byte[] begin = encodeFileBegin(kind, name, data.length, numBlocks);
        for (int i = 0; i < 3; i++) tx.sendPaced(begin);

        for (int b = 0; b < numBlocks; b++) {
            if (cancelled) return;
            int base  = b * BulkProtocol.BLOCK_FRAMES;
            int count = Math.min(BulkProtocol.BLOCK_FRAMES, totalFrames - base);

            int sent = sendBlock(b, base, count, data, null);   // сколько DATA-кадров поставлено в очередь
            int retries = 0;

            while (true) {
                if (cancelled) return;
                // Ждём, пока очередь сольётся (sent × задержка) + запас на ответ.
                long waitMs = (long) sent * delayMs + RTT_MARGIN_MS;
                FrameCodec.Frame r = inbox.poll(waitMs, TimeUnit.MILLISECONDS);
                if (r == null) {
                    if (++retries > MAX_RETRIES)
                        throw new TransferException("Канал не отвечает (блок " + b + ")");
                    tx.sendPaced(encodeBlockEnd(b, count));      // END/ответ потерян → повторяем END
                    sent = 1;                                    // данные уже слиты, ждём только END+RTT
                    continue;
                }
                if (r.payload.length < 1 || (r.payload[0] & 0xFF) != (b & 0xFF)) continue; // чужой блок
                if (r.type == BulkProtocol.TYPE_BLOCK_DONE) break;
                if (r.type == BulkProtocol.TYPE_NACK) {
                    boolean[] mask = parseNack(r.payload, count);
                    int miss = 0;
                    if (mask != null) for (boolean m : mask) if (m) miss++;
                    if (miss > 0) { sent = sendBlock(b, base, count, data, mask); retries = 0; }
                    else          { sent = 1; }                  // пустой NACK — ждём DONE ещё круг
                }
            }
            if (progress != null) progress.accept((double)(b + 1) / numBlocks);
        }

        byte[] end = FrameCodec.encode(seq++, BulkProtocol.TYPE_FILE_END, new byte[0]);
        for (int i = 0; i < 3; i++) tx.sendPaced(end);            // FILE_END ×3
    }

    /**
     * Отправляет DATA-кадры блока (idx 0..count-1) с пейсингом, затем BLOCK_END.
     * mask=null → все. Возвращает число поставленных в очередь DATA-кадров.
     */
    private int sendBlock(int blk, int base, int count, byte[] data, boolean[] mask) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (mask != null && !mask[i]) continue;
            int off = (base + i) * BulkProtocol.DATA_BYTES;
            int len = Math.min(BulkProtocol.DATA_BYTES, data.length - off);
            byte[] p = new byte[3 + len];
            p[0] = (byte) blk;
            p[1] = (byte)(i & 0xFF);
            p[2] = (byte)((i >> 8) & 0xFF);
            System.arraycopy(data, off, p, 3, len);
            tx.sendPaced(FrameCodec.encode(seq++, BulkProtocol.TYPE_DATA, p));
            n++;
        }
        tx.sendPaced(encodeBlockEnd(blk, count));
        return n;
    }

    private byte[] encodeBlockEnd(int blk, int count) {
        byte[] p = { (byte) blk, (byte)(count & 0xFF), (byte)((count >> 8) & 0xFF) };
        return FrameCodec.encode(seq++, BulkProtocol.TYPE_BLOCK_END, p);
    }

    private byte[] encodeFileBegin(int kind, String name, int size, int blocks) {
        byte[] nm = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (nm.length > 200) nm = java.util.Arrays.copyOf(nm, 200);
        byte[] p = new byte[1 + 2 + 4 + 1 + nm.length];
        p[0] = (byte) kind;
        p[1] = (byte)(blocks & 0xFF);       p[2] = (byte)((blocks >> 8) & 0xFF);
        p[3] = (byte)(size & 0xFF);         p[4] = (byte)((size >> 8) & 0xFF);
        p[5] = (byte)((size >> 16) & 0xFF); p[6] = (byte)((size >> 24) & 0xFF);
        p[7] = (byte) nm.length;
        System.arraycopy(nm, 0, p, 8, nm.length);
        return FrameCodec.encode(seq++, BulkProtocol.TYPE_FILE_BEGIN, p);
    }

    private static boolean[] parseNack(byte[] payload, int count) {
        if (payload.length < 3) return null;
        int n = (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
        boolean[] mask = new boolean[count];
        boolean any = false;
        for (int i = 0; i < n && i < count; i++) {
            int bytePos = 3 + i / 8;
            if (bytePos >= payload.length) break;
            if ((payload[bytePos] & (1 << (i % 8))) == 0) { mask[i] = true; any = true; }
        }
        return any ? mask : new boolean[count]; // пустая маска = ничего не досылать
    }

    private static final class TransferException extends Exception {
        TransferException(String m) { super(m); }
    }
}
