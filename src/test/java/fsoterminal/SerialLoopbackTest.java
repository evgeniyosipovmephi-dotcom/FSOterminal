package fsoterminal;

import fsoterminal.channel.SerialChannel;
import fsoterminal.protocol.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты с реальными COM-портами (FSO 24 кбод).
 *
 * Запуск:
 *   gradlew test -Dserial.test.enabled=true -Dserial.port.a=COM25 -Dserial.port.b=COM22
 *       --tests "fsoterminal.SerialLoopbackTest.<тест>" --rerun-tasks
 *
 * Топология:
 *   chanA (COM25) → USB → STM32 → FSO-оптика → STM32 → USB → chanB (COM22)
 *   chanB (COM22) → USB → STM32 → провод     → STM32 → USB → chanA (COM25)
 */
@EnabledIfSystemProperty(named = "serial.test.enabled", matches = "true")
class SerialLoopbackTest {

    // ── Порты ─────────────────────────────────────────────────────────────────
    private static final String PORT_A = System.getProperty("serial.port.a", "COM10");
    private static final String PORT_B = System.getProperty("serial.port.b", "COM11");
    private static final int    BAUD   = 115200;

    // ── WINDOW=1 протокол ─────────────────────────────────────────────────────
    private static final int WINDOW = 1;
    private static final int RT_MS  = 400; // таймаут ретрансмиссии

    // ── FSO-канал ─────────────────────────────────────────────────────────────
    private static final int FSO_BAUD = 24_000; // бод (8N1 → 2400 байт/с)

    // ── Bulk-протокол ─────────────────────────────────────────────────────────
    // Запас к FSO-времени кадра. FSO-кадр 255 Б = 106 мс на 24 кбод — это пол:
    // ниже него входной буфер STM32 копится → каскадная потеря (замер: 117 мс → 40%,
    // 120 мс → 24%). Безопасная зона начинается ~125 мс (обрыв ≈122 мс). Запас 30 мс
    // (→137 мс для 238 Б) — пик throughput ~1700 байт/с с зазором ~15 мс над обрывом.
    private static final int BULK_DELAY_MARGIN_MS = 30;
    // dataPerFrame ≤ 238: FSO-кадр = 17 + dataPerFrame ≤ 255 (лимит STM32)
    private static final int BULK_CLEAN = 238;
    // dataPerFrame ≤ 200 при 50% шуме: 222(наш) + 16(шум) = 238 ≤ 255
    private static final int BULK_NOISY = 200;

    // ── Переопределение параметров из командной строки ────────────────────────
    // -Dbulk.payload=128   — размер данных в bulk-кадре (байт)
    // -Dbulk.delay=95      — задержка между bulk-кадрами (мс), 0 = автоматически
    // -Dwindow.payload=180 — размер данных в WINDOW=1 кадре (байт)
    // -Dtest.bytes=10240   — общий объём данных (байт), переопределяет дефолт теста
    private static final int  CMD_BULK_PAYLOAD   = Integer.getInteger("bulk.payload",   -1);
    private static final long CMD_BULK_DELAY     = Long.getLong(      "bulk.delay",     -1);
    private static final int  CMD_BULK_BLOCK     = Integer.getInteger("bulk.block",     -1);
    private static final int  CMD_WINDOW_PAYLOAD = Integer.getInteger("window.payload", -1);
    private static final int  CMD_TEST_BYTES     = Integer.getInteger("test.bytes",     -1);
    private static final int TYPE_BULK_DATA = 0x31; // [idx_lo][idx_hi][данные...]
    private static final int TYPE_BULK_END  = 0x32; // [total_lo][total_hi]
    private static final int TYPE_BULK_NACK = 0x33; // [total 2B][bitmap: бит=0 → потерян]
    private static final int TYPE_BULK_DONE = 0x34;

    // ── Блочный bulk (тест размера блока, вопрос 2 дизайна) ─────────────────────
    // DATA: [blk 1][idx_lo][idx_hi][данные ≤56] → кадр 64 Б (1 USB-кусок)
    private static final int TYPE_BLK_DATA = 0x37; // [blk][idx_lo][idx_hi][данные]
    private static final int TYPE_BLK_END  = 0x38; // [blk][count_lo][count_hi]
    private static final int TYPE_BLK_NACK = 0x39; // [blk][count 2B][bitmap: бит=0 → потерян]
    private static final int TYPE_BLK_DONE = 0x3A; // [blk] — блок собран полностью

    // ── Noise-инжектор ────────────────────────────────────────────────────────
    // FSO-кадр шума: 26 байт = 11(payload) + 4(FrameCodec hdr) + 1(CRC) + 10(PtPP2)
    private static final int NOISE_FSO_BYTES = 26;
    private static final int NOISE_PAYLOAD   = NOISE_FSO_BYTES - 10 - 5; // = 11
    // 0x99 неизвестен получателю bulk → молча игнорируется.
    // ⚠ Нельзя с WINDOW=1: неизвестные типы нарушают SEQ в SlidingWindowReceiver.
    private static final int TYPE_NOISE = 0x99;

    // ── Состояние теста ───────────────────────────────────────────────────────
    private SerialChannel         chanA, chanB;
    private SlidingWindowSender   senderA, senderB;
    private SlidingWindowReceiver receiverA, receiverB;
    private AckProcessor          ackA, ackB;
    private TextAssembler         asmA, asmB;
    private ScheduledExecutorService timerA, timerB;
    private List<String>          deliveredToA, deliveredToB;

    @BeforeEach
    void setUp() {
        deliveredToA = new ArrayList<>();
        deliveredToB = new ArrayList<>();
        asmA = new TextAssembler(deliveredToA::add);
        asmB = new TextAssembler(deliveredToB::add);
        chanA = new SerialChannel();
        chanB = new SerialChannel();

        senderA   = new SlidingWindowSender(WINDOW, chanA::send);
        ackA      = new AckProcessor(senderA);
        receiverA = new SlidingWindowReceiver(WINDOW, chanA::send,
            fr -> { if (fr.type == FrameCodec.TYPE_DATA) asmA.onFrame(fr); });
        ackA.setDataHandler(receiverA::onFrame);
        ackA.setProbeHandler(() -> chanA.send(FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0])));
        chanA.setReceiveHandler(ackA::feed);

        senderB   = new SlidingWindowSender(WINDOW, chanB::send);
        ackB      = new AckProcessor(senderB);
        receiverB = new SlidingWindowReceiver(WINDOW, chanB::send,
            fr -> { if (fr.type == FrameCodec.TYPE_DATA) asmB.onFrame(fr); });
        ackB.setDataHandler(receiverB::onFrame);
        ackB.setProbeHandler(() -> chanB.send(FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0])));
        chanB.setReceiveHandler(ackB::feed);

        assertTrue(chanA.open(PORT_A, BAUD), "Не удалось открыть " + PORT_A);
        assertTrue(chanB.open(PORT_B, BAUD), "Не удалось открыть " + PORT_B);

        timerA = startRetransmitTimer(senderA);
        timerB = startRetransmitTimer(senderB);
    }

    @AfterEach
    void tearDown() {
        if (timerA != null) timerA.shutdownNow();
        if (timerB != null) timerB.shutdownNow();
        if (chanA  != null) chanA.close();
        if (chanB  != null) chanB.close();
    }

    // =========================================================================
    // Сообщения (текстовый WINDOW=1 протокол)
    // =========================================================================

    @Test void msg_AtoB() throws Exception {
        String txt = "Привет A→B";
        sendText(senderA, txt);
        waitFor(() -> !deliveredToB.isEmpty(), 5_000);
        assertEquals(List.of(txt), deliveredToB);
    }

    @Test void msg_BtoA() throws Exception {
        String txt = "Привет B→A";
        sendText(senderB, txt);
        waitFor(() -> !deliveredToA.isEmpty(), 5_000);
        assertEquals(List.of(txt), deliveredToA);
    }

    @Test void msg_bidirectional() throws Exception {
        for (int i = 0; i < 10; i++) sendText(senderA, "A→B #" + i);
        for (int i = 0; i < 10; i++) sendText(senderB, "B→A #" + i);
        waitFor(() -> deliveredToB.size() >= 10 && deliveredToA.size() >= 10, 15_000);
        assertEquals(10, deliveredToB.size(), "B не получил 10 сообщений");
        assertEquals(10, deliveredToA.size(), "A не получил 10 сообщений");
    }

    // =========================================================================
    // Передача данных (WINDOW=1)
    // дефолт 10 KB, переопределяется через -Dtest.bytes=N
    // =========================================================================

    @Test void window_AtoB() throws Exception { runWindow(windowPayload(), testBytes(10*1024), senderA, chanB, ackB, "A→B"); }
    @Test void window_BtoA() throws Exception { runWindow(windowPayload(), testBytes(10*1024), senderB, chanA, ackA, "B→A"); }

    // =========================================================================
    // Bulk-протокол: чистый канал
    // дефолт 100 KB, переопределяется через -Dtest.bytes=N
    // =========================================================================

    @Test void bulk_clean_AtoB() throws Exception { runBulk(bulkPayload(BULK_CLEAN), testBytes(100*1024), chanA, chanB, 0.0, "clean A→B"); }
    @Test void bulk_clean_BtoA() throws Exception { runBulk(bulkPayload(BULK_CLEAN), testBytes(100*1024), chanB, chanA, 0.0, "clean B→A"); }

    // =========================================================================
    // Блочный bulk: сравнение размера блока (вопрос 2 дизайна)
    //   -Dbulk.block=408   — NACK всегда 1 USB-кусок, но блоков много
    //   -Dbulk.block=1500  — NACK «толстый» (4 куска), но редкий; блоков мало
    //   -Dbulk.payload=56  -Dbulk.delay=20  -Dtest.bytes=524288
    // Запуск:
    //   gradlew test --tests "*bulk_blocks_AtoB*" --rerun-tasks -Dserial.test.enabled=true
    //     -Dserial.port.a=COM22 -Dserial.port.b=COM25 -Dbulk.block=408
    // =========================================================================

    @Test void bulk_blocks_AtoB() throws Exception {
        runBulkBlocks(bulkPayload(56), testBytes(512*1024), blockFrames(1500), chanA, chanB, 0.0, "blocks A→B");
    }
    @Test void bulk_blocks_BtoA() throws Exception {
        runBulkBlocks(bulkPayload(56), testBytes(512*1024), blockFrames(1500), chanB, chanA, 0.0, "blocks B→A");
    }

    // =========================================================================
    // Bulk-протокол: 50% шум
    // дефолт 50 KB, переопределяется через -Dtest.bytes=N
    // =========================================================================

    @Test void bulk_noisy50_AtoB() throws Exception {
        ScheduledExecutorService noise = startNoiseInjector(chanA, 0.50);
        try { runBulk(bulkPayload(BULK_NOISY), testBytes(50*1024), chanA, chanB, 0.50, "noisy50 A→B"); }
        finally { noise.shutdownNow(); }
    }

    @Test void bulk_noisy50_BtoA() throws Exception {
        ScheduledExecutorService noise = startNoiseInjector(chanB, 0.50);
        try { runBulk(bulkPayload(BULK_NOISY), testBytes(50*1024), chanB, chanA, 0.50, "noisy50 B→A"); }
        finally { noise.shutdownNow(); }
    }

    // =========================================================================
    // Sweep-тесты
    // =========================================================================

    @Test void sweep_window_3KB() throws Exception {
        runWindowSweep(new int[]{20, 40, 60, 80, 100, 120, 150, 180, 210, 240}, 3_000);
    }

    @Test void sweep_bulk_clean_10KB() throws Exception {
        runBulkSweep(new int[]{40, 80, 120, 160, 200, 238}, 10_000, 0.0);
    }

    @Test void sweep_bulk_noisy50_10KB() throws Exception {
        ScheduledExecutorService noise = startNoiseInjector(chanA, 0.50);
        try { runBulkSweep(new int[]{40, 80, 120, 160, 200, 220}, 10_000, 0.50); }
        finally { noise.shutdownNow(); }
    }

    // =========================================================================
    // Реализация: WINDOW=1 передача данных
    // =========================================================================

    /**
     * Передаёт totalBytes через WINDOW=1, кусками по payloadSz байт.
     * Проверяет целостность и отсутствие ретрансмитов.
     */
    private void runWindow(int payloadSz, int totalBytes,
                           SlidingWindowSender sender,
                           SerialChannel recvChan, AckProcessor recvAck,
                           String tag) throws Exception {
        byte[] txData = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++) txData[i] = (byte)(i * 71 + 13);

        // Приёмник: WINDOW=1 гарантирует порядок, собираем байты последовательно
        byte[] rxData   = new byte[totalBytes];
        AtomicInteger rxOff = new AtomicInteger(0);
        recvAck.setDataHandler(
            new SlidingWindowReceiver(WINDOW, recvChan::send, fr -> {
                if (fr.type == FrameCodec.TYPE_FILE_DATA) {
                    int off = rxOff.getAndAdd(fr.payload.length);
                    if (off + fr.payload.length <= totalBytes)
                        System.arraycopy(fr.payload, 0, rxData, off, fr.payload.length);
                }
            })::onFrame);

        long t0 = System.currentTimeMillis();
        for (int off = 0; off < totalBytes; off += payloadSz) {
            int len = Math.min(payloadSz, totalBytes - off);
            sender.trySend(FrameCodec.TYPE_FILE_DATA,
                Arrays.copyOfRange(txData, off, off + len), 30_000);
        }
        waitFor(() -> rxOff.get() >= totalBytes && sender.inFlight() == 0, 600_000);
        long elapsed = System.currentTimeMillis() - t0;

        double bps = totalBytes / (elapsed / 1000.0);
        int retransmits = sender.getRetransmitCount();
        String sep = "─".repeat(60);
        System.out.printf("%n%s%n  WINDOW=1 %s | %d Б/кадр | %d байт%n", sep, tag, payloadSz, totalBytes);
        System.out.printf("  Время: %.2f с | Скорость: %.0f байт/с (%.1f%% от 2400)%n",
            elapsed / 1000.0, bps, bps / 2400 * 100);
        System.out.printf("  Ретрансмитов: %d%s%n%s%n",
            retransmits, retransmits == 0 ? " — канал чистый" : " ⚠", sep);

        assertEquals(totalBytes, rxOff.get(),
            "[" + tag + "] Получено " + rxOff.get() + " из " + totalBytes + " байт");
        assertArrayEquals(txData, rxData, "[" + tag + "] Данные повреждены");
        assertEquals(0, retransmits,      "[" + tag + "] Ретрансмитов быть не должно");
    }

    // =========================================================================
    // Реализация: Bulk-протокол
    // =========================================================================

    /**
     * Передаёт totalBytes bulk-методом: все кадры → BULK_END → NACK-bitmap → досыл.
     *
     * Диагностика:
     *  - Индексы пропущенных кадров в каждом раунде (если ≤ 30)
     *  - Суммарно байт, поступивших в FrameCodec.Decoder на приёмнике
     *    (позволяет обнаружить потерю байт на уровне USB/jSerialComm:
     *     если rxBytes << ожидаемого — потеря до Decoder, не в оптике)
     */
    private void runBulk(int payloadSz, int totalBytes,
                         SerialChannel txChan, SerialChannel rxChan,
                         double noise, String tag) throws Exception {
        int totalFrames = (int) Math.ceil((double) totalBytes / payloadSz);
        int nackSz = 2 + (totalFrames + 7) / 8;
        assertTrue(nackSz <= FrameCodec.MAX_PAYLOAD,
            "NACK bitmap " + nackSz + " B > MAX_PAYLOAD — уменьшить totalBytes или увеличить payloadSz");

        byte[] txData = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++) txData[i] = (byte)(i * 71 + 13);

        byte[][] recvBuf  = new byte[totalFrames][];
        boolean[] gotIt   = new boolean[totalFrames];
        AtomicInteger dups    = new AtomicInteger(0);
        // Суммарно байт, пришедших в Decoder (включает PtPP2+DCmd overhead STM32).
        // Позволяет проверить: не теряет ли jSerialComm байты между callback-вызовами.
        AtomicInteger rxRawBytes = new AtomicInteger(0);

        // ── Приёмник ──────────────────────────────────────────────────────────
        FrameCodec.Decoder rxDec = new FrameCodec.Decoder();
        rxChan.setReceiveHandler(raw -> {
            rxRawBytes.addAndGet(raw.length);
            rxDec.feed(raw);
            FrameCodec.Frame fr;
            while ((fr = rxDec.poll()) != null) {
                if (fr.type == TYPE_BULK_DATA && fr.payload.length >= 2) {
                    int idx = (fr.payload[0] & 0xFF) | ((fr.payload[1] & 0xFF) << 8);
                    if (idx < totalFrames) {
                        if (!gotIt[idx]) {
                            gotIt[idx] = true;
                            recvBuf[idx] = Arrays.copyOfRange(fr.payload, 2, fr.payload.length);
                        } else {
                            dups.incrementAndGet();
                        }
                    }
                } else if (fr.type == TYPE_BULK_END && fr.payload.length >= 2) {
                    try {
                        int total = (fr.payload[0] & 0xFF) | ((fr.payload[1] & 0xFF) << 8);
                        int bmLen = (total + 7) / 8;
                        byte[] bm = new byte[bmLen];
                        int missing = 0;
                        for (int i = 0; i < total; i++) {
                            if (gotIt[i]) bm[i / 8] |= (byte)(1 << (i % 8));
                            else missing++;
                        }
                        int rtype = missing == 0 ? TYPE_BULK_DONE : TYPE_BULK_NACK;
                        byte[] resp = new byte[2 + bmLen];
                        resp[0] = (byte)(total & 0xFF);
                        resp[1] = (byte)((total >> 8) & 0xFF);
                        System.arraycopy(bm, 0, resp, 2, bmLen);
                        rxChan.send(FrameCodec.encode(0, rtype, resp));
                    } catch (Exception ex) {
                        System.err.printf("  [!] Ошибка приёмника: %s%n", ex);
                    }
                }
            }
        });

        // ── Отправитель: ожидает NACK/DONE ────────────────────────────────────
        FrameCodec.Decoder txDec = new FrameCodec.Decoder();
        BlockingQueue<FrameCodec.Frame> inbox = new LinkedBlockingQueue<>();
        txChan.setReceiveHandler(raw -> {
            txDec.feed(raw);
            FrameCodec.Frame fr2;
            while ((fr2 = txDec.poll()) != null) inbox.offer(fr2);
        });

        long delayMs = calcBulkDelayMs(payloadSz, noise);
        if (CMD_BULK_DELAY > 0) {
            System.out.printf("  [Override] задержка: %d мс → %d мс%n", delayMs, CMD_BULK_DELAY);
            delayMs = CMD_BULK_DELAY;
        }
        double theorBps = 2400.0 * (1.0 - noise);
        String sep = "─".repeat(60);
        System.out.printf("%n%s%n  BULK %s | %d Б/кадр | %d байт | %.0f%% шум%n",
            sep, tag, payloadSz, totalBytes, noise * 100);
        System.out.printf("  Кадров: %d | Задержка: %d мс/кадр | Теор.: %.0f байт/с%n",
            totalFrames, delayMs, theorBps);
        System.out.printf("%s%n", sep);

        long startMs     = System.currentTimeMillis();
        int  rounds      = 0;
        int  totalResent = 0;

        System.out.printf("  Раунд 1 — отправляем %d кадров...%n", totalFrames);
        sendBulkFrames(txChan, txData, totalFrames, payloadSz, null, delayMs);
        rounds++;

        outer:
        while (true) {
            FrameCodec.Frame resp = inbox.poll(3_000, TimeUnit.MILLISECONDS);
            if (resp == null) {
                System.out.printf("  (нет ответа 3 с — повторяем END)%n");
                byte[] end = { (byte)(totalFrames & 0xFF), (byte)((totalFrames >> 8) & 0xFF) };
                txChan.send(FrameCodec.encode(0, TYPE_BULK_END, end));
                continue;
            }
            switch (resp.type) {
                case TYPE_BULK_DONE -> {
                    System.out.printf("  Раунд %d — DONE ✓%n", rounds);
                    break outer;
                }
                case TYPE_BULK_NACK -> {
                    byte[] bm = resp.payload;
                    if (bm.length < 2) continue;
                    int nTotal = (bm[0] & 0xFF) | ((bm[1] & 0xFF) << 8);
                    boolean[] mask = new boolean[totalFrames];
                    int missing = 0;
                    for (int i = 0; i < nTotal && i < totalFrames; i++) {
                        if ((bm[2 + i / 8] & (1 << (i % 8))) == 0) { mask[i] = true; missing++; }
                    }
                    System.out.printf("  Раунд %d — принято: %d/%d (потеряно: %d = %.1f%%)%n",
                        rounds, nTotal - missing, nTotal, missing, 100.0 * missing / nTotal);
                    // Диагностика: индексы пропущенных кадров
                    if (missing > 0 && missing <= 30) {
                        StringBuilder sb = new StringBuilder("  Пропущены кадры:");
                        for (int i = 0; i < totalFrames; i++) if (mask[i]) sb.append(" ").append(i);
                        System.out.println(sb);
                    }
                    System.out.printf("  Раунд %d — досылаем %d кадров...%n", rounds + 1, missing);
                    sendBulkFrames(txChan, txData, totalFrames, payloadSz, mask, delayMs);
                    totalResent += missing;
                    rounds++;
                }
                default -> {}
            }
        }

        long   elapsed = System.currentTimeMillis() - startMs;
        double bps     = totalBytes / (elapsed / 1000.0);
        // Ожидаемый объём raw-байт ≈ totalFrames × (payloadSz + 17), если нет потерь jSerialComm
        int expectedRawBytes = totalFrames * (payloadSz + 17);

        System.out.printf("%s%n", sep);
        System.out.printf("  Время:      %.2f с%n", elapsed / 1000.0);
        System.out.printf("  Скорость:   %.0f байт/с (%.1f%% от теор. %.0f)%n",
            bps, bps / theorBps * 100, theorBps);
        System.out.printf("  Раунды:     %d | Ретрансм.: %d/%d | Дубли RX: %d%n",
            rounds, totalResent, totalFrames, dups.get());
        System.out.printf("  Байт→Decoder: %d (ожид. ≈ %d, разница: %d)%n",
            rxRawBytes.get(), expectedRawBytes, rxRawBytes.get() - expectedRawBytes);

        // Сборка и проверка
        byte[] assembled = new byte[totalBytes];
        boolean dataOk = true;
        for (int i = 0; i < totalFrames; i++) {
            int off = i * payloadSz;
            int len = Math.min(payloadSz, totalBytes - off);
            if (recvBuf[i] == null) {
                System.err.printf("  [!] Кадр %d не получен!%n", i);
                dataOk = false; continue;
            }
            if (recvBuf[i].length != len) {
                System.err.printf("  [!] Кадр %d: ожид. %d байт, получено %d%n", i, len, recvBuf[i].length);
                dataOk = false;
            }
            System.arraycopy(recvBuf[i], 0, assembled, off, Math.min(len, recvBuf[i].length));
        }
        int mismatches = 0;
        for (int i = 0; i < totalBytes && mismatches < 5; i++) {
            if (assembled[i] != txData[i]) {
                System.err.printf("  [!] Байт[%d] (кадр %d+%d): ожид=0x%02X получ=0x%02X%n",
                    i, i / payloadSz, i % payloadSz, txData[i] & 0xFF, assembled[i] & 0xFF);
                mismatches++;
                dataOk = false;
            }
        }
        System.out.printf("  Данные:     %s%n%s%n",
            dataOk ? "OK (" + totalBytes + " байт совпадают)" : "ОШИБКА!", sep);

        assertArrayEquals(txData, assembled, "BULK " + tag + ": данные повреждены");
    }

    // =========================================================================
    // Реализация: блочный bulk-протокол (тест размера блока, вопрос 2 дизайна)
    // =========================================================================

    /**
     * Передаёт totalBytes блоками по blockFrames кадров. Каждый блок доводится
     * до конца (раунды ретрансмитов по NACK), затем следующий.
     * DATA-кадр: [blk 1][idx_lo][idx_hi][данные] — idx ВНУТРИ блока (0..count-1).
     * NACK/END содержат blk → защита от «опоздавших» кадров чужого блока.
     */
    private void runBulkBlocks(int payloadSz, int totalBytes, int blockFrames,
                               SerialChannel txChan, SerialChannel rxChan,
                               double noise, String tag) throws Exception {
        final int totalFrames = (int) Math.ceil((double) totalBytes / payloadSz);
        final int numBlocks   = (int) Math.ceil((double) totalFrames / blockFrames);
        final int bmMax       = (blockFrames + 7) / 8;
        final int nackPayload = 3 + bmMax;                                  // blk + count(2) + bitmap
        final int nackFrame   = FrameCodec.HEADER + nackPayload + FrameCodec.TRAILER;
        final int nackChunks  = (nackFrame + 63) / 64;
        assertTrue(nackPayload <= FrameCodec.MAX_PAYLOAD,
            "NACK payload " + nackPayload + " Б > MAX_PAYLOAD — уменьшить bulk.block");
        assertTrue(numBlocks <= 256, "numBlocks " + numBlocks + " > 256 — blk не влезет в 1 байт");

        byte[] txData = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++) txData[i] = (byte)(i * 71 + 13);

        final byte[][] recvBuf = new byte[totalFrames][];
        final boolean[] gotIt  = new boolean[totalFrames];
        AtomicInteger dups       = new AtomicInteger(0);
        AtomicInteger rxRawBytes = new AtomicInteger(0);

        // ── Приёмник ──────────────────────────────────────────────────────────
        FrameCodec.Decoder rxDec = new FrameCodec.Decoder();
        rxChan.setReceiveHandler(raw -> {
            rxRawBytes.addAndGet(raw.length);
            rxDec.feed(raw);
            FrameCodec.Frame fr;
            while ((fr = rxDec.poll()) != null) {
                if (fr.type == TYPE_BLK_DATA && fr.payload.length >= 3) {
                    int blk = fr.payload[0] & 0xFF;
                    int idx = (fr.payload[1] & 0xFF) | ((fr.payload[2] & 0xFF) << 8);
                    int g   = blk * blockFrames + idx;
                    if (g < totalFrames) {
                        if (!gotIt[g]) {
                            gotIt[g]   = true;
                            recvBuf[g] = Arrays.copyOfRange(fr.payload, 3, fr.payload.length);
                        } else dups.incrementAndGet();
                    }
                } else if (fr.type == TYPE_BLK_END && fr.payload.length >= 3) {
                    int blk   = fr.payload[0] & 0xFF;
                    int count = (fr.payload[1] & 0xFF) | ((fr.payload[2] & 0xFF) << 8);
                    int base  = blk * blockFrames;
                    int bmLen = (count + 7) / 8;
                    byte[] bm = new byte[bmLen];
                    int missing = 0;
                    for (int i = 0; i < count; i++) {
                        if (base + i < totalFrames && gotIt[base + i]) bm[i / 8] |= (byte)(1 << (i % 8));
                        else missing++;
                    }
                    byte[] resp;
                    int rtype;
                    if (missing == 0) {
                        rtype = TYPE_BLK_DONE;
                        resp  = new byte[]{ (byte) blk };
                    } else {
                        rtype = TYPE_BLK_NACK;
                        resp  = new byte[3 + bmLen];
                        resp[0] = (byte) blk;
                        resp[1] = (byte)(count & 0xFF);
                        resp[2] = (byte)((count >> 8) & 0xFF);
                        System.arraycopy(bm, 0, resp, 3, bmLen);
                    }
                    rxChan.send(FrameCodec.encode(0, rtype, resp));
                }
            }
        });

        // ── Отправитель: приём NACK/DONE ──────────────────────────────────────
        FrameCodec.Decoder txDec = new FrameCodec.Decoder();
        BlockingQueue<FrameCodec.Frame> inbox = new LinkedBlockingQueue<>();
        txChan.setReceiveHandler(raw -> {
            txDec.feed(raw);
            FrameCodec.Frame fr2;
            while ((fr2 = txDec.poll()) != null) inbox.offer(fr2);
        });

        long delayMs = calcBulkDelayMs(payloadSz, noise);
        if (CMD_BULK_DELAY > 0) {
            System.out.printf("  [Override] задержка: %d мс → %d мс%n", delayMs, CMD_BULK_DELAY);
            delayMs = CMD_BULK_DELAY;
        }
        double theorBps = 2400.0 * (1.0 - noise);
        String sep = "─".repeat(60);
        System.out.printf("%n%s%n  BULK-BLOCKS %s | %d Б/кадр | %d байт%n", sep, tag, payloadSz, totalBytes);
        System.out.printf("  Кадров: %d | Блок: %d кадров × %d блоков | Задержка: %d мс%n",
            totalFrames, blockFrames, numBlocks, delayMs);
        System.out.printf("  NACK: payload %d Б → кадр %d Б = %d USB-кусков%n",
            nackPayload, nackFrame, nackChunks);
        System.out.printf("%s%n", sep);

        long startMs        = System.currentTimeMillis();
        int  totalRounds    = 0;
        int  totalResent    = 0;
        int  maxBlockRounds = 0;

        for (int b = 0; b < numBlocks; b++) {
            int base  = b * blockFrames;
            int count = Math.min(blockFrames, totalFrames - base);
            int blockRounds = 0;

            sendBlkFrames(txChan, txData, payloadSz, b, base, count, null, delayMs);
            blockRounds++;

            blockLoop:
            while (true) {
                FrameCodec.Frame resp = inbox.poll(3_000, TimeUnit.MILLISECONDS);
                if (resp == null) {                                 // потерян END/NACK → повторяем END
                    byte[] end = { (byte) b, (byte)(count & 0xFF), (byte)((count >> 8) & 0xFF) };
                    txChan.send(FrameCodec.encode(0, TYPE_BLK_END, end));
                    continue;
                }
                int rblk = resp.payload.length >= 1 ? (resp.payload[0] & 0xFF) : -1;
                if (rblk != b) continue;                            // ответ другого блока — отбрасываем
                switch (resp.type) {
                    case TYPE_BLK_DONE -> { break blockLoop; }
                    case TYPE_BLK_NACK -> {
                        if (resp.payload.length < 3) continue;
                        int n = (resp.payload[1] & 0xFF) | ((resp.payload[2] & 0xFF) << 8);
                        boolean[] mask = new boolean[count];
                        int missing = 0;
                        for (int i = 0; i < n && i < count; i++) {
                            if ((resp.payload[3 + i / 8] & (1 << (i % 8))) == 0) { mask[i] = true; missing++; }
                        }
                        sendBlkFrames(txChan, txData, payloadSz, b, base, count, mask, delayMs);
                        totalResent += missing;
                        blockRounds++;
                    }
                    default -> {}
                }
            }
            totalRounds   += blockRounds;
            maxBlockRounds = Math.max(maxBlockRounds, blockRounds);
            System.out.printf("  Блок %d/%d: %d кадров, %d раунд(ов)%n", b + 1, numBlocks, count, blockRounds);
        }

        long   elapsed = System.currentTimeMillis() - startMs;
        double bps     = totalBytes / (elapsed / 1000.0);
        System.out.printf("%s%n", sep);
        System.out.printf("  Время:      %.2f с%n", elapsed / 1000.0);
        System.out.printf("  Скорость:   %.0f байт/с (%.1f%% от теор. %.0f)%n",
            bps, bps / theorBps * 100, theorBps);
        System.out.printf("  Блоков: %d | Раундов всего: %d (макс/блок: %d) | Ретрансм.: %d | Дубли: %d%n",
            numBlocks, totalRounds, maxBlockRounds, totalResent, dups.get());
        System.out.printf("  Байт→Decoder: %d%n", rxRawBytes.get());

        byte[] assembled = new byte[totalBytes];
        for (int i = 0; i < totalFrames; i++) {
            int off = i * payloadSz;
            int len = Math.min(payloadSz, totalBytes - off);
            assertNotNull(recvBuf[i], "Кадр " + i + " не получен");
            System.arraycopy(recvBuf[i], 0, assembled, off, Math.min(len, recvBuf[i].length));
        }
        System.out.printf("  Данные:     %s%n%s%n",
            Arrays.equals(txData, assembled) ? "OK (" + totalBytes + " байт)" : "ОШИБКА!", sep);
        assertArrayEquals(txData, assembled, "BULK-BLOCKS " + tag + ": данные повреждены");
    }

    /** Отправляет DATA-кадры блока blk (idx 0..count-1) с пейсингом, затем BLK_END. mask=null → все. */
    private void sendBlkFrames(SerialChannel chan, byte[] data, int payloadSz,
                               int blk, int base, int count, boolean[] mask, long delayMs)
            throws InterruptedException {
        for (int i = 0; i < count; i++) {
            if (mask != null && !mask[i]) continue;
            int off = (base + i) * payloadSz;
            int len = Math.min(payloadSz, data.length - off);
            byte[] p = new byte[3 + len];
            p[0] = (byte) blk;
            p[1] = (byte)(i & 0xFF);
            p[2] = (byte)((i >> 8) & 0xFF);
            System.arraycopy(data, off, p, 3, len);
            chan.send(FrameCodec.encode(i & 0xFF, TYPE_BLK_DATA, p));
            Thread.sleep(delayMs);
        }
        byte[] end = { (byte) blk, (byte)(count & 0xFF), (byte)((count >> 8) & 0xFF) };
        chan.send(FrameCodec.encode(0, TYPE_BLK_END, end));
    }

    // =========================================================================
    // Sweep: WINDOW=1 по размеру кадра
    // =========================================================================

    private void runWindowSweep(int[] payloads, int bytesPerPoint) throws Exception {
        System.out.printf("%n[Sweep WINDOW=1] %d байт/точка%n", bytesPerPoint);
        System.out.printf("  %-6s  %-7s  %-8s  %-10s  %-7s  %-6s%n",
            "P,Б", "Кадров", "Время,с", "байт/с", "% теор", "Ретр.");
        System.out.println("  " + "─".repeat(52));

        int bestP = 0; double bestBps = 0;
        for (int p : payloads) {
            long[] r   = measureWindowPoint(p, bytesPerPoint);
            double bps = bytesPerPoint / (r[0] / 1000.0);
            System.out.printf("  %-6d  %-7d  %-8.2f  %-10.0f  %-7.1f  %-6d%s%n",
                p, r[1], r[0] / 1000.0, bps, bps / 2400 * 100, r[2], r[2] > 0 ? " ⚠" : "");
            if (bps > bestBps) { bestBps = bps; bestP = p; }
            Thread.sleep(500);
        }
        System.out.println("  " + "─".repeat(52));
        System.out.printf("  Лучший: %d байт → %.0f байт/с (%.1f%%)  |  теор. макс: 2400 байт/с%n",
            bestP, bestBps, bestBps / 2400 * 100);
    }

    /** Одна точка sweep WINDOW=1. Возвращает [elapsedMs, frames, retransmits]. */
    private long[] measureWindowPoint(int payloadSz, int totalBytes) throws Exception {
        byte[] data = new byte[totalBytes];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 71 + 13);

        SlidingWindowSender txSender = new SlidingWindowSender(WINDOW, chanA::send);
        AtomicInteger rxBytes = new AtomicInteger(0);
        SlidingWindowReceiver rxWin = new SlidingWindowReceiver(WINDOW, chanB::send,
            fr -> { if (fr.type == FrameCodec.TYPE_FILE_DATA) rxBytes.addAndGet(fr.payload.length); });
        AckProcessor txAck = new AckProcessor(txSender);
        AckProcessor rxAck = new AckProcessor(new SlidingWindowSender(1, b -> {}));
        txAck.setDataHandler(f -> {});
        rxAck.setDataHandler(rxWin::onFrame);
        chanA.setReceiveHandler(txAck::feed);
        chanB.setReceiveHandler(rxAck::feed);

        ScheduledExecutorService timer = startRetransmitTimer(txSender);
        long t0 = System.currentTimeMillis();
        int frames = 0, off = 0;
        while (off < data.length) {
            int len = Math.min(payloadSz, data.length - off);
            txSender.trySend(FrameCodec.TYPE_FILE_DATA,
                Arrays.copyOfRange(data, off, off + len), 30_000);
            off += len;
            frames++;
        }
        waitFor(() -> rxBytes.get() >= totalBytes && txSender.inFlight() == 0, 300_000);
        long elapsed = System.currentTimeMillis() - t0;

        timer.shutdownNow();
        chanA.setReceiveHandler(ackA::feed);
        chanB.setReceiveHandler(ackB::feed);

        return new long[]{ elapsed, frames, txSender.getRetransmitCount() };
    }

    // =========================================================================
    // Sweep: Bulk по размеру кадра
    // =========================================================================

    private void runBulkSweep(int[] payloads, int bytesPerPoint, double noise) throws Exception {
        System.out.printf("%n[Sweep Bulk] %.0f%% шум | %d байт/точка%n", noise * 100, bytesPerPoint);
        System.out.printf("  %-7s  %-8s  %-9s  %-8s  %-7s  %-9s  %-7s%n",
            "Data,Б", "FSO,Б", "Delay,мс", "Время,с", "байт/с", "% теор.", "Раунды");
        System.out.println("  " + "─".repeat(62));

        double theorBps = 2400.0 * (1.0 - noise);
        for (int p : payloads) {
            long delayMs = calcBulkDelayMs(p, noise);
            long[] r = measureBulkPoint(p, bytesPerPoint, noise, delayMs, 300_000);
            double bps = bytesPerPoint / (r[0] / 1000.0);
            System.out.printf("  %-7d  %-8d  %-9d  %-8.2f  %-7.0f  %-9.1f  %-7d%s%n",
                p, p + 17, delayMs, r[0] / 1000.0, bps, bps / theorBps * 100, r[1],
                r[1] > 2 ? " ⚠" : "");
            Thread.sleep(500);
        }
        System.out.println("  " + "─".repeat(62));
        System.out.printf("  Теор. макс при %.0f%% шуме: %.0f байт/с%n", noise * 100, theorBps);
    }

    /** Одна точка sweep Bulk. Возвращает [elapsedMs, rounds]. */
    private long[] measureBulkPoint(int payloadSz, int totalBytes, double noise,
                                    long delayMs, long timeoutMs) throws Exception {
        int totalFrames = (int) Math.ceil((double) totalBytes / payloadSz);
        byte[] data = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++) data[i] = (byte)(i * 71 + 13);

        boolean[] gotIt = new boolean[totalFrames];
        FrameCodec.Decoder rxDec = new FrameCodec.Decoder();
        chanB.setReceiveHandler(raw -> {
            rxDec.feed(raw);
            FrameCodec.Frame fr;
            while ((fr = rxDec.poll()) != null) {
                if (fr.type == TYPE_BULK_DATA && fr.payload.length >= 2) {
                    int idx = (fr.payload[0] & 0xFF) | ((fr.payload[1] & 0xFF) << 8);
                    if (idx < totalFrames) gotIt[idx] = true;
                } else if (fr.type == TYPE_BULK_END && fr.payload.length >= 2) {
                    try {
                        int total = (fr.payload[0] & 0xFF) | ((fr.payload[1] & 0xFF) << 8);
                        int bmLen = (total + 7) / 8;
                        byte[] bm = new byte[bmLen];
                        int miss = 0;
                        for (int i = 0; i < total; i++) {
                            if (gotIt[i]) bm[i / 8] |= (byte)(1 << (i % 8));
                            else miss++;
                        }
                        byte[] resp = new byte[2 + bmLen];
                        resp[0] = (byte)(total & 0xFF);
                        resp[1] = (byte)((total >> 8) & 0xFF);
                        System.arraycopy(bm, 0, resp, 2, bmLen);
                        chanB.send(FrameCodec.encode(0, miss == 0 ? TYPE_BULK_DONE : TYPE_BULK_NACK, resp));
                    } catch (Exception ignored) {}
                }
            }
        });

        FrameCodec.Decoder txDec = new FrameCodec.Decoder();
        BlockingQueue<FrameCodec.Frame> inbox = new LinkedBlockingQueue<>();
        chanA.setReceiveHandler(raw -> {
            txDec.feed(raw);
            FrameCodec.Frame fr2;
            while ((fr2 = txDec.poll()) != null) inbox.offer(fr2);
        });

        long t0 = System.currentTimeMillis(), deadline = t0 + timeoutMs;
        int rounds = 0;
        sendBulkFrames(chanA, data, totalFrames, payloadSz, null, delayMs);
        rounds++;

        outer:
        while (System.currentTimeMillis() < deadline) {
            long rem = deadline - System.currentTimeMillis();
            FrameCodec.Frame resp = inbox.poll(Math.min(rem, 3_000), TimeUnit.MILLISECONDS);
            if (resp == null) {
                byte[] end = { (byte)(totalFrames & 0xFF), (byte)((totalFrames >> 8) & 0xFF) };
                chanA.send(FrameCodec.encode(0, TYPE_BULK_END, end));
                continue;
            }
            switch (resp.type) {
                case TYPE_BULK_DONE -> { break outer; }
                case TYPE_BULK_NACK -> {
                    byte[] bm = resp.payload;
                    if (bm.length < 2) continue;
                    int nTotal = (bm[0] & 0xFF) | ((bm[1] & 0xFF) << 8);
                    boolean[] mask = new boolean[totalFrames];
                    for (int i = 0; i < nTotal && i < totalFrames; i++)
                        if ((bm[2 + i / 8] & (1 << (i % 8))) == 0) mask[i] = true;
                    sendBulkFrames(chanA, data, totalFrames, payloadSz, mask, delayMs);
                    rounds++;
                }
                default -> {}
            }
        }

        chanA.setReceiveHandler(ackA::feed);
        chanB.setReceiveHandler(ackB::feed);
        return new long[]{ System.currentTimeMillis() - t0, rounds };
    }

    // =========================================================================
    // Bulk helpers
    // =========================================================================

    /**
     * Отправляет bulk-кадры с задержкой delayMs между ними, затем BULK_END.
     * @param mask null = все кадры; mask[i]=true = отправить только i-й
     */
    private void sendBulkFrames(SerialChannel chan, byte[] data, int totalFrames,
                                 int payloadSz, boolean[] mask, long delayMs)
            throws InterruptedException {
        for (int i = 0; i < totalFrames; i++) {
            if (mask != null && !mask[i]) continue;
            int off = i * payloadSz;
            int len = Math.min(payloadSz, data.length - off);
            byte[] payload = new byte[2 + len];
            payload[0] = (byte)(i & 0xFF);
            payload[1] = (byte)((i >> 8) & 0xFF);
            System.arraycopy(data, off, payload, 2, len);
            chan.send(FrameCodec.encode(i & 0xFF, TYPE_BULK_DATA, payload));
            Thread.sleep(delayMs);
        }
        byte[] end = { (byte)(totalFrames & 0xFF), (byte)((totalFrames >> 8) & 0xFF) };
        chan.send(FrameCodec.encode(0, TYPE_BULK_END, end));
    }

    /**
     * Задержка между bulk-кадрами.
     * Для 238 байт чистый канал: ceil(106.25) + 30 = 137 мс.
     */
    private static long calcBulkDelayMs(int payloadSz, double noise) {
        // Реальная упаковка на STM: USB режет кадр на куски ≤64 Б, и КАЖДЫЙ кусок
        // пакуется в отдельный PtPP2-пакет (+10 Б). Значит overhead = 10×ceil(кадр/64),
        // а не 10 однократно. FSO-байт растёт ступенями по 64 → пол передачи скачет.
        int frameLen = payloadSz + FrameCodec.HEADER + FrameCodec.TRAILER + 2; // +2 idx
        int chunks   = (frameLen + 63) / 64;            // сколько USB-кусков ≤64 Б
        int fsoBytes = frameLen + 10 * chunks;          // PtPP2 на каждый кусок
        double txMs  = fsoBytes * 10.0 / FSO_BAUD * 1000.0;
        // Запас 12% над полом: держит подушку над обрывом, но не душит мелкие кадры
        // (для 64-Б кадра ~35 мс вместо прежних 61, для 256-Б ~133 мс).
        return (long) Math.ceil(txMs / (1.0 - noise) * 1.12);
    }

    // =========================================================================
    // Вспомогательные методы
    // =========================================================================

    private void sendText(SlidingWindowSender s, String text) {
        byte[][] payloads = TextAssembler.encodePayloads(text);
        Thread t = new Thread(() -> {
            try { for (byte[] p : payloads) s.trySend(FrameCodec.TYPE_DATA, p, 5_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "send-text");
        t.setDaemon(true);
        t.start();
    }

    private ScheduledExecutorService startNoiseInjector(SerialChannel chan, double fraction) {
        double frameMs  = NOISE_FSO_BYTES * 10.0 / FSO_BAUD * 1000.0;
        long intervalMs = Math.max(1, Math.round(frameMs / fraction));
        AtomicInteger seq = new AtomicInteger(0);
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "noise");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> {
            byte[] p = new byte[NOISE_PAYLOAD];
            Arrays.fill(p, (byte) 0xCC);
            try { chan.send(FrameCodec.encode(seq.getAndIncrement() & 0xFF, TYPE_NOISE, p)); }
            catch (Exception ignored) {}
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        System.out.printf("[Noise] запущен: %d мс интервал (%.0f%% канала)%n", intervalMs, fraction * 100);
        return ex;
    }

    private static ScheduledExecutorService startRetransmitTimer(SlidingWindowSender s) {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retransmit");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> {
            if (s.inFlight() == 0) return;
            if (System.currentTimeMillis() - s.getLastAckAdvanceMs() >= RT_MS)
                s.retransmitUnconfirmed();
        }, RT_MS, RT_MS, TimeUnit.MILLISECONDS);
        return ex;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline)
            Thread.sleep(50);
    }

    /** Возвращает объём данных: из -Dtest.bytes если задан, иначе defaultVal. */
    private static int testBytes(int defaultVal) {
        return CMD_TEST_BYTES > 0 ? CMD_TEST_BYTES : defaultVal;
    }

    /** Возвращает размер bulk-payload: из -Dbulk.payload если задан, иначе defaultVal. */
    private static int bulkPayload(int defaultVal) {
        return CMD_BULK_PAYLOAD > 0 ? CMD_BULK_PAYLOAD : defaultVal;
    }

    /** Возвращает число кадров в блоке: из -Dbulk.block если задано, иначе defaultVal. */
    private static int blockFrames(int defaultVal) {
        return CMD_BULK_BLOCK > 0 ? CMD_BULK_BLOCK : defaultVal;
    }

    /** Возвращает размер WINDOW=1 payload: из -Dwindow.payload если задан, иначе 240. */
    private static int windowPayload() {
        return CMD_WINDOW_PAYLOAD > 0 ? CMD_WINDOW_PAYLOAD : 240;
    }
}
