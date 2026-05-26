package fsoterminal;

import fsoterminal.channel.FSOEmulator;
import fsoterminal.protocol.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
