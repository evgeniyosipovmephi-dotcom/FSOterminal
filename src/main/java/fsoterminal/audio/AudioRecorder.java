package fsoterminal.audio;

import javax.sound.sampled.*;
import java.io.*;

/**
 * Запись голосового сообщения с микрофона.
 *
 * Формат: 8000 Гц, 8-бит, моно, PCM signed little-endian.
 * Такой формат даёт 8000 байт/сек, что приемлемо для FSO-канала (~3000 байт/сек):
 * 10-секундное сообщение = 80 KB → ~27 с передачи.
 *
 * Использование:
 *   recorder.start();
 *   ... пользователь говорит ...
 *   byte[] wav = recorder.stop();  // возвращает готовый WAV
 */
public class AudioRecorder {

    /** 8 кГц, 8-бит, моно */
    public static final AudioFormat FORMAT =
        new AudioFormat(8000f, 8, 1, true, false);

    /** Байт на секунду при нашем формате. */
    public static final int BYTES_PER_SEC = 8000;

    private TargetDataLine        line;
    private ByteArrayOutputStream pcmBuf;
    private Thread                captureThread;
    private volatile boolean      recording = false;
    private long                  startMs;

    // -------------------------------------------------------------------------

    /**
     * Проверяет, доступен ли микрофон в системе.
     */
    public static boolean isMicAvailable() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            return AudioSystem.isLineSupported(info);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Начать запись. Блокирует до открытия устройства.
     *
     * @throws LineUnavailableException если микрофон недоступен
     */
    public void start() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(FORMAT, 8000); // 1-секундный буфер
        line.start();

        pcmBuf    = new ByteArrayOutputStream(64 * 1024);
        recording = true;
        startMs   = System.currentTimeMillis();

        captureThread = new Thread(() -> {
            byte[] buf = new byte[1024];
            while (recording) {
                int n = line.read(buf, 0, buf.length);
                if (n > 0) pcmBuf.write(buf, 0, n);
            }
        }, "audio-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /**
     * Остановить запись и вернуть WAV-файл.
     *
     * @return байты WAV-файла, готовые для сохранения/отправки
     */
    public byte[] stop() throws InterruptedException, IOException {
        recording = false;
        if (line != null) { line.stop(); line.drain(); line.close(); }
        if (captureThread != null) captureThread.join(1000);

        byte[] pcm = pcmBuf.toByteArray();
        return pcmToWav(pcm);
    }

    /** Сколько секунд идёт запись. */
    public double elapsedSeconds() {
        return recording ? (System.currentTimeMillis() - startMs) / 1000.0 : 0;
    }

    public boolean isRecording() { return recording; }

    // -------------------------------------------------------------------------

    /** Оборачивает сырой PCM в WAV-контейнер. */
    private static byte[] pcmToWav(byte[] pcm) throws IOException {
        AudioInputStream ais = new AudioInputStream(
            new ByteArrayInputStream(pcm), FORMAT, pcm.length);
        ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.length + 44);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav);
        return wav.toByteArray();
    }

    /**
     * Вычисляет длительность голосового сообщения по размеру WAV-файла.
     * Предполагает наш формат (8 кГц, 8-бит, моно).
     */
    public static double durationSeconds(long wavFileSize) {
        long pcmBytes = Math.max(0, wavFileSize - 44); // 44 = размер WAV-заголовка
        return pcmBytes / (double) BYTES_PER_SEC;
    }

    /** Форматирует секунды как "M:SS". */
    public static String formatDuration(double secs) {
        int total = (int) Math.round(secs);
        return total / 60 + ":" + String.format("%02d", total % 60);
    }
}
