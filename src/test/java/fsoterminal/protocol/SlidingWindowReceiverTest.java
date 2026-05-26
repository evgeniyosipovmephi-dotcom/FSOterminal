package fsoterminal.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowReceiverTest {

    private List<byte[]>           acks;
    private List<FrameCodec.Frame> delivered;
    private SlidingWindowReceiver  receiver;

    @BeforeEach
    void setUp() {
        acks      = new ArrayList<>();
        delivered = new ArrayList<>();
        receiver  = new SlidingWindowReceiver(4, acks::add, delivered::add);
    }

    // --- Базовый приём ---

    @Test
    void onFrame_singleFrame_delivered() {
        receiver.onFrame(frame(0, new byte[]{0x42}));

        assertEquals(1, delivered.size());
        assertArrayEquals(new byte[]{0x42}, delivered.get(0).payload);
        assertEquals(0, delivered.get(0).seq);
    }

    @Test
    void onFrame_inOrder_allDelivered() {
        receiver.onFrame(frame(0, new byte[]{1}));
        receiver.onFrame(frame(1, new byte[]{2}));
        receiver.onFrame(frame(2, new byte[]{3}));

        assertEquals(3, delivered.size());
        assertArrayEquals(new byte[]{1}, delivered.get(0).payload);
        assertArrayEquals(new byte[]{2}, delivered.get(1).payload);
        assertArrayEquals(new byte[]{3}, delivered.get(2).payload);
    }

    @Test
    void onFrame_inOrder_advancesBase() {
        receiver.onFrame(frame(0, new byte[]{1}));
        receiver.onFrame(frame(1, new byte[]{2}));

        assertEquals(2, receiver.baseSeq());
    }

    // --- Не по порядку ---

    @Test
    void onFrame_outOfOrder_buffersUntilGapFilled() {
        // Приходят 2, 0, 1 — доставка должна быть 0, 1, 2
        receiver.onFrame(frame(2, new byte[]{3}));
        assertEquals(0, delivered.size()); // ждём 0

        receiver.onFrame(frame(0, new byte[]{1}));
        assertEquals(1, delivered.size()); // доставлен 0, ждём 1

        receiver.onFrame(frame(1, new byte[]{2}));
        assertEquals(3, delivered.size()); // доставлены 1 и 2
    }

    @Test
    void onFrame_outOfOrder_deliveryOrderCorrect() {
        receiver.onFrame(frame(2, new byte[]{30}));
        receiver.onFrame(frame(0, new byte[]{10}));
        receiver.onFrame(frame(1, new byte[]{20}));

        assertArrayEquals(new byte[]{10}, delivered.get(0).payload);
        assertArrayEquals(new byte[]{20}, delivered.get(1).payload);
        assertArrayEquals(new byte[]{30}, delivered.get(2).payload);
    }

    // --- Дубликаты ---

    @Test
    void onFrame_duplicate_deliveredOnce() {
        receiver.onFrame(frame(0, new byte[]{1}));
        receiver.onFrame(frame(0, new byte[]{1})); // повторная доставка

        assertEquals(1, delivered.size());
    }

    @Test
    void onFrame_duplicateAfterDelivery_ignoredSilently() {
        receiver.onFrame(frame(0, new byte[]{1}));
        receiver.onFrame(frame(1, new byte[]{2}));
        receiver.onFrame(frame(0, new byte[]{1})); // уже доставленный (вне окна)

        assertEquals(2, delivered.size());
    }

    // --- ACK ---

    @Test
    void onFrame_alwaysSendsAck() {
        receiver.onFrame(frame(0, new byte[]{1}));
        receiver.onFrame(frame(1, new byte[]{2}));

        assertEquals(2, acks.size());
    }

    @Test
    void onFrame_ackWindowBase_advancesAfterDelivery() {
        receiver.onFrame(frame(0, new byte[]{1}));
        int[] ack = decodeAck(acks.get(0));

        assertEquals(1, ack[0]); // WINDOW_BASE=1 (кадр 0 подтверждён)
        assertEquals(0, ack[1]); // BITMAP=0 (нет буферизованных)
    }

    @Test
    void onFrame_ackBitmap_reflectsOutOfOrderBuffered() {
        // Кадр 2 пришёл, кадр 0 ещё нет → base=0, бит 2 установлен
        receiver.onFrame(frame(2, new byte[]{3}));
        int[] ack = decodeAck(acks.get(0));

        assertEquals(0, ack[0]);     // WINDOW_BASE=0
        assertEquals(0b100, ack[1]); // бит 2 = кадр 2 получен
    }

    @Test
    void onFrame_ackAfterGapFilled_bitmapClear() {
        receiver.onFrame(frame(2, new byte[]{3}));
        receiver.onFrame(frame(0, new byte[]{1}));
        receiver.onFrame(frame(1, new byte[]{2}));

        int[] lastAck = decodeAck(acks.get(acks.size() - 1));
        assertEquals(3, lastAck[0]); // все три подтверждены
        assertEquals(0, lastAck[1]);
    }

    // --- Оборачивание SEQ ---

    @Test
    void onFrame_seqWrapsAt256() {
        SlidingWindowReceiver r = new SlidingWindowReceiver(1, a -> {}, delivered::add);

        for (int i = 0; i < 257; i++) {
            r.onFrame(frame(i & 0xFF, new byte[]{(byte) i}));
        }

        assertEquals(257, delivered.size());
        assertEquals(1, r.baseSeq()); // 257 & 0xFF
    }

    // --- Window size 1 ---

    @Test
    void windowSize1_worksCorrectly() {
        SlidingWindowReceiver r = new SlidingWindowReceiver(1, acks::add, delivered::add);

        r.onFrame(frame(0, new byte[]{10}));
        r.onFrame(frame(1, new byte[]{20}));

        assertEquals(2, delivered.size());
        assertEquals(2, r.baseSeq());
    }

    // --- Вспомогательные методы ---

    private FrameCodec.Frame frame(int seq, byte[] payload) {
        return new FrameCodec.Frame(seq, FrameCodec.TYPE_DATA, payload);
    }

    /** Декодирует ACK-кадр → [windowBase, bitmap] */
    private int[] decodeAck(byte[] raw) {
        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(raw);
        FrameCodec.Frame f = dec.poll();
        assertNotNull(f, "Не удалось декодировать ACK");
        assertEquals(FrameCodec.TYPE_ACK, f.type);
        assertTrue(f.payload.length >= 3);
        int windowBase = f.payload[0] & 0xFF;
        int bitmap     = (f.payload[1] & 0xFF) | ((f.payload[2] & 0xFF) << 8);
        return new int[]{windowBase, bitmap};
    }
}
