package fsoterminal.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Собирает файл из потока кадров FILE_BEGIN / FILE_DATA / FILE_END.
 *
 * Поток вызовов:
 *   FILE_BEGIN → onBegin(name, totalBytes)
 *   FILE_DATA  × N → onProgress(bytesReceived, totalBytes)
 *   FILE_END        → onComplete(name, data)
 *
 * Все колбэки вызываются в том потоке, где вызван onFrame() (поток получения).
 * Если нужно обновить UI — оборачивайте в Platform.runLater в колбэке.
 *
 * Формат FILE_BEGIN payload:
 *   [nameLen: 1B][name UTF-8 nameLen байт][totalSize: 4B LE]
 */
public class FileAssembler {

    /** Вызывается при начале передачи: (имя файла, полный размер в байтах). */
    private final BiConsumer<String, Long> onBegin;

    /**
     * Вызывается при получении каждого фрагмента:
     * (байт получено, байт всего).
     */
    private final BiConsumer<Long, Long> onProgress;

    /**
     * Вызывается когда файл собран полностью:
     * (имя файла, байты файла).
     */
    private final BiConsumer<String, byte[]> onComplete;

    // --- Внутреннее состояние ------------------------------------------------

    private String                name;
    private long                  totalSize;
    private ByteArrayOutputStream buffer;

    // -------------------------------------------------------------------------

    public FileAssembler(BiConsumer<String, Long>    onBegin,
                         BiConsumer<Long, Long>       onProgress,
                         BiConsumer<String, byte[]>  onComplete) {
        this.onBegin    = onBegin;
        this.onProgress = onProgress;
        this.onComplete = onComplete;
    }

    // -------------------------------------------------------------------------

    /**
     * Обработать входящий кадр. Игнорирует кадры неподходящего типа.
     * Вызывается из SlidingWindowReceiver (после упорядочивания).
     */
    public void onFrame(FrameCodec.Frame frame) {
        switch (frame.type) {
            case FrameCodec.TYPE_FILE_BEGIN -> handleBegin(frame);
            case FrameCodec.TYPE_FILE_DATA  -> handleData(frame);
            case FrameCodec.TYPE_FILE_END   -> handleEnd(frame);
            // остальные типы — не наше дело
        }
    }

    /** Сбросить состояние (например при разрыве соединения). */
    public void reset() {
        name      = null;
        totalSize = 0;
        buffer    = null;
    }

    // -------------------------------------------------------------------------

    private void handleBegin(FrameCodec.Frame frame) {
        // payload: [nameLen:1B][name: nameLen байт][size: 4B LE]
        byte[] p = frame.payload;
        if (p.length < 5) return; // слишком короткий — игнорируем

        int nameLen = p[0] & 0xFF;
        if (1 + nameLen + 4 > p.length) return;

        name      = new String(p, 1, nameLen, StandardCharsets.UTF_8);
        totalSize = (p[1 + nameLen]     & 0xFFL)
                  | ((p[2 + nameLen]    & 0xFFL) << 8)
                  | ((p[3 + nameLen]    & 0xFFL) << 16)
                  | ((p[4 + nameLen]    & 0xFFL) << 24);
        buffer    = new ByteArrayOutputStream((int) Math.min(totalSize, 64 * 1024));

        if (onBegin != null) onBegin.accept(name, totalSize);
    }

    private void handleData(FrameCodec.Frame frame) {
        if (buffer == null) return; // BEGIN ещё не пришёл — пропускаем
        buffer.write(frame.payload, 0, frame.payload.length);
        if (onProgress != null) onProgress.accept((long) buffer.size(), totalSize);
    }

    private void handleEnd(FrameCodec.Frame frame) {
        if (buffer == null || name == null) return;
        byte[] data = buffer.toByteArray();
        String completedName = name;
        reset();
        if (onComplete != null) onComplete.accept(completedName, data);
    }
}
