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
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test void singleMessage_AtoB() throws Exception { doSingleMessage(senderA, deliveredToB, "A→B"); }
    @Test void singleMessage_BtoA() throws Exception { doSingleMessage(senderB, deliveredToA, "B→A"); }

    /** Двустороннее: A→B и B→A одновременно. */
    @Test
    void bidirectional_10messages() throws Exception {
        for (int i = 0; i < 10; i++) sendText(senderA, "A→B #" + i);
        for (int i = 0; i < 10; i++) sendText(senderB, "B→A #" + i);

        waitFor(() -> deliveredToB.size() >= 10 && deliveredToA.size() >= 10, 10_000);
        assertEquals(10, deliveredToB.size());
        assertEquals(10, deliveredToA.size());
    }

    @Test void largeText_AtoB() throws Exception { doLargeText(senderA, deliveredToB, "A→B"); }
    @Test void largeText_BtoA() throws Exception { doLargeText(senderB, deliveredToA, "B→A"); }

    @Test void fileTransfer_4KB_AtoB() throws Exception { doFileTransfer4KB(senderA, chanB, ackB, "A→B"); }
    @Test void fileTransfer_4KB_BtoA() throws Exception { doFileTransfer4KB(senderB, chanA, ackA, "B→A"); }

    @Test void fileTransfer_10KB_AtoB() throws Exception { doFileTransfer10KB(senderA, chanB, ackB, "A→B"); }
    @Test void fileTransfer_10KB_BtoA() throws Exception { doFileTransfer10KB(senderB, chanA, ackA, "B→A"); }

    @Test void fileTransfer_100KB_AtoB() throws Exception { doFileTransfer100KB(senderA, chanB, ackB, "A→B"); }
    @Test void fileTransfer_100KB_BtoA() throws Exception { doFileTransfer100KB(senderB, chanA, ackA, "B→A"); }

    // =========================================================================
    // Хелперы тестов
    // =========================================================================

    private void doSingleMessage(SlidingWindowSender sender, List<String> delivered, String label) throws Exception {
        String msg = "Привет " + label;
        sendText(sender, msg);
        waitFor(() -> !delivered.isEmpty(), 5_000);
        assertEquals(1, delivered.size());
        assertEquals(msg, delivered.get(0));
    }

    private void doLargeText(SlidingWindowSender sender, List<String> delivered, String label) throws Exception {
        String msg = "Кирилл".repeat(100); // ~600 байт UTF-8 → несколько кадров
        sendText(sender, msg);
        waitFor(() -> !delivered.isEmpty(), 10_000);
        assertEquals(1, delivered.size());
        assertEquals(msg, delivered.get(0));
    }

    /** Передача 4 KB бинарного файла, проверка байт-в-байт. */
    private void doFileTransfer4KB(SlidingWindowSender sender, SerialChannel chanRecv,
                                   AckProcessor ackRecv, String label) throws Exception {
        byte[] fileData = new byte[4096];
        for (int i = 0; i < fileData.length; i++) fileData[i] = (byte)(i * 13 + 7);

        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler fa = new FileAssembler((n,s)->{}, (r,t)->{}, (n,d)->received.set(d));
        ackRecv.setDataHandler(new SlidingWindowReceiver(WINDOW, chanRecv::send, fa::onFrame)::onFrame);

        Thread t = fileSendThread(sender, "test4k.bin", fileData, "4k-" + label);
        t.start();

        waitFor(() -> received.get() != null, 30_000);
        assertNotNull(received.get(),              "[" + label + "] Файл не получен");
        assertArrayEquals(fileData, received.get(),"[" + label + "] Данные повреждены");
    }

    /**
     * Передача 10 KB: проверка целостности + скорость + ретрансмиты.
     * Теор. макс. FSO 24000 бод (8N1) = 2400 байт/с.
     */
    private void doFileTransfer10KB(SlidingWindowSender sender, SerialChannel chanRecv,
                                    AckProcessor ackRecv, String label) throws Exception {
        byte[] fileData = new byte[10_240];
        for (int i = 0; i < fileData.length; i++) fileData[i] = (byte)(i * 131 + 17);

        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler fa = new FileAssembler((n,s)->{}, (r,t)->{}, (n,d)->received.set(d));
        ackRecv.setDataHandler(new SlidingWindowReceiver(WINDOW, chanRecv::send, fa::onFrame)::onFrame);

        long startMs = System.currentTimeMillis();
        Thread t = fileSendThread(sender, "test10k.bin", fileData, "10k-" + label);
        t.start();

        waitFor(() -> received.get() != null, 120_000);
        long elapsedMs = System.currentTimeMillis() - startMs;

        double bps         = fileData.length / (elapsedMs / 1000.0);
        int    retransmits = sender.getRetransmitCount();
        System.out.printf("%n[10KB %s] Время: %.2f с | Скорость: %.0f байт/с (%.1f%% от 2400)%n",
            label, elapsedMs / 1000.0, bps, bps / 2400.0 * 100);
        System.out.printf("[10KB %s] Ретрансмитов: %d%s%n",
            label, retransmits, retransmits == 0 ? " — канал чистый" : " — ВНИМАНИЕ!");

        assertNotNull(received.get(),               "[" + label + "] Файл не получен за 120 с");
        assertArrayEquals(fileData, received.get(), "[" + label + "] Данные повреждены");
        assertEquals(0, retransmits,                "[" + label + "] Ретрансмитов быть не должно");
    }

    /** Передача 100 KB: скорость и целостность. */
    private void doFileTransfer100KB(SlidingWindowSender sender, SerialChannel chanRecv,
                                     AckProcessor ackRecv, String label) throws Exception {
        byte[] fileData = new byte[100 * 1024];
        for (int i = 0; i < fileData.length; i++) fileData[i] = (byte)(i * 97 + 31);

        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler fa = new FileAssembler((n,s)->{}, (r,t)->{}, (n,d)->received.set(d));
        ackRecv.setDataHandler(new SlidingWindowReceiver(WINDOW, chanRecv::send, fa::onFrame)::onFrame);

        long startMs = System.currentTimeMillis();
        Thread t = fileSendThread(sender, "test100k.bin", fileData, "100k-" + label);
        t.start();

        waitFor(() -> received.get() != null, 600_000);
        long elapsedMs = System.currentTimeMillis() - startMs;

        double bps    = fileData.length / (elapsedMs / 1000.0);
        int    frames = 1 + (int) Math.ceil((double) fileData.length / FrameCodec.MAX_PAYLOAD) + 1;
        System.out.printf("%n[100KB %s] Время: %.2f с | Скорость: %.0f байт/с | Кадров теор: %d%n",
            label, elapsedMs / 1000.0, bps, frames);
        System.out.printf("[100KB %s] %.1f%% от теор. 2400 байт/с%n",
            label, bps / 2400.0 * 100);

        assertNotNull(received.get(),               "[" + label + "] Файл не получен за 10 мин");
        assertArrayEquals(fileData, received.get(), "[" + label + "] Данные повреждены");
    }

    /** Запускает отправку файла в отдельном потоке. */
    private Thread fileSendThread(SlidingWindowSender sender, String name, byte[] data, String threadLabel) {
        Thread t = new Thread(() -> {
            try {
                sender.trySend(FrameCodec.TYPE_FILE_BEGIN, encodeFileBegin(name, data.length), 30_000);
                int off = 0;
                while (off < data.length) {
                    int len = Math.min(FrameCodec.MAX_PAYLOAD, data.length - off);
                    sender.trySend(FrameCodec.TYPE_FILE_DATA,
                        Arrays.copyOfRange(data, off, off + len), 30_000);
                    off += len;
                }
                sender.trySend(FrameCodec.TYPE_FILE_END, new byte[0], 30_000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "file-send-" + threadLabel);
        t.setDaemon(true);
        return t;
    }

    // =========================================================================

    /**
     * Sweep-тест: перебирает payload от 20 до 240 байт и замеряет throughput
     * на реальном железе. Передаёт по 3 KB на каждый размер.
     *
     * Запуск:
     *   gradlew.bat test -Dserial.test.enabled=true ^
     *       --tests "fsoterminal.SerialLoopbackTest.frameSize_throughputSweep"
     *
     * Пример вывода:
     *   P,B   Кадров  Время,с  байт/с     % теор  Ретр.
     *   20    150     10.50    285        11.9%   0
     *   ...
     *   240   13       1.50   2000        83.3%   0
     *   Лучший: payload=240 → 2000 байт/с (83.3%)
     */
    @Test
    void frameSize_throughputSweep() throws Exception {
        int[] payloads   = {20, 40, 60, 80, 100, 120, 150, 180, 210, 240};
        int   totalBytes = 3_000;

        System.out.println("\n[Frame Sweep] Зависимость throughput от размера payload");
        System.out.printf("  %-5s  %-7s  %-8s  %-10s  %-7s  %-6s%n",
            "P,B", "Кадров", "Время,с", "байт/с", "% теор", "Ретр.");
        System.out.println("  " + "─".repeat(52));

        int    bestPs  = 0;
        double bestBps = 0;

        for (int ps : payloads) {
            long[] r = doSweep(ps, totalBytes);
            // r[0]=elapsedMs  r[1]=frameCount  r[2]=retransmits
            double bps = totalBytes / (r[0] / 1000.0);
            double pct = bps / 2400.0 * 100;
            System.out.printf("  %-5d  %-7d  %-8.2f  %-10.0f  %-7.1f  %-6d%s%n",
                ps, r[1], r[0] / 1000.0, bps, pct, r[2],
                r[2] > 0 ? " ⚠" : "");
            if (bps > bestBps) { bestBps = bps; bestPs = ps; }
            Thread.sleep(500); // пауза между замерами
        }

        System.out.println("  " + "─".repeat(52));
        System.out.printf("  Лучший: payload=%d байт → %.0f байт/с (%.1f%% от теор.)%n",
            bestPs, bestBps, bestBps / 2400.0 * 100);
        System.out.println("  Теор. макс: 2400 байт/с (FSO 24 кбод, 8N1)");
    }

    /**
     * Передаёт totalBytes кусками по payloadSize байт (TYPE_FILE_DATA) от A к B.
     *
     * @return long[]{elapsedMs, frameCount, retransmits}
     */
    private long[] doSweep(int payloadSize, int totalBytes) throws Exception {
        // Свежие объекты протокола для каждой итерации
        SlidingWindowSender sA = new SlidingWindowSender(WINDOW, chanA::send);

        // Dummy-sender для ackB2: ACK от A к B не приходят (A только шлёт данные),
        // но null вызвал бы NPE в handleAck при случайных остатках → безопаснее no-op.
        SlidingWindowSender dummyB = new SlidingWindowSender(1, b -> {});
        AtomicInteger receivedBytesB = new AtomicInteger();

        SlidingWindowReceiver rB = new SlidingWindowReceiver(WINDOW, chanB::send,
            frame -> {
                if (frame.type == FrameCodec.TYPE_FILE_DATA)
                    receivedBytesB.addAndGet(frame.payload.length);
            });

        AckProcessor ackA2 = new AckProcessor(sA);
        AckProcessor ackB2 = new AckProcessor(dummyB);
        ackA2.setDataHandler(f -> {});   // A в этом тесте данные не принимает
        ackB2.setDataHandler(rB::onFrame);

        chanA.setReceiveHandler(ackA2::feed);
        chanB.setReceiveHandler(ackB2::feed);

        // Тестовые данные
        byte[] data = new byte[totalBytes];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 71 + 13);

        ScheduledExecutorService timer = startRetransmitTimer(sA);
        long startMs = System.currentTimeMillis();

        int frames = 0, off = 0;
        while (off < data.length) {
            int len = Math.min(payloadSize, data.length - off);
            sA.trySend(FrameCodec.TYPE_FILE_DATA,
                Arrays.copyOfRange(data, off, off + len), 30_000);
            off += len;
            frames++;
        }

        // Ждём: B принял все байты И последний ACK вернулся к A
        waitFor(() -> receivedBytesB.get() >= totalBytes && sA.inFlight() == 0, 120_000);
        long elapsed = System.currentTimeMillis() - startMs;

        timer.shutdownNow();

        // Восстанавливаем обработчики, созданные в setUp
        chanA.setReceiveHandler(ackA::feed);
        chanB.setReceiveHandler(ackB::feed);

        return new long[]{ elapsed, frames, sA.getRetransmitCount() };
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
