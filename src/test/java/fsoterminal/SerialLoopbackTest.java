package fsoterminal;

import fsoterminal.channel.SerialChannel;
import fsoterminal.protocol.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты с реальными COM-портами (FSO 24 кбод) — на прод-классах
 * bulk/MSG (BulkSender / BulkReceiver / MsgChannel / PacedTransmitter).
 *
 * Запуск:
 *   gradlew test --tests "*SerialLoopbackTest*" --rerun-tasks
 *     -Dserial.test.enabled=true -Dserial.port.a=COM22 -Dserial.port.b=COM25
 *     [-Dtest.bytes=262144] [-Dbulk.overdrive=11]
 *
 * Топология: A↔B через STM32-мосты (USB-CDC ↔ FSO ↔ USB-CDC).
 */
@EnabledIfSystemProperty(named = "serial.test.enabled", matches = "true")
class SerialLoopbackTest {

    private static final String PORT_A     = System.getProperty("serial.port.a", "COM10");
    private static final String PORT_B     = System.getProperty("serial.port.b", "COM11");
    private static final int    BAUD       = 115200;
    private static final int    TEST_BYTES = Integer.getInteger("test.bytes", 100 * 1024);
    private static final int    OVERDRIVE  = Integer.getInteger("bulk.overdrive", 11);

    /** Полный протокольный стек одного узла. */
    private static final class Peer {
        SerialChannel       ch;
        PacedTransmitter    tx;
        BulkSender          bulkSender;
        BulkReceiver        bulkReceiver;
        MsgChannel          msgChannel;
        final FrameCodec.Decoder dec = new FrameCodec.Decoder();

        final AtomicReference<byte[]>  rxFile = new AtomicReference<>();
        final AtomicReference<String>  rxText = new AtomicReference<>();
        volatile CountDownLatch fileLatch;
        volatile CountDownLatch textLatch;
    }

    private Peer A, B;

    @BeforeEach
    void setUp() {
        A = makePeer();
        B = makePeer();
        assertTrue(A.ch.open(PORT_A, BAUD), "Не удалось открыть " + PORT_A);
        assertTrue(B.ch.open(PORT_B, BAUD), "Не удалось открыть " + PORT_B);
        A.tx.start();
        B.tx.start();
    }

    @AfterEach
    void tearDown() {
        if (A != null) { A.tx.stop(); A.ch.close(); }
        if (B != null) { B.tx.stop(); B.ch.close(); }
    }

    private Peer makePeer() {
        Peer p = new Peer();
        ProtocolConfig cfg = new ProtocolConfig();
        cfg.bulkOverdriveMs = OVERDRIVE;

        p.ch = new SerialChannel();
        p.tx = new PacedTransmitter(p.ch::send);
        p.bulkSender = new BulkSender(p.tx, cfg);
        p.bulkReceiver = new BulkReceiver(p.tx,
            (kind, name, data) -> { p.rxFile.set(data); if (p.fileLatch != null) p.fileLatch.countDown(); },
            null);
        p.msgChannel = new MsgChannel(p.tx,
            text -> { p.rxText.set(text); if (p.textLatch != null) p.textLatch.countDown(); });

        p.ch.setReceiveHandler(raw -> {
            p.dec.feed(raw);
            FrameCodec.Frame f;
            while ((f = p.dec.poll()) != null) route(p, f);
        });
        return p;
    }

    private void route(Peer p, FrameCodec.Frame f) {
        switch (f.type) {
            case BulkProtocol.TYPE_MSG, BulkProtocol.TYPE_MSG_ACK -> p.msgChannel.feed(f);
            case BulkProtocol.TYPE_FILE_BEGIN, BulkProtocol.TYPE_DATA,
                 BulkProtocol.TYPE_BLOCK_END,  BulkProtocol.TYPE_FILE_END -> p.bulkReceiver.feed(f);
            case BulkProtocol.TYPE_NACK, BulkProtocol.TYPE_BLOCK_DONE -> p.bulkSender.onControlFrame(f);
            default -> { /* PROBE и пр. в тесте не нужны */ }
        }
    }

    // =========================================================================
    // Передача файла (bulk)
    // =========================================================================

    @Test void file_AtoB() throws Exception { transferFile(A, B, "A→B"); }
    @Test void file_BtoA() throws Exception { transferFile(B, A, "B→A"); }

    private void transferFile(Peer from, Peer to, String tag) throws Exception {
        byte[] data = new byte[TEST_BYTES];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 71 + 13);

        to.fileLatch = new CountDownLatch(1);
        AtomicReference<String> err = new AtomicReference<>();

        long t0 = System.currentTimeMillis();
        from.bulkSender.send(BulkProtocol.KIND_FILE, "test.bin", data,
            null, () -> {}, e -> { err.set(e); to.fileLatch.countDown(); });

        boolean ok = to.fileLatch.await(180, TimeUnit.SECONDS);
        long dt = System.currentTimeMillis() - t0;

        assertNull(err.get(), "Передача завершилась ошибкой: " + err.get());
        assertTrue(ok, "Файл не принят за 180 с");
        System.out.printf("  [%s] %d Б за %.1f с = %.0f байт/с%n",
            tag, data.length, dt / 1000.0, data.length / (dt / 1000.0));
        assertArrayEquals(data, to.rxFile.get(), "Файл повреждён (" + tag + ")");
    }

    // =========================================================================
    // Передача текста (MSG)
    // =========================================================================

    @Test void msg_AtoB() throws Exception {
        transferMsg(A, B, "Привет A→B! Длинное сообщение для проверки фрагментации. ".repeat(4));
    }
    @Test void msg_BtoA() throws Exception {
        transferMsg(B, A, "Короткий ответ B→A");
    }

    private void transferMsg(Peer from, Peer to, String text) throws Exception {
        to.textLatch = new CountDownLatch(1);
        AtomicReference<String> err = new AtomicReference<>();
        from.msgChannel.send(text, e -> { err.set(e); to.textLatch.countDown(); });

        boolean ok = to.textLatch.await(30, TimeUnit.SECONDS);
        assertNull(err.get(), "MSG завершился ошибкой: " + err.get());
        assertTrue(ok, "Текст не принят за 30 с");
        assertEquals(text, to.rxText.get(), "Текст не совпал");
    }
}
