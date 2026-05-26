package fsoterminal.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AckProcessorTest {

    private List<byte[]>      sent;
    private SlidingWindowSender sender;
    private AckProcessor        proc;

    @BeforeEach
    void setUp() {
        sent   = new ArrayList<>();
        sender = new SlidingWindowSender(4, sent::add);
        proc   = new AckProcessor(sender);
    }

    // Вспомогательный: кодирует ACK-кадр
    private byte[] makeAck(int seq, int windowBase, int bitmap) {
        byte[] payload = {
            (byte) windowBase,
            (byte) (bitmap & 0xFF),
            (byte) ((bitmap >> 8) & 0xFF)
        };
        return FrameCodec.encode(seq, FrameCodec.TYPE_ACK, payload);
    }

    // --- ACK → sender.onAck ---

    @Test
    void feed_ackFrame_releasesCredit() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1}); // SEQ=0

        proc.feed(makeAck(0, 0, 0b0001));

        assertEquals(0, sender.inFlight());
        assertEquals(4, sender.availableCredits());
    }

    @Test
    void feed_partialAck_retransmitsMissingFrame() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1}); // SEQ=0
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2}); // SEQ=1
        sender.send(FrameCodec.TYPE_DATA, new byte[]{3}); // SEQ=2
        sent.clear();

        // SEQ=0 и SEQ=2 получены, SEQ=1 — нет
        proc.feed(makeAck(0, 0, 0b0101));

        assertEquals(1, sent.size());
        FrameCodec.Frame retransmitted = decode(sent.get(0));
        assertNotNull(retransmitted);
        assertEquals(1, retransmitted.seq);
    }

    @Test
    void feed_noneConfirmed_retransmitsAll() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2});
        sent.clear();

        proc.feed(makeAck(0, 0, 0b0000));

        assertEquals(2, sent.size());
    }

    @Test
    void feed_ackWithShortPayload_ignored() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});
        int before = sender.inFlight();

        byte[] badAck = FrameCodec.encode(0, FrameCodec.TYPE_ACK, new byte[]{0x00, 0x01}); // только 2 байта
        proc.feed(badAck);

        assertEquals(before, sender.inFlight()); // ничего не изменилось
    }

    // --- DATA → dataHandler ---

    @Test
    void feed_dataFrame_callsDataHandler() {
        List<FrameCodec.Frame> received = new ArrayList<>();
        proc.setDataHandler(received::add);

        proc.feed(FrameCodec.encode(5, FrameCodec.TYPE_DATA, new byte[]{0x42}));

        assertEquals(1, received.size());
        assertEquals(5, received.get(0).seq);
        assertArrayEquals(new byte[]{0x42}, received.get(0).payload);
    }

    @Test
    void feed_dataFrame_noHandler_noException() {
        assertDoesNotThrow(() ->
            proc.feed(FrameCodec.encode(0, FrameCodec.TYPE_DATA, new byte[]{1})));
    }

    // --- FILE_BEGIN / FILE_DATA / FILE_END → dataHandler (через скользящее окно) ---

    @Test
    void feed_fileBeginFrame_callsDataHandler() {
        List<FrameCodec.Frame> received = new ArrayList<>();
        proc.setDataHandler(received::add);

        proc.feed(FrameCodec.encode(0, FrameCodec.TYPE_FILE_BEGIN, new byte[]{'f', 'o', 'o'}));

        assertEquals(1, received.size());
        assertEquals(FrameCodec.TYPE_FILE_BEGIN, received.get(0).type);
    }

    @Test
    void feed_fileDataFrame_callsDataHandler() {
        List<FrameCodec.Frame> received = new ArrayList<>();
        proc.setDataHandler(received::add);

        proc.feed(FrameCodec.encode(1, FrameCodec.TYPE_FILE_DATA, new byte[]{1, 2, 3}));

        assertEquals(1, received.size());
        assertEquals(FrameCodec.TYPE_FILE_DATA, received.get(0).type);
    }

    @Test
    void feed_fileEndFrame_callsDataHandler() {
        List<FrameCodec.Frame> received = new ArrayList<>();
        proc.setDataHandler(received::add);

        proc.feed(FrameCodec.encode(2, FrameCodec.TYPE_FILE_END, new byte[0]));

        assertEquals(1, received.size());
        assertEquals(FrameCodec.TYPE_FILE_END, received.get(0).type);
    }

    // --- PROBE → probeHandler ---

    @Test
    void feed_probeFrame_callsProbeHandler() {
        boolean[] called = {false};
        proc.setProbeHandler(() -> called[0] = true);

        proc.feed(FrameCodec.encode(0, FrameCodec.TYPE_PROBE, new byte[0]));

        assertTrue(called[0]);
    }

    // --- Несколько кадров в одном feed ---

    @Test
    void feed_multipleFrames_allDispatched() {
        List<FrameCodec.Frame> received = new ArrayList<>();
        proc.setDataHandler(received::add);

        byte[] f1 = FrameCodec.encode(1, FrameCodec.TYPE_DATA, new byte[]{0x01});
        byte[] f2 = FrameCodec.encode(2, FrameCodec.TYPE_DATA, new byte[]{0x02});

        proc.feed(concat(f1, f2));

        assertEquals(2, received.size());
        assertEquals(1, received.get(0).seq);
        assertEquals(2, received.get(1).seq);
    }

    // --- Вспомогательные методы ---

    private FrameCodec.Frame decode(byte[] raw) {
        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(raw);
        return dec.poll();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
