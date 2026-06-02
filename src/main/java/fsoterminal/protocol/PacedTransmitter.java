package fsoterminal.protocol;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Один фоновый поток-отправитель на канал. Держит две очереди:
 *  - hi: текстовые MSG-кадры — без задержки, обгоняют файл (приоритет);
 *  - lo: кадры файла (BEGIN/DATA/END/...) — с пейсингом dataDelayMs между ними,
 *        порядок строго сохраняется.
 *
 * frameOutput должен быть неблокирующим (SerialChannel.send пишет в буфер ОС).
 * См. docs/BULK_PROTOCOL_DESIGN.md, разделы 6–7.
 */
public class PacedTransmitter {

    private final Consumer<byte[]> frameOutput;
    private final BlockingQueue<byte[]> hi = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> lo = new LinkedBlockingQueue<>();

    private volatile long    dataDelayMs = 20;
    private volatile boolean running     = false;
    private Thread thread;

    public PacedTransmitter(Consumer<byte[]> frameOutput) {
        this.frameOutput = frameOutput;
    }

    /** Задержка между кадрами файла (low-priority), мс. */
    public void setDataDelayMs(long ms) { this.dataDelayMs = ms; }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "paced-tx");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /** Высокий приоритет: MSG/MSG_ACK — без задержки, обгоняет файл. */
    public void sendNow(byte[] frame) { hi.offer(frame); }

    /** Низкий приоритет: кадры файла — с пейсингом, порядок сохраняется. */
    public void sendPaced(byte[] frame) { lo.offer(frame); }

    /** Очистить очередь файла (например, при отмене передачи). */
    public void clearPaced() { lo.clear(); }

    private void loop() {
        while (running) {
            try {
                byte[] f = hi.poll();
                if (f != null) { frameOutput.accept(f); continue; }   // hi без задержки

                f = lo.poll(50, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                frameOutput.accept(f);
                long d = dataDelayMs;
                if (d > 0) Thread.sleep(d);
            } catch (InterruptedException e) {
                if (!running) return;
            } catch (Exception ex) {
                // frameOutput бросил исключение (порт закрыт?) — не роняем поток
            }
        }
    }
}
