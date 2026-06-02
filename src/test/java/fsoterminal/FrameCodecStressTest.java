package fsoterminal;

import fsoterminal.protocol.FrameCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Стресс-тест декодера: воспроизводит фрагментацию потока, как её делает
 * USB-драйвер (рандомные куски 51–64 байта) и jSerialComm (большие куски из
 * буфера ОС). Железо не требуется.
 *
 * Цель — поймать потери кадров на «идеальном» канале (STM32 ничего не теряет),
 * которые на самом деле возникают в Java при сборке кадров из фрагментов.
 */
class FrameCodecStressTest {

    private static final int BULK_PAYLOAD = 238;       // как в реальном bulk
    private static final int FRAMES       = 200;       // ~48 КБ данных

    /** Строит поток из FRAMES валидных bulk-кадров. Payload содержит 0xAA-байты. */
    private static byte[] buildStream(byte[][] expectedPayloads) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < FRAMES; i++) {
            byte[] payload = new byte[2 + BULK_PAYLOAD];
            payload[0] = (byte)(i & 0xFF);
            payload[1] = (byte)((i >> 8) & 0xFF);
            // Данные специально содержат 0xAA (=SOF) и псевдо-заголовки
            for (int j = 0; j < BULK_PAYLOAD; j++)
                payload[2 + j] = (byte)((i * 71 + j * 13) & 0xFF);
            expectedPayloads[i] = payload;
            byte[] frame = FrameCodec.encode(i & 0xFF, FrameCodec.TYPE_DATA, payload);
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private static List<FrameCodec.Frame> decodeAll(FrameCodec.Decoder dec) {
        List<FrameCodec.Frame> frames = new ArrayList<>();
        FrameCodec.Frame f;
        while ((f = dec.poll()) != null) frames.add(f);
        return frames;
    }

    // =========================================================================

    /** Мелкие куски 51–64 байта с poll() после каждого — так ведёт себя USB. */
    @Test
    void decode_smallChunks_pollEach_noLoss() {
        byte[][] expected = new byte[FRAMES][];
        byte[] stream = buildStream(expected);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        List<FrameCodec.Frame> got = new ArrayList<>();
        Random rnd = new Random(42);

        int pos = 0;
        while (pos < stream.length) {
            int chunk = 51 + rnd.nextInt(14); // 51..64
            chunk = Math.min(chunk, stream.length - pos);
            dec.feed(stream, pos, chunk);
            pos += chunk;
            got.addAll(decodeAll(dec));
        }
        got.addAll(decodeAll(dec));

        assertNoLoss(expected, got, "мелкие куски 51–64 Б + poll после каждого");
    }

    /**
     * Куски до 4 КБ — потолок реального буфера ОС: jSerialComm отдаёт его
     * содержимое разом, когда поток-слушатель проснулся с задержкой и накопилось
     * несколько кадров. Раньше кольцевой буфер (490 Б) тут переполнялся и терял
     * кадры ещё до poll(). Контракт декодера: один feed() ≤ BUF_SIZE, после
     * каждого feed() — слить poll() досуха.
     */
    @Test
    void decode_maxOsChunks_noLoss() {
        byte[][] expected = new byte[FRAMES][];
        byte[] stream = buildStream(expected);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        List<FrameCodec.Frame> got = new ArrayList<>();

        int pos = 0;
        while (pos < stream.length) {
            int chunk = Math.min(4096, stream.length - pos);
            dec.feed(stream, pos, chunk);
            pos += chunk;
            got.addAll(decodeAll(dec));
        }
        got.addAll(decodeAll(dec));

        assertNoLoss(expected, got, "куски до 4 КБ (потолок буфера ОС) + poll после каждого");
    }

    /**
     * Реалистичный худший случай: куски по ~1 КБ (буфер ОС), poll после каждого.
     * Даже с poll один кусок > 490 байт переполняет буфер при записи.
     */
    @Test
    void decode_osSizedChunks_noLoss() {
        byte[][] expected = new byte[FRAMES][];
        byte[] stream = buildStream(expected);

        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        List<FrameCodec.Frame> got = new ArrayList<>();

        int pos = 0;
        while (pos < stream.length) {
            int chunk = Math.min(1024, stream.length - pos);
            dec.feed(stream, pos, chunk);
            pos += chunk;
            got.addAll(decodeAll(dec));
        }
        got.addAll(decodeAll(dec));

        assertNoLoss(expected, got, "куски ~1 КБ (буфер ОС) + poll после каждого");
    }

    // =========================================================================

    private static void assertNoLoss(byte[][] expected, List<FrameCodec.Frame> got, String scenario) {
        int lost = 0;
        for (int i = 0; i < expected.length; i++) {
            boolean found = false;
            for (FrameCodec.Frame f : got) {
                if (f.payload.length == expected[i].length
                        && (f.payload[0] & 0xFF) == (i & 0xFF)
                        && (f.payload[1] & 0xFF) == ((i >> 8) & 0xFF)) {
                    assertArrayEquals(expected[i], f.payload,
                        "[" + scenario + "] кадр " + i + " повреждён");
                    found = true;
                    break;
                }
            }
            if (!found) lost++;
        }
        assertEquals(0, lost,
            "[" + scenario + "] потеряно кадров: " + lost + " из " + expected.length
            + " (декодировано " + got.size() + ")");
    }
}
