package fsoterminal.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Разбивает длинный текст на кадры и собирает обратно на приёмной стороне.
 *
 * Формат payload кадра TYPE_DATA:
 *   [FLAG: 1B] [текст UTF-8]
 *   FLAG = 0xFF — кадр промежуточный (продолжение следует)
 *   FLAG = 0x00 — кадр последний (или единственный)
 *
 * Максимум текста за кадр: MAX_PAYLOAD - 1 = 249 байт.
 * Для Кириллицы (2 байта/символ): ~124 символа на кадр.
 * Длина сообщения ограничена только шириной окна × кадров.
 */
public class TextAssembler {

    public static final byte FLAG_LAST = 0x00;
    public static final byte FLAG_MORE = (byte) 0xFF;
    public static final int  MAX_BYTES_PER_FRAME = FrameCodec.MAX_PAYLOAD - 1; // 249

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Consumer<String>       onComplete;

    public TextAssembler(Consumer<String> onComplete) {
        this.onComplete = onComplete;
    }

    // -------------------------------------------------------------------------
    // Приём
    // -------------------------------------------------------------------------

    /**
     * Принять кадр TYPE_DATA от SlidingWindowReceiver.
     * Когда получен последний фрагмент — вызывает onComplete с полным текстом.
     */
    public void onFrame(FrameCodec.Frame frame) {
        if (frame.payload.length == 0) return; // пустой payload — игнорируем
        byte flag = frame.payload[0];
        buffer.write(frame.payload, 1, frame.payload.length - 1);
        if (flag == FLAG_LAST) {
            if (onComplete != null)
                onComplete.accept(buffer.toString(StandardCharsets.UTF_8));
            buffer.reset();
        }
    }

    /** Сбросить буфер (вызвать при разрыве соединения). */
    public void reset() { buffer.reset(); }

    // -------------------------------------------------------------------------
    // Отправка
    // -------------------------------------------------------------------------

    /**
     * Разбить текст на payload-массивы для TYPE_DATA кадров.
     * Вызывающий код передаёт каждый из них в SlidingWindowSender.send().
     */
    public static byte[][] encodePayloads(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length == 0) {
            return new byte[][]{ new byte[]{ FLAG_LAST } };
        }

        int chunks = (textBytes.length + MAX_BYTES_PER_FRAME - 1) / MAX_BYTES_PER_FRAME;
        byte[][] payloads = new byte[chunks][];

        for (int i = 0; i < chunks; i++) {
            int start  = i * MAX_BYTES_PER_FRAME;
            int end    = Math.min(start + MAX_BYTES_PER_FRAME, textBytes.length);
            boolean last = (i == chunks - 1);

            payloads[i] = new byte[1 + (end - start)];
            payloads[i][0] = last ? FLAG_LAST : FLAG_MORE;
            System.arraycopy(textBytes, start, payloads[i], 1, end - start);
        }
        return payloads;
    }

    /** Сколько кадров займёт текст (для UI-подсказки). */
    public static int frameCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return (bytes + MAX_BYTES_PER_FRAME - 1) / MAX_BYTES_PER_FRAME;
    }
}
