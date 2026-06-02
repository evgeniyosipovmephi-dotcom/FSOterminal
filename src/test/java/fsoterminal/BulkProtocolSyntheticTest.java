package fsoterminal;

import fsoterminal.channel.FSOEmulator;
import fsoterminal.protocol.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Синтетические тесты прод-классов bulk/MSG на FSOEmulator (без железа).
 *
 * Топология: A→B канал с потерями (forward), B→A чистый (reverse).
 * frameOutput каждого PacedTransmitter гонит кадр через эмулятор и при доставке
 * скармливает декодеру другой стороны. Каждый декодер питается из ОДНОГО потока
 * (forward — из потока txA, reverse — из txB), так что BulkReceiver thread-safe не нужен.
 *
 * Задержка пейсинга обнулена (overdrive = floor) — тест гоняется быстро.
 */
class BulkProtocolSyntheticTest {

    private static ProtocolConfig fastConfig() {
        ProtocolConfig c = new ProtocolConfig();
        c.bulkOverdriveMs = 1000; // delay = max(0, floor − 1000) = 0 → без пейсинга
        return c;
    }

    @Test
    void bulkTransfer_withLoss_reassemblesExactly() throws Exception {
        byte[] data = new byte[8 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 37 + 5);

        FSOEmulator emuFwd = new FSOEmulator(0.12, 42);  // 12% потерь A→B
        FSOEmulator emuRev = new FSOEmulator(0.0, 1);    // чистый B→A

        FrameCodec.Decoder decA = new FrameCodec.Decoder();
        FrameCodec.Decoder decB = new FrameCodec.Decoder();

        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> rxName   = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        BulkSender[]   sxBox = new BulkSender[1];
        BulkReceiver[] rxBox = new BulkReceiver[1];

        // B→A: доставленные кадры → декодер A → BulkSender.onControlFrame (NACK/DONE)
        PacedTransmitter txB = new PacedTransmitter(frame -> {
            byte[] r = emuRev.pass(frame);
            if (r != null) {
                decA.feed(r);
                FrameCodec.Frame f;
                while ((f = decA.poll()) != null) sxBox[0].onControlFrame(f);
            }
        });
        // A→B: доставленные кадры → декодер B → BulkReceiver (DATA/END/FILE_*)
        PacedTransmitter txA = new PacedTransmitter(frame -> {
            byte[] r = emuFwd.pass(frame);
            if (r != null) {
                decB.feed(r);
                FrameCodec.Frame f;
                while ((f = decB.poll()) != null) rxBox[0].feed(f);
            }
        });

        rxBox[0] = new BulkReceiver(txB, (kind, name, bytes) -> {
            rxName.set(name);
            received.set(bytes);
            done.countDown();
        }, null);
        sxBox[0] = new BulkSender(txA, fastConfig());

        txA.start();
        txB.start();

        sxBox[0].send(BulkProtocol.KIND_FILE, "test.bin", data, null, () -> {}, err -> fail("Ошибка отправки: " + err));

        assertTrue(done.await(60, TimeUnit.SECONDS), "Передача не завершилась за 60 с");
        txA.stop();
        txB.stop();

        assertEquals("test.bin", rxName.get());
        assertArrayEquals(data, received.get(), "Данные после bulk-передачи повреждены");
    }

    @Test
    void msgChannel_fragmentsLongText_roundTrip() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("Привет мир ").append(i).append("! ");
        String text = sb.toString();   // заведомо длиннее одного MSG-кадра

        FSOEmulator emuFwd = new FSOEmulator(0.10, 7);
        FSOEmulator emuRev = new FSOEmulator(0.10, 9);

        FrameCodec.Decoder decA = new FrameCodec.Decoder();
        FrameCodec.Decoder decB = new FrameCodec.Decoder();

        AtomicReference<String> rxText = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        MsgChannel[] msgB = new MsgChannel[1];
        MsgChannel[] msgA = new MsgChannel[1];

        PacedTransmitter txA = new PacedTransmitter(frame -> {
            byte[] r = emuFwd.pass(frame);
            if (r != null) { decB.feed(r); FrameCodec.Frame f; while ((f = decB.poll()) != null) msgB[0].feed(f); }
        });
        PacedTransmitter txB = new PacedTransmitter(frame -> {
            byte[] r = emuRev.pass(frame);
            if (r != null) { decA.feed(r); FrameCodec.Frame f; while ((f = decA.poll()) != null) msgA[0].feed(f); }
        });

        msgA[0] = new MsgChannel(txA, t -> {});                    // A не принимает в этом тесте
        msgB[0] = new MsgChannel(txB, t -> { rxText.set(t); done.countDown(); });

        txA.start();
        txB.start();

        msgA[0].send(text, err -> fail("Ошибка отправки MSG: " + err));

        assertTrue(done.await(30, TimeUnit.SECONDS), "MSG не доставлен за 30 с");
        txA.stop();
        txB.stop();

        assertEquals(text, rxText.get(), "Текст после фрагментации/сборки не совпал");
    }
}
