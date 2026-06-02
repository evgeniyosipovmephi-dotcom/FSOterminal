package fsoterminal.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameCodecTest {

    // --- encode ---

    @Test
    void encode_startsWithSOF() {
        byte[] frame = FrameCodec.encode(1, FrameCodec.TYPE_DATA, new byte[]{0x01, 0x02});
        assertEquals(FrameCodec.SOF, frame[0]);
    }

    @Test
    void encode_headerFieldsCorrect() {
        byte[] frame = FrameCodec.encode(42, FrameCodec.TYPE_ACK, new byte[]{(byte)0xAA});
        assertEquals(42,                  frame[1] & 0xFF); // SEQ
        assertEquals(FrameCodec.TYPE_ACK, frame[2] & 0xFF); // TYPE
        assertEquals(1,                   frame[3] & 0xFF); // LEN
    }

    @Test
    void encode_totalLengthCorrect() {
        byte[] payload = new byte[10];
        byte[] frame = FrameCodec.encode(0, FrameCodec.TYPE_DATA, payload);
        assertEquals(FrameCodec.HEADER + 10 + FrameCodec.TRAILER, frame.length);
    }

    @Test
    void encode_emptyPayload() {
        byte[] frame = FrameCodec.encode(0, FrameCodec.TYPE_PROBE, new byte[0]);
        assertEquals(FrameCodec.HEADER + FrameCodec.TRAILER, frame.length);
    }

    @Test
    void encode_maxPayload() {
        byte[] payload = new byte[FrameCodec.MAX_PAYLOAD];
        byte[] frame = FrameCodec.encode(0, FrameCodec.TYPE_DATA, payload);
        assertEquals(FrameCodec.MAX_FRAME, frame.length);
    }

    @Test
    void encode_maxPayload_fits255() {
        // MAX_PAYLOAD=240 + HEADER=4 + TRAILER=1 = 245 ≤ 255 (лимит пакета STM32)
        assertEquals(245, FrameCodec.MAX_FRAME);
        assertTrue(FrameCodec.MAX_FRAME <= 255, "кадр должен влезать в лимит STM32 (255 Б)");
    }

    @Test
    void encode_overMaxPayload_throws() {
        byte[] payload = new byte[FrameCodec.MAX_PAYLOAD + 1];
        assertThrows(IllegalArgumentException.class,
            () -> FrameCodec.encode(0, FrameCodec.TYPE_DATA, payload));
    }

    // --- decode (round-trip) ---

    @Test
    void decode_roundTrip_simplePayload() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        byte[] frame = FrameCodec.encode(7, FrameCodec.TYPE_DATA, payload);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(frame);
        FrameCodec.Frame f = dec.poll();

        assertNotNull(f);
        assertEquals(7,                  f.seq);
        assertEquals(FrameCodec.TYPE_DATA, f.type);
        assertArrayEquals(payload,         f.payload);
    }

    @Test
    void decode_roundTrip_emptyPayload() {
        byte[] frame = FrameCodec.encode(0, FrameCodec.TYPE_PROBE, new byte[0]);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(frame);
        FrameCodec.Frame f = dec.poll();

        assertNotNull(f);
        assertEquals(0,                    f.seq);
        assertEquals(FrameCodec.TYPE_PROBE, f.type);
        assertEquals(0,                    f.payload.length);
    }

    @Test
    void decode_seqWraparound() {
        byte[] frame = FrameCodec.encode(255, FrameCodec.TYPE_DATA, new byte[]{0x00});

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(frame);
        assertEquals(255, dec.poll().seq);
    }

    // --- Разбитая доставка (fragmentation) ---

    @Test
    void decode_byteByByte() {
        byte[] payload = new byte[]{(byte)0xDE, (byte)0xAD};
        byte[] frame = FrameCodec.encode(3, FrameCodec.TYPE_DATA, payload);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        FrameCodec.Frame result = null;

        for (byte b : frame) {
            dec.feed(new byte[]{b});
            FrameCodec.Frame f = dec.poll();
            if (f != null) result = f;
        }

        assertNotNull(result);
        assertArrayEquals(payload, result.payload);
    }

    // --- Восстановление синхронизации ---

    @Test
    void decode_garbageBeforeFrame() {
        byte[] payload = new byte[]{0x55};
        byte[] frame = FrameCodec.encode(1, FrameCodec.TYPE_DATA, payload);

        // Мусор перед кадром
        byte[] garbage = {0x00, 0x11, 0x22};
        byte[] stream = concat(garbage, frame);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(stream);
        FrameCodec.Frame f = dec.poll();

        assertNotNull(f);
        assertArrayEquals(payload, f.payload);
    }

    @Test
    void decode_corruptedFrameThenValidFrame() {
        byte[] good = FrameCodec.encode(2, FrameCodec.TYPE_DATA, new byte[]{0x42});

        // Испорченный кадр: SOF есть, но CRC неверный
        byte[] bad = FrameCodec.encode(1, FrameCodec.TYPE_DATA, new byte[]{(byte)0x99});
        bad[bad.length - 1] ^= 0xFF; // портим CRC

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(concat(bad, good));

        FrameCodec.Frame f = dec.poll();
        assertNotNull(f);
        assertEquals(2, f.seq); // должен найти второй (правильный) кадр
    }

    @Test
    void decode_twoFramesBackToBack() {
        byte[] f1 = FrameCodec.encode(10, FrameCodec.TYPE_DATA, new byte[]{0x01});
        byte[] f2 = FrameCodec.encode(11, FrameCodec.TYPE_DATA, new byte[]{0x02});

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(concat(f1, f2));

        assertEquals(10, dec.poll().seq);
        assertEquals(11, dec.poll().seq);
        assertNull(dec.poll());
    }

    @Test
    void decode_sofInPayload_noFalsePositive() {
        // 0x7E внутри payload не должен сбивать декодер
        byte[] payload = new byte[]{0x7E, 0x7E, 0x7E};
        byte[] frame = FrameCodec.encode(5, FrameCodec.TYPE_DATA, payload);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(frame);
        FrameCodec.Frame f = dec.poll();

        assertNotNull(f);
        assertArrayEquals(payload, f.payload);
    }

    @Test
    void decode_incompleteFrame_returnsNull() {
        byte[] frame = FrameCodec.encode(1, FrameCodec.TYPE_DATA, new byte[]{0x01, 0x02, 0x03});

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(frame, 0, frame.length - 2); // последние 2 байта не пришли

        assertNull(dec.poll());
    }

    // --- Вспомогательный метод ---

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
