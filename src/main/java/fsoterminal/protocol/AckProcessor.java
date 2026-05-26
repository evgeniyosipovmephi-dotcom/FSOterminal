package fsoterminal.protocol;

import java.util.function.Consumer;

/**
 * Обрабатывает входящий байтовый поток: декодирует кадры и распределяет по типу.
 *
 * ACK       → SlidingWindowSender.onAck()
 * PROBE     → probeHandler
 * PROBE_RESP→ probeRespHandler
 * Всё остальное (DATA, FILE_BEGIN, FILE_DATA, FILE_END, VOICE …)
 *           → dataHandler (= SlidingWindowReceiver.onFrame())
 *
 * Важно: все «данные» проходят через один скользящий приёмник — порядок гарантирован.
 */
public class AckProcessor {

    private final FrameCodec.Decoder   decoder = new FrameCodec.Decoder();
    private final SlidingWindowSender  sender;

    private Consumer<FrameCodec.Frame> dataHandler;      // DATA + FILE_* + VOICE
    private Runnable                   probeHandler;
    private Consumer<FrameCodec.Frame> probeRespHandler;

    public AckProcessor(SlidingWindowSender sender) {
        this.sender = sender;
    }

    public void setDataHandler(Consumer<FrameCodec.Frame> h)      { dataHandler      = h; }
    public void setProbeHandler(Runnable h)                        { probeHandler     = h; }
    public void setProbeRespHandler(Consumer<FrameCodec.Frame> h)  { probeRespHandler = h; }

    /**
     * Принять байты из канала. Можно вызывать частями — декодер хранит состояние.
     */
    public void feed(byte[] data) {
        decoder.feed(data);
        FrameCodec.Frame frame;
        while ((frame = decoder.poll()) != null) {
            dispatch(frame);
        }
    }

    private void dispatch(FrameCodec.Frame frame) {
        switch (frame.type) {
            case FrameCodec.TYPE_ACK        -> handleAck(frame);
            case FrameCodec.TYPE_PROBE      -> { if (probeHandler     != null) probeHandler.run();             }
            case FrameCodec.TYPE_PROBE_RESP -> { if (probeRespHandler != null) probeRespHandler.accept(frame); }
            // DATA, FILE_BEGIN, FILE_DATA, FILE_END, VOICE — всё через скользящее окно
            default                         -> { if (dataHandler      != null) dataHandler.accept(frame);      }
        }
    }

    /**
     * ACK payload: [WINDOW_BASE: 1B][BITMAP_LOW: 1B][BITMAP_HIGH: 1B]
     * bitmap бит i = 1 означает что кадр (base+i) получен.
     */
    private void handleAck(FrameCodec.Frame frame) {
        if (frame.payload.length < 3) return; // некорректный ACK — игнорируем
        int ackBase = frame.payload[0] & 0xFF;
        int bitmap  = (frame.payload[1] & 0xFF) | ((frame.payload[2] & 0xFF) << 8);
        sender.onAck(ackBase, bitmap);
    }
}
