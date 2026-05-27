package fsoterminal.model;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Одна запись в ленте чата.
 *
 * Kind.TEXT  — текстовое сообщение (для многокадровых — progress != null)
 * Kind.FILE  — файл (прогресс-бар, после сохранения savedPath != null)
 * Kind.IMAGE — изображение (то же, что FILE + превью по savedPath)
 */
public class ChatItem {

    public enum Kind      { TEXT, FILE, IMAGE, VOICE }
    public enum Direction { SENT, RECEIVED, SYSTEM }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Set<String> IMAGE_EXTS =
        Set.of("jpg","jpeg","png","gif","bmp","webp","tiff","tif");
    private static final Set<String> VOICE_EXTS = Set.of("wav");

    public final Kind      kind;
    public final Direction direction;
    public final String    text;      // null для FILE/IMAGE
    public final String    time;

    // FILE / IMAGE
    public final String fileName;    // null для TEXT/SYSTEM
    public final long   fileSize;

    /**
     * Прогресс 0..1. Ненулевой для:
     * - многокадровых исходящих текстовых сообщений
     * - любых файлов
     * null → не отображать полосу прогресса
     */
    private final SimpleDoubleProperty progress;

    /**
     * Путь к сохранённому файлу (для IMAGE — чтобы показать превью).
     * Устанавливается после сохранения на диск.
     * Observable → ChatCell слушает изменение.
     */
    private final SimpleStringProperty savedPath;

    /**
     * Действие отмены отправки. Устанавливается sendFile() перед стартом потока,
     * обнуляется после завершения. null — отмена недоступна.
     */
    private volatile Runnable cancelAction;

    // -------------------------------------------------------------------------

    private ChatItem(Kind kind, Direction direction, String text,
                     String fileName, long fileSize, boolean withProgress) {
        this.kind      = kind;
        this.direction = direction;
        this.text      = text;
        this.time      = LocalTime.now().format(TIME_FMT);
        this.fileName  = fileName;
        this.fileSize  = fileSize;
        this.progress  = withProgress ? new SimpleDoubleProperty(0.0) : null;
        this.savedPath = new SimpleStringProperty(null);
    }

    // --- TEXT ----------------------------------------------------------------

    public static ChatItem sent(String text) {
        return new ChatItem(Kind.TEXT, Direction.SENT, text, null, 0, false);
    }

    public static ChatItem received(String text) {
        return new ChatItem(Kind.TEXT, Direction.RECEIVED, text, null, 0, false);
    }

    public static ChatItem system(String text) {
        return new ChatItem(Kind.TEXT, Direction.SYSTEM, text, null, 0, false);
    }

    /** Исходящее текстовое сообщение, которое разбивается на несколько кадров — показываем прогресс. */
    public static ChatItem sentMultiFrame(String text) {
        return new ChatItem(Kind.TEXT, Direction.SENT, text, null, 0, true);
    }

    // --- FILE / IMAGE --------------------------------------------------------

    public static ChatItem fileSent(String name, long size) {
        return new ChatItem(Kind.FILE, Direction.SENT, null, name, size, true);
    }

    public static ChatItem fileReceived(String name, long size) {
        Kind kind = isImageName(name) ? Kind.IMAGE
                  : isVoiceName(name) ? Kind.VOICE
                  : Kind.FILE;
        return new ChatItem(kind, Direction.RECEIVED, null, name, size, true);
    }

    /** Исходящее голосовое сообщение (уже записанное, идёт отправка). */
    public static ChatItem voiceSent(String name, long size) {
        return new ChatItem(Kind.VOICE, Direction.SENT, null, name, size, true);
    }

    /** Исходящее изображение (идёт отправка; savedPath устанавливается немедленно для превью). */
    public static ChatItem imageSent(String name, long size) {
        return new ChatItem(Kind.IMAGE, Direction.SENT, null, name, size, true);
    }

    // -------------------------------------------------------------------------

    public SimpleDoubleProperty progressProperty() { return progress; }

    public void setProgress(double v) {
        if (progress != null) progress.set(v);
    }

    public double getProgress() {
        return progress != null ? progress.get() : 1.0;
    }

    public StringProperty savedPathProperty() { return savedPath; }

    public void setSavedPath(String path) { savedPath.set(path); }

    public String getSavedPath() { return savedPath.get(); }

    // -------------------------------------------------------------------------

    public void setCancelAction(Runnable r) { cancelAction = r; }
    public Runnable getCancelAction()       { return cancelAction; }

    public static boolean isImageName(String name) {
        return hasExtension(name, IMAGE_EXTS);
    }

    public static boolean isVoiceName(String name) {
        return hasExtension(name, VOICE_EXTS);
    }

    private static boolean hasExtension(String name, Set<String> exts) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && exts.contains(name.substring(dot + 1).toLowerCase());
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024)             return bytes + " Б";
        if (bytes < 1024 * 1024)      return String.format("%.1f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024*1024) return String.format("%.1f МБ", bytes / (1024.0*1024));
        return String.format("%.1f ГБ", bytes / (1024.0*1024*1024));
    }
}
