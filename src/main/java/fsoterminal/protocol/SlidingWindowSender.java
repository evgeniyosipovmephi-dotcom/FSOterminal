package fsoterminal.protocol;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Отправитель с раздвижным окном и кредитным управлением потоком.
 *
 * Один поток вызывает send() — блокируется если окно заполнено.
 * Другой поток вызывает onAck() при получении ACK от удалённой стороны.
 * Таймер (снаружи) вызывает retransmitUnconfirmed() при истечении ожидания.
 *
 * frameOutput должен быть неблокирующим (добавляет в очередь, не пишет напрямую в порт).
 */
public class SlidingWindowSender {

    private final int              windowSize;
    private final Consumer<byte[]> frameOutput;

    private final byte[][]  slots;      // закодированные кадры для ретрансмита
    private final boolean[] confirmed;  // подтверждены ли кадры в слоте

    private int base = 0;  // абсолютный счётчик старейшего неподтверждённого кадра
    private int next = 0;  // абсолютный счётчик следующего отправляемого

    private final Semaphore credits;

    public SlidingWindowSender(int windowSize, Consumer<byte[]> frameOutput) {
        if (windowSize < 1 || windowSize > 16)
            throw new IllegalArgumentException("windowSize: 1..16");
        this.windowSize  = windowSize;
        this.frameOutput = frameOutput;
        this.slots       = new byte[windowSize][];
        this.confirmed   = new boolean[windowSize];
        this.credits     = new Semaphore(windowSize);
    }

    // -------------------------------------------------------------------------
    // Отправка
    // -------------------------------------------------------------------------

    /**
     * Закодировать и отправить кадр. Блокируется если окно заполнено.
     */
    public void send(int type, byte[] payload) throws InterruptedException {
        credits.acquire();
        doSend(type, payload);
    }

    /**
     * Попытаться отправить с таймаутом. Возвращает false если окно заполнено дольше timeoutMs.
     */
    public boolean trySend(int type, byte[] payload, long timeoutMs) throws InterruptedException {
        if (!credits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return false;
        doSend(type, payload);
        return true;
    }

    private synchronized void doSend(int type, byte[] payload) {
        int slot  = next % windowSize;
        byte[] frame = FrameCodec.encode(next & 0xFF, type, payload);
        slots[slot]     = frame;
        confirmed[slot] = false;
        next++;
        frameOutput.accept(frame);
    }

    // -------------------------------------------------------------------------
    // Обработка ACK
    // -------------------------------------------------------------------------

    /**
     * Обработать ACK-bitmap от удалённой стороны.
     *
     * @param ackBase SEQ первого кадра в окне отправителя на момент отправки ACK
     * @param bitmap  16-битная маска: бит i=1 → кадр (ackBase+i) получен
     */
    /**
     * Обработать ACK-bitmap от удалённой стороны.
     *
     * @param ackBase SEQ следующего ожидаемого кадра у получателя
     *                (все кадры до ackBase считаются подтверждёнными неявно)
     * @param bitmap  16-битная маска: бит i=1 → кадр (ackBase+i) получен
     */
    public synchronized void onAck(int ackBase, int bitmap) {
        int baseRolling = base & 0xFF;
        int advance = (ackBase - baseRolling + 256) % 256;

        if (advance > windowSize) return; // вне окна — устаревший или чужой ACK

        // Кадры до ackBase подтверждены неявно (получатель уже принял их все)
        for (int i = 0; i < advance; i++)
            confirmed[(base + i) % windowSize] = true;

        // Кадры начиная с ackBase — из bitmap
        int inFlight = next - base;
        for (int i = advance; i < inFlight && (i - advance) < 16; i++) {
            if ((bitmap >> (i - advance) & 1) == 1)
                confirmed[(base + i) % windowSize] = true;
        }

        // Сдвигаем базу через подряд идущие подтверждённые, освобождаем кредиты
        int released = 0;
        while (base < next && confirmed[base % windowSize]) {
            confirmed[base % windowSize] = false;
            base++;
            released++;
        }
        if (released > 0) credits.release(released);

        // Досылаем кадры которые получатель не видел
        for (int i = base; i < next; i++) {
            if (!confirmed[i % windowSize])
                frameOutput.accept(slots[i % windowSize]);
        }
    }

    // -------------------------------------------------------------------------
    // Ретрансмит по таймауту
    // -------------------------------------------------------------------------

    /**
     * Переотправить все неподтверждённые кадры. Вызывается внешним таймером.
     */
    public synchronized void retransmitUnconfirmed() {
        for (int i = base; i < next; i++)
            frameOutput.accept(slots[i % windowSize]);
    }

    // -------------------------------------------------------------------------
    // Состояние (для диагностики и тестов)
    // -------------------------------------------------------------------------

    public synchronized int inFlight()      { return next - base; }
    public int              availableCredits() { return credits.availablePermits(); }
    public synchronized int nextSeq()       { return next & 0xFF; }
    public synchronized int baseSeq()       { return base & 0xFF; }
}
