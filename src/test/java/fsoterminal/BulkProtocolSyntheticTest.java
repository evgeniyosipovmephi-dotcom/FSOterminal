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
 * Каждый декодер питается из ОДНОГО потока (forward — из потока txA, reverse — из txB),
 * так что BulkReceiver thread-safe не нужен.
 */
class BulkProtocolSyntheticTest {

    /** Конфиг с заданным over-drive (задержка пейсинга = пол − overdrive). */
    private static ProtocolConfig cfg(int overdriveMs) {
        ProtocolConfig c = new ProtocolConfig();
        c.bulkOverdriveMs = overdriveMs;
        return c;
    }

    /**
     * Прогоняет bulk-передача A→B через эмулятор и возвращает принятые байты.
     * @param overdrive over-drive (пол кадра 64 Б = 31 мс; 1000 → задержка 0; 21 → 10 мс)
     */
    private byte[] runBulk(byte[] data, double fwdLoss, int overdrive, int timeoutSec) throws Exception {
        FSOEmulator emuFwd = new FSOEmulator(fwdLoss, 42);
        FSOEmulator emuRev = new FSOEmulator(0.0, 7);
        FrameCodec.Decoder decA = new FrameCodec.Decoder();
        FrameCodec.Decoder decB = new FrameCodec.Decoder();

        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> error    = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        BulkSender[]   sxBox = new BulkSender[1];
        BulkReceiver[] rxBox = new BulkReceiver[1];

        PacedTransmitter txB = new PacedTransmitter(frame -> {
            byte[] r = emuRev.pass(frame);
            if (r != null) { decA.feed(r); FrameCodec.Frame f; while ((f = decA.poll()) != null) sxBox[0].onControlFrame(f); }
        });
        PacedTransmitter txA = new PacedTransmitter(frame -> {
            byte[] r = emuFwd.pass(frame);
            if (r != null) { decB.feed(r); FrameCodec.Frame f; while ((f = decB.poll()) != null) rxBox[0].feed(f); }
        });

        rxBox[0] = new BulkReceiver(txB, (kind, name, bytes) -> { received.set(bytes); done.countDown(); }, null);
        sxBox[0] = new BulkSender(txA, cfg(overdrive));

        txA.start();
        txB.start();
        sxBox[0].send(BulkProtocol.KIND_FILE, "test.bin", data,
            null, () -> {}, err -> { error.set(err); done.countDown(); });

        boolean ok = done.await(timeoutSec, TimeUnit.SECONDS);
        txA.stop();
        txB.stop();
        assertNull(error.get(), "передача завершилась ошибкой: " + error.get());
        assertTrue(ok, "передача не завершилась за " + timeoutSec + " с");
        return received.get();
    }

    @Test
    void bulkTransfer_noPacing_withLoss() throws Exception {
        byte[] data = new byte[8 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 37 + 5);
        assertArrayEquals(data, runBulk(data, 0.12, 1000, 60)); // overdrive 1000 → задержка 0
    }

    @Test
    void bulkTransfer_withPacing_withLoss() throws Exception {
        // Реальная задержка ~10 мс: блок ~147 кадров сливается ~1.5 с — дольше старого
        // фиксированного таймаута 1 с. Ловит регресс «прерванные передачи на блоках».
        byte[] data = new byte[8 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 53 + 11);
        assertArrayEquals(data, runBulk(data, 0.10, 21, 90)); // overdrive 21 → задержка 10 мс
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

        msgA[0] = new MsgChannel(txA, t -> {});
        msgB[0] = new MsgChannel(txB, t -> { rxText.set(t); done.countDown(); });

        txA.start();
        txB.start();
        msgA[0].send(text, err -> fail("Ошибка отправки MSG: " + err));

        assertTrue(done.await(30, TimeUnit.SECONDS), "MSG не доставлен за 30 с");
        txA.stop();
        txB.stop();
        assertEquals(text, rxText.get(), "Текст после фрагментации/сборки не совпал");
    }

    // =========================================================================
    // Регресс: отменили файл A, начали B → приёмник не должен смешать (tid)
    // =========================================================================

    @Test
    void bulkReceiver_newTransfer_discardsPrevious_noMix() {
        AtomicReference<String> name = new AtomicReference<>();
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        PacedTransmitter sink = new PacedTransmitter(f -> {}); // NACK/DONE в никуда, поток не стартуем
        BulkReceiver rx = new BulkReceiver(sink, (k, n, b) -> { name.set(n); bytes.set(b); }, null);

        int D = BulkProtocol.DATA_BYTES;
        byte[] a = bytesOf(D + 20, (byte) 0xA1); // файл A (2 кадра)
        byte[] b = bytesOf(D + 20, (byte) 0xB2); // файл B (2 кадра), другое содержимое

        // Передача A (tid=0): начали и оборвали на первом кадре (нет END/FILE_END)
        rx.feed(frame(BulkProtocol.TYPE_FILE_BEGIN, fileBegin(0, BulkProtocol.KIND_FILE, 1, a.length, "a.bin")));
        rx.feed(frame(BulkProtocol.TYPE_DATA, data(0, 0, 0, a, 0, D)));

        // Передача B (tid=1): должна сбросить A и принять начисто
        rx.feed(frame(BulkProtocol.TYPE_FILE_BEGIN, fileBegin(1, BulkProtocol.KIND_FILE, 1, b.length, "b.bin")));
        // Хвост A долетел уже после старта B — обязан игнорироваться (чужой tid)
        rx.feed(frame(BulkProtocol.TYPE_DATA, data(0, 0, 1, a, D, a.length - D)));
        // Полный B
        rx.feed(frame(BulkProtocol.TYPE_DATA, data(1, 0, 0, b, 0, D)));
        rx.feed(frame(BulkProtocol.TYPE_DATA, data(1, 0, 1, b, D, b.length - D)));
        rx.feed(frame(BulkProtocol.TYPE_BLOCK_END, new byte[]{ 1, 0, 2, 0 }));   // tid=1 blk=0 count=2
        rx.feed(frame(BulkProtocol.TYPE_FILE_END, new byte[]{ 1 }));             // tid=1

        assertEquals("b.bin", name.get(), "доставлено имя не того файла");
        assertArrayEquals(b, bytes.get(), "содержимое смешано с отменённым файлом");
    }

    // --- помощники сборки кадров ---

    private static byte[] bytesOf(int n, byte seed) {
        byte[] x = new byte[n];
        for (int i = 0; i < n; i++) x[i] = (byte)(seed + i);
        return x;
    }

    private static FrameCodec.Frame frame(int type, byte[] payload) {
        FrameCodec.Decoder d = new FrameCodec.Decoder();
        d.feed(FrameCodec.encode(0, type, payload));
        return d.poll();
    }

    private static byte[] fileBegin(int tid, int kind, int blocks, int size, String nm) {
        byte[] n = nm.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] p = new byte[9 + n.length];
        p[0] = (byte) tid; p[1] = (byte) kind;
        p[2] = (byte) blocks; p[3] = (byte)(blocks >> 8);
        p[4] = (byte) size; p[5] = (byte)(size >> 8); p[6] = (byte)(size >> 16); p[7] = (byte)(size >> 24);
        p[8] = (byte) n.length;
        System.arraycopy(n, 0, p, 9, n.length);
        return p;
    }

    private static byte[] data(int tid, int blk, int idx, byte[] src, int off, int len) {
        byte[] p = new byte[4 + len];
        p[0] = (byte) tid; p[1] = (byte) blk; p[2] = (byte)(idx & 0xFF); p[3] = (byte)((idx >> 8) & 0xFF);
        System.arraycopy(src, off, p, 4, len);
        return p;
    }
}
