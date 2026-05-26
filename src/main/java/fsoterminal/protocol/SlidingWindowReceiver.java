package fsoterminal.protocol;

import java.util.function.Consumer;

/**
 * Принимающая сторона скользящего окна.
 *
 * Буферизует кадры, пришедшие не по порядку, доставляет данные строго
 * последовательно и генерирует ACK-кадры с bitmap подтверждений.
 *
 * Вызов: подключить к AckProcessor.setDataHandler(receiver::onFrame).
 * ackOutput должен быть неблокирующим (добавляет в очередь, не пишет в порт).
 */
public class SlidingWindowReceiver {

    private final int windowSize;
    private final Consumer<byte[]>           ackOutput;   // отправка ACK обратно
    private final Consumer<FrameCodec.Frame> dataOutput;  // доставка данных приложению

    private final FrameCodec.Frame[] slots;
    private final boolean[]          received;

    private int base   = 0;  // абсолютный счётчик следующего ожидаемого кадра
    private int ackSeq = 0;  // rolling SEQ для исходящих ACK-кадров

    public SlidingWindowReceiver(int windowSize,
                                 Consumer<byte[]> ackOutput,
                                 Consumer<FrameCodec.Frame> dataOutput) {
        if (windowSize < 1 || windowSize > 16)
            throw new IllegalArgumentException("windowSize: 1..16");
        this.windowSize = windowSize;
        this.ackOutput  = ackOutput;
        this.dataOutput = dataOutput;
        this.slots      = new FrameCodec.Frame[windowSize];
        this.received   = new boolean[windowSize];
    }

    // -------------------------------------------------------------------------
    // Приём кадра
    // -------------------------------------------------------------------------

    /**
     * Принять DATA-кадр (вызывается из AckProcessor.dataHandler).
     * Кадры внутри окна буферизуются; последовательные — сразу доставляются.
     */
    public synchronized void onFrame(FrameCodec.Frame frame) {
        int offset = (frame.seq - (base & 0xFF) + 256) % 256;

        if (offset < windowSize) {
            int slot = (base + offset) % windowSize;
            if (!received[slot]) {
                slots[slot]    = frame;
                received[slot] = true;
            }
            // дубликат — идемпотентно, ACK всё равно отправим
        }
        // else: вне окна или старый кадр — ACK отправим с текущим состоянием

        // Доставляем все последовательно идущие кадры
        while (received[base % windowSize]) {
            int idx = base % windowSize;
            if (dataOutput != null) dataOutput.accept(slots[idx]);
            slots[idx]    = null;
            received[idx] = false;
            base++;
        }

        sendAck();
    }

    // -------------------------------------------------------------------------
    // Внутренние методы
    // -------------------------------------------------------------------------

    private void sendAck() {
        int baseRolling = base & 0xFF;
        int bitmap = 0;
        for (int i = 0; i < windowSize; i++) {
            if (received[(base + i) % windowSize])
                bitmap |= (1 << i);
        }
        byte[] payload = {
            (byte) baseRolling,
            (byte) (bitmap       & 0xFF),
            (byte) ((bitmap >> 8) & 0xFF)
        };
        ackOutput.accept(FrameCodec.encode(ackSeq++ & 0xFF, FrameCodec.TYPE_ACK, payload));
    }

    // -------------------------------------------------------------------------
    // Состояние (диагностика и тесты)
    // -------------------------------------------------------------------------

    public synchronized int baseSeq() { return base & 0xFF; }
    public synchronized int baseAbs() { return base; }
}
