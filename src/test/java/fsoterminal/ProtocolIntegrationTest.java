package fsoterminal;

import fsoterminal.channel.FSOEmulator;
import fsoterminal.protocol.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end тест протокола: два симметричных узла (A и B) обмениваются
 * сообщениями через FSOEmulator с потерями. Проверяем надёжную доставку.
 *
 * Топология:
 *   senderA ─[chanAtoB]─► receiverB ─► ackProcA ─► senderA (кольцо)
 *   senderB ─[chanBtoA]─► receiverA ─► ackProcB ─► senderB
 */
class ProtocolIntegrationTest {

    // -------------------------------------------------------------------------
    // Сценарий 1: идеальный канал
    // -------------------------------------------------------------------------

    @Test
    void perfectChannel_allMessagesDelivered() throws InterruptedException {
        Endpoint a = new Endpoint(4);
        Endpoint b = new Endpoint(4);

        FSOEmulator chanAtoB = new FSOEmulator(0.0, 1);
        FSOEmulator chanBtoA = new FSOEmulator(0.0, 2);

        sendMessages(a, b, chanAtoB, chanBtoA, 20);

        assertEquals(20, b.delivered.size());
        verifyPayloads(a.messages, b.delivered);
    }

    // -------------------------------------------------------------------------
    // Сценарий 2: 10% потерь данных, 5% потерь ACK
    // -------------------------------------------------------------------------

    @Test
    void noisyChannel_allMessagesDeliveredReliably() throws InterruptedException {
        Endpoint a = new Endpoint(4);
        Endpoint b = new Endpoint(4);

        FSOEmulator chanAtoB = new FSOEmulator(0.10, 42);
        FSOEmulator chanBtoA = new FSOEmulator(0.05, 99);

        sendMessages(a, b, chanAtoB, chanBtoA, 20);

        assertEquals(20, b.delivered.size());
        verifyPayloads(a.messages, b.delivered);
    }

    // -------------------------------------------------------------------------
    // Сценарий 3: burst-потери (3 кадра подряд, 20% вероятность)
    // -------------------------------------------------------------------------

    @Test
    void burstLoss_allMessagesDelivered() throws InterruptedException {
        Endpoint a = new Endpoint(4);
        Endpoint b = new Endpoint(4);

        FSOEmulator chanAtoB = new FSOEmulator(0.20, 3, 77);
        FSOEmulator chanBtoA = new FSOEmulator(0.10, 2, 88);

        sendMessages(a, b, chanAtoB, chanBtoA, 20);

        assertEquals(20, b.delivered.size());
        verifyPayloads(a.messages, b.delivered);
    }

    // -------------------------------------------------------------------------
    // Сценарий 4: двустороннее общение
    // -------------------------------------------------------------------------

    @Test
    void bidirectional_bothSidesReceiveAllMessages() throws InterruptedException {
        Endpoint a = new Endpoint(4);
        Endpoint b = new Endpoint(4);

        FSOEmulator chanAtoB = new FSOEmulator(0.05, 42);
        FSOEmulator chanBtoA = new FSOEmulator(0.05, 43);

        // Готовим сообщения на обоих концах
        for (int i = 0; i < 10; i++) a.messages.add(("A→B #" + i).getBytes());
        for (int i = 0; i < 10; i++) b.messages.add(("B→A #" + i).getBytes());

        runProtocolLoop(a, b, chanAtoB, chanBtoA, 10, 10);

        assertEquals(10, b.delivered.size());
        assertEquals(10, a.delivered.size());
    }

    // -------------------------------------------------------------------------
    // Сценарий 5: передача файла (FILE_BEGIN / FILE_DATA×N / FILE_END)
    // -------------------------------------------------------------------------

    @Test
    void fileTransfer_5percentLoss_fileAssembledCorrectly() throws InterruptedException {
        // «Файл» — 2 KB псевдослучайных байт
        byte[] original = new byte[2048];
        for (int i = 0; i < original.length; i++) original[i] = (byte)(i * 37 + 13);
        String fileName = "test.bin";

        // Стек A (отправитель файла)
        List<byte[]> aOut = new ArrayList<>();
        SlidingWindowSender   senderA   = new SlidingWindowSender(4, aOut::add);
        AckProcessor          ackA      = new AckProcessor(senderA);
        List<FrameCodec.Frame> aDelivered = new ArrayList<>();
        SlidingWindowReceiver receiverA  = new SlidingWindowReceiver(4, aOut::add, aDelivered::add);
        ackA.setDataHandler(receiverA::onFrame);

        // Стек B (получатель файла)
        List<byte[]> bOut = new ArrayList<>();
        SlidingWindowSender   senderB   = new SlidingWindowSender(4, bOut::add);
        AckProcessor          ackB      = new AckProcessor(senderB);
        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler fa = new FileAssembler(
            (n, sz) -> {},
            (r, t)  -> {},
            (n, d)  -> received.set(d)
        );
        SlidingWindowReceiver receiverB  = new SlidingWindowReceiver(4, bOut::add, fa::onFrame);
        ackB.setDataHandler(receiverB::onFrame);

        // Очередь кадров файла для A
        List<int[]>  types    = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();
        types.add(new int[]{FrameCodec.TYPE_FILE_BEGIN});
        payloads.add(encodeFileBegin(fileName, original.length));
        int off = 0;
        while (off < original.length) {
            int len = Math.min(FrameCodec.MAX_PAYLOAD, original.length - off);
            types.add(new int[]{FrameCodec.TYPE_FILE_DATA});
            payloads.add(Arrays.copyOfRange(original, off, off + len));
            off += len;
        }
        types.add(new int[]{FrameCodec.TYPE_FILE_END});
        payloads.add(new byte[0]);

        FSOEmulator chanAtoB = new FSOEmulator(0.05, 42);
        FSOEmulator chanBtoA = new FSOEmulator(0.02, 43);

        int idx = 0;
        for (int round = 0; round < 5000; round++) {
            // A пытается выдать следующий кадр файла
            while (idx < payloads.size() &&
                   senderA.trySend(types.get(idx)[0], payloads.get(idx), 0)) {
                idx++;
            }
            transferFrames(aOut, chanAtoB, ackB);
            transferFrames(bOut, chanBtoA, ackA);
            if (received.get() != null) break;
            if (idx >= payloads.size() && senderA.inFlight() > 0)
                senderA.retransmitUnconfirmed();
        }

        assertNotNull(received.get(), "Файл не был собран получателем");
        assertArrayEquals(original, received.get(), "Содержимое файла не совпадает");
    }

    // -------------------------------------------------------------------------
    // Вспомогательные: кодирование FILE_BEGIN
    // -------------------------------------------------------------------------

    private static byte[] encodeFileBegin(String name, long totalBytes) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        int nameLen = Math.min(nb.length, 245);
        byte[] p = new byte[1 + nameLen + 4];
        p[0] = (byte) nameLen;
        System.arraycopy(nb, 0, p, 1, nameLen);
        int sz = (int) Math.min(totalBytes, 0xFFFFFFFFL);
        p[1+nameLen] = (byte)(sz); p[2+nameLen] = (byte)(sz>>8);
        p[3+nameLen] = (byte)(sz>>16); p[4+nameLen] = (byte)(sz>>24);
        return p;
    }

    // -------------------------------------------------------------------------
    // Вспомогательные классы и методы
    // -------------------------------------------------------------------------

    /** Один узел протокола: отправитель + приёмник + ACK-процессор. */
    private static class Endpoint {
        final List<byte[]>           outbound  = new ArrayList<>(); // исходящие кадры
        final List<FrameCodec.Frame> delivered = new ArrayList<>(); // принятые данные
        final List<byte[]>           messages  = new ArrayList<>(); // что хотим отправить

        final SlidingWindowSender   sender;
        final SlidingWindowReceiver receiver;
        final AckProcessor          ackProc;

        Endpoint(int windowSize) {
            sender   = new SlidingWindowSender(windowSize, outbound::add);
            ackProc  = new AckProcessor(sender);
            receiver = new SlidingWindowReceiver(windowSize, outbound::add, delivered::add);
            ackProc.setDataHandler(receiver::onFrame);
        }
    }

    /** Подготавливает сообщения на узле A и запускает симуляцию. */
    private void sendMessages(Endpoint a, Endpoint b,
                              FSOEmulator chanAtoB, FSOEmulator chanBtoA,
                              int count) throws InterruptedException {
        for (int i = 0; i < count; i++)
            a.messages.add(("msg-" + i).getBytes());

        runProtocolLoop(a, b, chanAtoB, chanBtoA, count, 0);
    }

    /**
     * Симулирует обмен кадрами итеративно (single-threaded).
     * На каждом шаге: заполняем окна → передаём через эмулятор → обрабатываем ACK.
     * При зависании вызываем retransmitUnconfirmed.
     */
    private void runProtocolLoop(Endpoint a, Endpoint b,
                                 FSOEmulator chanAtoB, FSOEmulator chanBtoA,
                                 int expectedAtB, int expectedAtA) throws InterruptedException {

        int aMsgIdx = 0, bMsgIdx = 0;
        int stallRounds = 0;

        for (int round = 0; round < 2000; round++) {
            boolean progress = false;

            int bDeliveredBefore = b.delivered.size();
            int aDeliveredBefore = a.delivered.size();

            // A пытается отправить очередные сообщения
            while (aMsgIdx < a.messages.size() && a.sender.trySend(
                    FrameCodec.TYPE_DATA, a.messages.get(aMsgIdx), 0)) {
                aMsgIdx++;
            }

            // B пытается отправить очередные сообщения
            while (bMsgIdx < b.messages.size() && b.sender.trySend(
                    FrameCodec.TYPE_DATA, b.messages.get(bMsgIdx), 0)) {
                bMsgIdx++;
            }

            // Передаём кадры A→B через эмулятор
            progress |= transferFrames(a.outbound, chanAtoB, b.ackProc);

            // Передаём кадры B→A через эмулятор (данные + ACK)
            progress |= transferFrames(b.outbound, chanBtoA, a.ackProc);

            if (b.delivered.size() > bDeliveredBefore) progress = true;
            if (a.delivered.size() > aDeliveredBefore) progress = true;

            // Готово?
            if (b.delivered.size() >= expectedAtB && a.delivered.size() >= expectedAtA) break;

            // Обнаружение зависания → ретрансмит
            if (!progress) {
                stallRounds++;
                if (stallRounds >= 5) {
                    a.sender.retransmitUnconfirmed();
                    b.sender.retransmitUnconfirmed();
                    stallRounds = 0;
                }
            } else {
                stallRounds = 0;
            }
        }
    }

    /** Передаёт кадры из outbound через эмулятор в ackProc приёмника. */
    private boolean transferFrames(List<byte[]> outbound,
                                   FSOEmulator emulator,
                                   AckProcessor ackProc) {
        if (outbound.isEmpty()) return false;

        List<byte[]> batch = new ArrayList<>(outbound);
        outbound.clear();
        boolean anyDelivered = false;

        for (byte[] raw : batch) {
            byte[] passed = emulator.pass(raw);
            if (passed != null) {
                ackProc.feed(passed);
                anyDelivered = true;
            }
        }
        return anyDelivered;
    }

    /** Проверяет, что доставленные payload совпадают с отправленными. */
    private void verifyPayloads(List<byte[]> sent, List<FrameCodec.Frame> received) {
        assertEquals(sent.size(), received.size(), "Количество сообщений не совпадает");
        for (int i = 0; i < sent.size(); i++) {
            assertArrayEquals(sent.get(i), received.get(i).payload,
                "Сообщение #" + i + " не совпадает");
        }
    }

    // -------------------------------------------------------------------------
    // Сценарий 6: эффективность передачи 10 KB (Selective Repeat не флудит канал)
    // -------------------------------------------------------------------------

    /**
     * Проверяет, что после исправления onAck() (gap-based Selective Repeat)
     * передача 10 KB файла не создаёт лавину лишних ретрансмитов.
     *
     * Теоретика:
     *   • FILE_BEGIN + ceil(10240/250) + FILE_END = 43 кадра
     *   • С 5% потерями: ожидаемых ретрансмитов ~5% → итого ~45–46 кадров
     *   • Граница теста: totalSent ≤ totalNeeded × 2  (≤100% overhead)
     *   • Старый код при окне 8: каждый ACK → 7 retransmit → >500 кадров вместо 43
     */
    @Test
    void fileTransfer_10KB_efficiencyCheck() throws InterruptedException {
        final int WINDOW = 8;
        final int CHUNK  = FrameCodec.MAX_PAYLOAD; // 250 байт

        // Файл 10 KB псевдослучайных байт
        byte[] original = new byte[10_240];
        for (int i = 0; i < original.length; i++) original[i] = (byte)(i * 131 + 17);
        String fileName = "bigtest.bin";

        // ----- Стек A (отправитель файла) -----
        // Оборачиваем frameOutput сендера для подсчёта ВСЕХ отправленных кадров
        // (оригинальные отправки + любые ретрансмиты)
        List<byte[]> aOut      = new ArrayList<>();
        int[]        sentCount = {0};

        SlidingWindowSender senderA = new SlidingWindowSender(WINDOW, frame -> {
            aOut.add(frame);
            sentCount[0]++;
        });
        AckProcessor          ackA      = new AckProcessor(senderA);
        List<FrameCodec.Frame> aDelivered = new ArrayList<>();
        // receiverA пассивен: B файлы обратно не шлёт; ACKи от него идут через bOut
        SlidingWindowReceiver receiverA  = new SlidingWindowReceiver(WINDOW, aOut::add, aDelivered::add);
        ackA.setDataHandler(receiverA::onFrame);

        // ----- Стек B (получатель файла) -----
        List<byte[]> bOut = new ArrayList<>();
        SlidingWindowSender   senderB   = new SlidingWindowSender(WINDOW, bOut::add);
        AckProcessor          ackB      = new AckProcessor(senderB);
        AtomicReference<byte[]> received = new AtomicReference<>();
        FileAssembler fa = new FileAssembler(
            (n, sz) -> {},
            (r, t)  -> {},
            (n, d)  -> received.set(d)
        );
        SlidingWindowReceiver receiverB  = new SlidingWindowReceiver(WINDOW, bOut::add, fa::onFrame);
        ackB.setDataHandler(receiverB::onFrame);

        // ----- Очередь кадров файла -----
        List<int[]>  types    = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();
        types.add(new int[]{FrameCodec.TYPE_FILE_BEGIN});
        payloads.add(encodeFileBegin(fileName, original.length));
        int off = 0;
        while (off < original.length) {
            int len = Math.min(CHUNK, original.length - off);
            types.add(new int[]{FrameCodec.TYPE_FILE_DATA});
            payloads.add(Arrays.copyOfRange(original, off, off + len));
            off += len;
        }
        types.add(new int[]{FrameCodec.TYPE_FILE_END});
        payloads.add(new byte[0]);

        int totalFramesNeeded = payloads.size(); // 1 + 41 + 1 = 43

        // ----- Эмуляторы канала -----
        FSOEmulator chanAtoB = new FSOEmulator(0.05, 42); // 5% потерь данных
        FSOEmulator chanBtoA = new FSOEmulator(0.02, 43); // 2% потерь ACK

        // ----- Симуляция (tick-based, каждый round ≈ 1 RTT) -----
        int idx    = 0;
        int rounds = 0;

        for (int round = 0; round < 5000; round++) {
            // A заполняет окно следующими кадрами файла (trySend неблокирующий: timeout=0)
            while (idx < payloads.size() &&
                   senderA.trySend(types.get(idx)[0], payloads.get(idx), 0)) {
                idx++;
            }
            transferFrames(aOut, chanAtoB, ackB);
            transferFrames(bOut, chanBtoA, ackA);
            rounds++;

            if (received.get() != null) break;

            // Таймерный fallback: все кадры отправлены, но хвостовой потерян
            if (idx >= payloads.size() && senderA.inFlight() > 0)
                senderA.retransmitUnconfirmed();
        }

        // ----- Метрики эффективности -----
        int    totalSent    = sentCount[0];
        int    overhead     = totalSent - totalFramesNeeded;
        double overheadPct  = 100.0 * overhead / totalFramesNeeded;

        // Теоретическое время (все кадры × размер / скорость канала)
        double frameBytes   = CHUNK + FrameCodec.HEADER + FrameCodec.TRAILER; // ~255 байт
        double chanBytesS   = 3000.0; // ~24 кбит/с
        double theorSec     = totalFramesNeeded * frameBytes / chanBytesS;
        double simulatedSec = rounds * 0.130; // каждый round ≈ RTT/2 ≈ 65 мс... берём полный RTT

        System.out.printf(
            "%n[10KB efficiency] Кадров нужно: %d | Отправлено всего: %d | Overhead: +%d (%.1f%%)%n",
            totalFramesNeeded, totalSent, overhead, overheadPct);
        System.out.printf(
            "[10KB efficiency] Теор. время: %.2f с | Симул. время: %.2f с | Раундов: %d%n",
            theorSec, simulatedSec, rounds);

        // ----- Assertions -----
        assertNotNull(received.get(),
            "Файл не был собран получателем за 5000 раундов");
        assertArrayEquals(original, received.get(),
            "Содержимое полученного файла не совпадает с оригиналом");
        assertTrue(totalSent <= totalFramesNeeded * 2,
            String.format("Слишком много ретрансмитов: отправлено %d, нужно %d, лимит ×2 = %d",
                totalSent, totalFramesNeeded, totalFramesNeeded * 2));
    }
}
