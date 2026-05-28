package fsoterminal;

import fsoterminal.channel.SerialChannel;
import fsoterminal.protocol.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест с реальными COM-портами.
 *
 * Требует два USB-UART адаптера, соединённых TX-RX накрест:
 *   COM10 TX ─► COM11 RX
 *   COM10 RX ◄─ COM11 TX
 *
 * Запуск:
 *   gradlew.bat test -Dserial.test.enabled=true
 *
 * По умолчанию тест пропускается (не мешает обычной сборке).
 */
@EnabledIfSystemProperty(named = "serial.test.enabled", matches = "true")
class SerialLoopbackTest {

    private static final String PORT_A  = System.getProperty("serial.port.a", "COM10");
    private static final String PORT_B  = System.getProperty("serial.port.b", "COM11");
    private static final int    BAUD    = 115200;
    // Window=1: единственный безопасный вариант для FSO 24 кбод.
    // BDP = 2400 байт/с × RTT(108 мс) = 259 байт ≈ 1 кадр → оптимум = WINDOW=1.
    // При WINDOW=2 буфер STM32 (512 байт) переполняется: когда ACK за первый кадр
    // приходит в 108 мс, в буфере ещё ~250 байт остатка; отправка следующих двух
    // кадров (510 байт) даёт 760 байт > 512 → дропы → ретрансмиты → скорость 23%.
    // WINDOW=1: кадр уходит за 106 мс, ACK в 108 мс, буфер пуст → 0 ретрансмитов,
    // throughput 250/108мс = 2315 байт/с (96.5% от 2400).
    private static final int    WINDOW  = 1;
    private static final int    RT_MS   = 400; // таймаут ретрансмита (мс)

    private SerialChannel chanA, chanB;
    private SlidingWindowSender   senderA,   senderB;
    private SlidingWindowReceiver receiverA, receiverB;
    private AckProcessor          ackA,      ackB;
    private TextAssembler         assemblerA, assemblerB;
    private ScheduledExecutorService timerA, timerB;

    @BeforeEach
    void setUp() {
        // --- Сторона A ---
        List<String> deliveredToA = new ArrayList<>();
        assemblerA  = new TextAssembler(text -> deliveredToA.add(text));
        chanA       = new SerialChannel();
        senderA     = new SlidingWindowSender(WINDOW, chanA::send);
        ackA        = new AckProcessor(senderA);
        receiverA   = new SlidingWindowReceiver(WINDOW, chanA::send,
                         frame -> { if (frame.type == FrameCodec.TYPE_DATA) assemblerA.onFrame(frame); });
        ackA.setDataHandler(receiverA::onFrame);
        ackA.setProbeHandler(() -> chanA.send(FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0])));
        chanA.setReceiveHandler(ackA::feed);

        // --- Сторона B ---
        List<String> deliveredToB = new ArrayList<>();
        assemblerB  = new TextAssembler(text -> deliveredToB.add(text));
        chanB       = new SerialChannel();
        senderB     = new SlidingWindowSender(WINDOW, chanB::send);
        ackB        = new AckProcessor(senderB);
        receiverB   = new SlidingWindowReceiver(WINDOW, chanB::send,
                         frame -> { if (frame.type == FrameCodec.TYPE_DATA) assemblerB.onFrame(frame); });
        ackB.setDataHandler(receiverB::onFrame);
        ackB.setProbeHandler(() -> chanB.send(FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0])));
        chanB.setReceiveHandler(ackB::feed);

        // Открываем порты
        assertTrue(chanA.open(PORT_A, BAUD), "Не удалось открыть " + PORT_A);
        assertTrue(chanB.open(PORT_B, BAUD), "Не удалось открыть " + PORT_B);

        // Ретрансмит-таймеры
        timerA = startRetransmitTimer(senderA);
        timerB = startRetransmitTimer(senderB);

        // Сохраняем списки для проверок
        this.deliveredToA = deliveredToA;
        this.deliveredToB = deliveredToB;
    }

    private List<String> deliveredToA;
    private List<String> deliveredToB;

    @AfterEach
    void tearDown() {
        if (timerA != null) timerA.shutdownNow();
        if (timerB != null) timerB.shutdownNow();
        if (chanA  != null) chanA.close();
        if (chanB  != null) chanB.close();
    }

    // =========================================================================

    /** A отправляет короткое сообщение → B принимает. */
    @Test
    void singleMessage_AtoB() throws Exception {
        String msg = "Привет с COM" + PORT_A.replace("COM","");
        sendText(senderA, msg);

        waitFor(() -> !deliveredToB.isEmpty(), 3000);
        assertEquals(1, deliveredToB.size());
        assertEquals(msg, deliveredToB.get(0));
    }

    /** Двустороннее: A→B и B→A одновременно. */
    @Test
    void bidirectional_10messages() throws Exception {
        for (int i = 0; i < 10; i++) sendText(senderA, "A→B #" + i);
        for (int i = 0; i < 10; i++) sendText(senderB, "B→A #" + i);

        waitFor(() -> deliveredToB.size() >= 10 && deliveredToA.size() >= 10, 10_000);
        assertEquals(10, deliveredToB.size());
        assertEquals(10, deliveredToA.size());
    }

    /** Большое сообщение (несколько кадров). */
    @Test
    void largeText_multiFrame() throws Exception {
        String msg = "Кирилл".repeat(100); // ~600 байт UTF-8 → 3 кадра
        sendText(senderA, msg);

        waitFor(() -> !deliveredToB.isEmpty(), 5000);
        assertEquals(1, deliveredToB.size());
        assertEquals(msg, deliveredToB.get(0));
    }

    /** Передача бинарного файла 4 KB: A→B. */
    @Test
    void fileTransfer_4KB_AtoB() throws Exception {
        byte[] fileData = new byte[4096];
        for (int i = 0; i < fileData.length; i++) fileData[i] = (byte)(i * 13 + 7);
        String fileName = "test4k.bin";

        // FileAssembler на стороне B
        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler faB = new FileAssembler((n,s)->{}, (r,t)->{}, (n,d)->received.set(d));
        // Переподключаем диспетчер B (для этого теста)
        receiverB = new SlidingWindowReceiver(WINDOW, chanB::send, faB::onFrame);
        ackB.setDataHandler(receiverB::onFrame);

        // Отправка файла
        Thread sender = new Thread(() -> {
            try {
                senderA.trySend(FrameCodec.TYPE_FILE_BEGIN, encodeFileBegin(fileName, fileData.length), 10_000);
                int off = 0;
                while (off < fileData.length) {
                    int len = Math.min(FrameCodec.MAX_PAYLOAD, fileData.length - off);
                    senderA.trySend(FrameCodec.TYPE_FILE_DATA, Arrays.copyOfRange(fileData, off, off + len), 10_000);
                    off += len;
                }
                senderA.trySend(FrameCodec.TYPE_FILE_END, new byte[0], 10_000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "file-send");
        sender.setDaemon(true);
        sender.start();

        waitFor(() -> received.get() != null, 30_000);
        assertNotNull(received.get(), "Файл не получен");
        assertArrayEquals(fileData, received.get(), "Данные файла повреждены");
    }

    /**
     * Передача файла 10 KB по реальному COM-порту.
     * Проверяет байт-в-байт целостность, замеряет скорость и считает ретрансмиты.
     * Теоретический максимум FSO 24000 бод (8N1): 24000/10 = 2400 байт/с.
     * На проводном канале (нет потерь) ретрансмитов должно быть 0.
     */
    @Test
    void fileTransfer_10KB_AtoB() throws Exception {
        byte[] fileData = new byte[10_240];
        for (int i = 0; i < fileData.length; i++) fileData[i] = (byte)(i * 131 + 17);
        String fileName = "test10k.bin";

        // FileAssembler на стороне B
        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler faB = new FileAssembler((n,s)->{}, (r,t)->{}, (n,d)->received.set(d));
        receiverB = new SlidingWindowReceiver(WINDOW, chanB::send, faB::onFrame);
        ackB.setDataHandler(receiverB::onFrame);

        long startMs = System.currentTimeMillis();

        // Отправка файла в отдельном потоке (trySend блокирует на семафоре)
        Thread sender = new Thread(() -> {
            try {
                senderA.trySend(FrameCodec.TYPE_FILE_BEGIN,
                    encodeFileBegin(fileName, fileData.length), 10_000);
                int off = 0;
                while (off < fileData.length) {
                    int len = Math.min(FrameCodec.MAX_PAYLOAD, fileData.length - off);
                    senderA.trySend(FrameCodec.TYPE_FILE_DATA,
                        Arrays.copyOfRange(fileData, off, off + len), 10_000);
                    off += len;
                }
                senderA.trySend(FrameCodec.TYPE_FILE_END, new byte[0], 10_000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "file-send-10k");
        sender.setDaemon(true);
        sender.start();

        // FSO 24 кбод: теор. ~4.3 с, допускаем до 120 с
        waitFor(() -> received.get() != null, 120_000);
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Теоретический максимум: 24000 бод / 10 бит на байт (8N1) = 2400 байт/с
        double theoreticalBps = 2400.0;
        double bps = fileData.length / (elapsedMs / 1000.0);
        int retransmits = senderA.getRetransmitCount();
        System.out.printf("%n[10KB serial] Время: %.2f с | Скорость: %.0f байт/с (%.1f%% от %.0f)%n",
            elapsedMs / 1000.0, bps, bps / theoreticalBps * 100, theoreticalBps);
        System.out.printf("[10KB serial] Ретрансмитов: %d%s%n",
            retransmits, retransmits == 0 ? " — канал чистый" : " — ВНИМАНИЕ: потери на проводном канале!");

        assertNotNull(received.get(), "Файл не получен за 120 секунд");
        assertArrayEquals(fileData, received.get(), "Данные файла повреждены");
        assertEquals(0, retransmits, "На проводном канале ретрансмитов быть не должно");
    }

    /**
     * Передача файла 100 KB по реальному COM-порту.
     * Теоретический минимум при 115200: ~9 с; при FSO 24 кбит/с: ~35 с.
     * Допускаем до 600 с (10 мин) чтобы тест не падал на медленном канале.
     */
    @Test
    void fileTransfer_100KB_AtoB() throws Exception {
        byte[] fileData = new byte[100 * 1024]; // 102400 байт
        for (int i = 0; i < fileData.length; i++) fileData[i] = (byte)(i * 97 + 31);
        String fileName = "test100k.bin";

        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler faB = new FileAssembler((n,s)->{}, (r,t)->{}, (n,d)->received.set(d));
        receiverB = new SlidingWindowReceiver(WINDOW, chanB::send, faB::onFrame);
        ackB.setDataHandler(receiverB::onFrame);

        long startMs = System.currentTimeMillis();

        Thread sender = new Thread(() -> {
            try {
                senderA.trySend(FrameCodec.TYPE_FILE_BEGIN,
                    encodeFileBegin(fileName, fileData.length), 30_000);
                int off = 0;
                while (off < fileData.length) {
                    int len = Math.min(FrameCodec.MAX_PAYLOAD, fileData.length - off);
                    senderA.trySend(FrameCodec.TYPE_FILE_DATA,
                        Arrays.copyOfRange(fileData, off, off + len), 30_000);
                    off += len;
                }
                senderA.trySend(FrameCodec.TYPE_FILE_END, new byte[0], 30_000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "file-send-100k");
        sender.setDaemon(true);
        sender.start();

        waitFor(() -> received.get() != null, 600_000); // до 10 мин
        long elapsedMs = System.currentTimeMillis() - startMs;

        double bps    = fileData.length / (elapsedMs / 1000.0);
        int    frames = 1 + (int) Math.ceil((double) fileData.length / FrameCodec.MAX_PAYLOAD) + 1;
        System.out.printf("%n[100KB serial] Время: %.2f с | Скорость: %.0f байт/с | Фреймов теор: %d%n",
            elapsedMs / 1000.0, bps, frames);
        System.out.printf("[100KB serial] Эффективность: %.1f%% (от 115200 бод ≈ 11520 байт/с)%n",
            bps / 11520.0 * 100);

        assertNotNull(received.get(), "Файл не получен за 10 минут");
        assertArrayEquals(fileData, received.get(), "Данные файла повреждены");
    }

    // =========================================================================
    // Вспомогательные
    // =========================================================================

    private void sendText(SlidingWindowSender s, String text) {
        byte[][] payloads = TextAssembler.encodePayloads(text);
        Thread t = new Thread(() -> {
            try {
                for (byte[] p : payloads) s.trySend(FrameCodec.TYPE_DATA, p, 5000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "send-thread");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] encodeFileBegin(String name, long totalBytes) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        int nameLen = Math.min(nb.length, 245);
        byte[] p = new byte[1 + nameLen + 4];
        p[0] = (byte) nameLen;
        System.arraycopy(nb, 0, p, 1, nameLen);
        int sz = (int) Math.min(totalBytes, 0xFFFFFFFFL);
        p[1+nameLen]=(byte)sz; p[2+nameLen]=(byte)(sz>>8);
        p[3+nameLen]=(byte)(sz>>16); p[4+nameLen]=(byte)(sz>>24);
        return p;
    }

    private static ScheduledExecutorService startRetransmitTimer(SlidingWindowSender s) {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retransmit");
            t.setDaemon(true); return t;
        });
        // Ретрансмит только если окно не продвигалось RT_MS мс (потерян хвостовой кадр).
        // Промежуточные потери обрабатывает gap-based Selective Repeat в onAck().
        ex.scheduleAtFixedRate(() -> {
            if (s.inFlight() == 0) return;
            long silentMs = System.currentTimeMillis() - s.getLastAckAdvanceMs();
            if (silentMs >= RT_MS) s.retransmitUnconfirmed();
        }, RT_MS, RT_MS, TimeUnit.MILLISECONDS);
        return ex;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline)
            Thread.sleep(50);
    }
}
