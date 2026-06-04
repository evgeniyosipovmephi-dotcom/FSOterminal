package fsoterminal.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Лёгкая дорожка текстовых сообщений поверх того же канала, что и bulk-файлы.
 * См. docs/BULK_PROTOCOL_DESIGN.md, раздел 7.
 *
 * - Фрагментация: текст режется на куски ≤57 байт UTF-8 (флаг MORE/LAST).
 *   Приёмник склеивает БАЙТЫ и декодирует целиком на LAST — мультибайтовые
 *   символы не страдают при разрезе.
 * - Надёжность: stop-and-wait по каждому фрагменту (msg_id + MSG_ACK, повтор по таймауту).
 * - Приоритет: MSG идёт через PacedTransmitter.sendNow — обгоняет кадры файла.
 */
public class MsgChannel {

    private static final int ACK_TIMEOUT_MS = 600;
    private static final int MAX_RETRIES    = 10;

    private final PacedTransmitter  tx;
    private final Consumer<String>  onText;       // доставленное сообщение целиком

    private final BlockingQueue<Integer> ackInbox = new LinkedBlockingQueue<>();
    private int sendId = 0;
    private int seq    = 0;

    // Сборка на приёмной стороне
    private final ByteArrayOutputStream rxBuf = new ByteArrayOutputStream();
    private int lastRxId = -1;

    public MsgChannel(PacedTransmitter tx, Consumer<String> onText) {
        this.tx     = tx;
        this.onText = onText;
    }

    /** Скормить декодированный кадр. Обрабатывает только MSG/MSG_ACK. */
    public void feed(FrameCodec.Frame fr) {
        if (fr.type == BulkProtocol.TYPE_MSG_ACK && fr.payload.length >= 1) {
            ackInbox.offer(fr.payload[0] & 0xFF);
        } else if (fr.type == BulkProtocol.TYPE_MSG && fr.payload.length >= 2) {
            onMsg(fr.payload);
        }
    }

    /** Отправить текст в фоне (stop-and-wait по фрагментам). */
    public void send(String text, Runnable onDelivered, Consumer<String> onError) {
        Thread t = new Thread(() -> {
            try {
                sendBlocking(text);
                if (onDelivered != null) onDelivered.run();
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (TimeoutException te)    { if (onError != null) onError.accept(te.getMessage()); }
        }, "msg-send");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------

    private void sendBlocking(String text) throws InterruptedException, TimeoutException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int n = Math.max(1, (int) Math.ceil((double) bytes.length / BulkProtocol.MSG_BYTES));

        for (int k = 0; k < n; k++) {
            int id   = sendId++ & 0xFF;
            int flag = (k == n - 1) ? BulkProtocol.MSG_LAST : BulkProtocol.MSG_MORE;
            int off  = k * BulkProtocol.MSG_BYTES;
            int len  = Math.min(BulkProtocol.MSG_BYTES, bytes.length - off);
            byte[] p = new byte[2 + len];
            p[0] = (byte) id;
            p[1] = (byte) flag;
            System.arraycopy(bytes, off, p, 2, len);
            byte[] frame = FrameCodec.encode(seq++, BulkProtocol.TYPE_MSG, p);

            int retries = 0;
            while (!awaitAck(frame, id)) {
                if (++retries > MAX_RETRIES)
                    throw new TimeoutException("Текст не доставлен (фрагмент " + k + ")");
            }
        }
    }

    /** Шлёт кадр и ждёт MSG_ACK с нужным id до таймаута. true = подтверждено. */
    private boolean awaitAck(byte[] frame, int id) throws InterruptedException {
        ackInbox.clear();
        tx.sendNow(frame);
        long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        long remaining;
        while ((remaining = deadline - System.currentTimeMillis()) > 0) {
            Integer a = ackInbox.poll(remaining, TimeUnit.MILLISECONDS);
            if (a != null && a == id) return true;
        }
        return false;
    }

    private void onMsg(byte[] p) {
        int id   = p[0] & 0xFF;
        int flag = p[1] & 0xFF;
        tx.sendNow(FrameCodec.encode(0, BulkProtocol.TYPE_MSG_ACK, new byte[]{ (byte) id }));
        if (id == lastRxId) return;                 // дубликат (ACK потерялся) — не дописываем
        lastRxId = id;
        rxBuf.write(p, 2, p.length - 2);
        if (flag == BulkProtocol.MSG_LAST) {
            String text = new String(rxBuf.toByteArray(), StandardCharsets.UTF_8);
            rxBuf.reset();
            if (onText != null) onText.accept(text);
        }
    }

    private static final class TimeoutException extends Exception {
        TimeoutException(String m) { super(m); }
    }
}
