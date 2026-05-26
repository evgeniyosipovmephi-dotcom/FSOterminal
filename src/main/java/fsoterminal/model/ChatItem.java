package fsoterminal.model;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Одно сообщение (или системное уведомление) в чате.
 *
 * Kind.TEXT  — текстовое сообщение
 * Kind.FILE  — передача файла (прогресс обновляется через fileProgressProperty())
 */
public class ChatItem {

    public enum Kind      { TEXT, FILE }
    public enum Direction { SENT, RECEIVED, SYSTEM }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public final Kind      kind;
    public final Direction direction;

    /** Текст сообщения (для TEXT) или описание-ошибка (для FILE-ошибок). */
    public final String    text;
    public final String    time;

    // --- FILE-specific -------------------------------------------------------

    /** Имя файла (null для TEXT/SYSTEM). */
    public final String fileName;

    /** Размер файла в байтах (0 для TEXT/SYSTEM). */
    public final long   fileSize;

    /**
     * Прогресс 0.0 … 1.0. Обновляется только из FX-потока.
     * null для TEXT/SYSTEM.
     */
    private final DoubleProperty fileProgress;

    // -------------------------------------------------------------------------

    private ChatItem(Kind kind, Direction direction, String text,
                     String fileName, long fileSize) {
        this.kind         = kind;
        this.direction    = direction;
        this.text         = text;
        this.time         = LocalTime.now().format(TIME_FMT);
        this.fileName     = fileName;
        this.fileSize     = fileSize;
        this.fileProgress = (kind == Kind.FILE)
                ? new SimpleDoubleProperty(0.0)
                : null;
    }

    // --- TEXT factories ------------------------------------------------------

    public static ChatItem sent(String text) {
        return new ChatItem(Kind.TEXT, Direction.SENT, text, null, 0);
    }

    public static ChatItem received(String text) {
        return new ChatItem(Kind.TEXT, Direction.RECEIVED, text, null, 0);
    }

    public static ChatItem system(String text) {
        return new ChatItem(Kind.TEXT, Direction.SYSTEM, text, null, 0);
    }

    // --- FILE factories ------------------------------------------------------

    public static ChatItem fileSent(String fileName, long fileSize) {
        return new ChatItem(Kind.FILE, Direction.SENT, null, fileName, fileSize);
    }

    public static ChatItem fileReceived(String fileName, long fileSize) {
        return new ChatItem(Kind.FILE, Direction.RECEIVED, null, fileName, fileSize);
    }

    // --- FILE progress -------------------------------------------------------

    /** Свойство прогресса (0..1). Только для Kind.FILE. */
    public DoubleProperty fileProgressProperty() {
        return fileProgress;
    }

    /**
     * Обновить прогресс (0..1). Вызывать только из FX-потока.
     * Для TEXT/SYSTEM — нет эффекта.
     */
    public void setFileProgress(double v) {
        if (fileProgress != null) fileProgress.set(v);
    }

    // --- Вспомогательный метод форматирования размера ------------------------

    public static String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " Б";
        if (bytes < 1024 * 1024)
            return String.format("%.1f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format("%.1f МБ", bytes / (1024.0 * 1024));
        return String.format("%.1f ГБ", bytes / (1024.0 * 1024 * 1024));
    }
}
