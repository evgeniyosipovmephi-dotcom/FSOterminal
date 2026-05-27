package fsoterminal.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowSenderTest {

    private List<byte[]>       sent;
    private SlidingWindowSender sender;

    @BeforeEach
    void setUp() {
        sent   = new ArrayList<>();
        sender = new SlidingWindowSender(4, sent::add);
    }

    // --- Базовая отправка ---

    @Test
    void send_outputsEncodedFrame() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{0x01, 0x02});

        assertEquals(1, sent.size());
        FrameCodec.Frame f = decode(sent.get(0));
        assertNotNull(f);
        assertEquals(0, f.seq);
        assertEquals(FrameCodec.TYPE_DATA, f.type);
        assertArrayEquals(new byte[]{0x01, 0x02}, f.payload);
    }

    @Test
    void send_seqAutoIncrements() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2});
        sender.send(FrameCodec.TYPE_DATA, new byte[]{3});

        assertEquals(0, decode(sent.get(0)).seq);
        assertEquals(1, decode(sent.get(1)).seq);
        assertEquals(2, decode(sent.get(2)).seq);
    }

    @Test
    void send_consumesCredit() throws InterruptedException {
        assertEquals(4, sender.availableCredits());
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});
        assertEquals(3, sender.availableCredits());
    }

    @Test
    void windowFull_noCreditsLeft() throws InterruptedException {
        for (int i = 0; i < 4; i++)
            sender.send(FrameCodec.TYPE_DATA, new byte[]{(byte) i});

        assertEquals(4, sender.inFlight());
        assertEquals(0, sender.availableCredits());
    }

    @Test
    void trySend_returnsFalseWhenWindowFull() throws InterruptedException {
        for (int i = 0; i < 4; i++)
            sender.send(FrameCodec.TYPE_DATA, new byte[]{(byte) i});

        boolean result = sender.trySend(FrameCodec.TYPE_DATA, new byte[]{99}, 50);
        assertFalse(result);
        assertEquals(4, sent.size()); // пятый кадр не отправлен
    }

    // --- ACK: подтверждение ---

    @Test
    void onAck_allConfirmed_releasesCredits() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1}); // SEQ=0
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2}); // SEQ=1

        sender.onAck(0, 0b0011); // оба получены

        assertEquals(0, sender.inFlight());
        assertEquals(4, sender.availableCredits());
    }

    @Test
    void onAck_allConfirmed_advancesBase() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2});
        sender.send(FrameCodec.TYPE_DATA, new byte[]{3});

        sender.onAck(0, 0b0111);

        assertEquals(3, sender.baseSeq());
        assertEquals(3, sender.nextSeq());
    }

    @Test
    void onAck_partialConfirm_retransmitsMissing() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1}); // SEQ=0
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2}); // SEQ=1
        sender.send(FrameCodec.TYPE_DATA, new byte[]{3}); // SEQ=2

        sent.clear(); // сбрасываем историю отправок

        // SEQ=0 и SEQ=2 получены, SEQ=1 — нет
        sender.onAck(0, 0b0101);

        assertEquals(1, sent.size());
        assertEquals(1, decode(sent.get(0)).seq); // переотправлен SEQ=1
    }

    @Test
    void onAck_noneConfirmed_noRetransmit() throws InterruptedException {
        // bitmap=0 при отсутствии advance означает: кадры ещё в пути (не потеряны).
        // onAck() не должен их перепосылать — это задача таймера (timeout fallback).
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2});

        sent.clear();
        sender.onAck(0, 0b0000); // получатель ничего не видел — кадры в пути

        assertEquals(0, sent.size()); // ретрансмит не нужен — таймер разберётся
    }

    @Test
    void onAck_staleBase_ignored() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});

        sent.clear();
        sender.onAck(99, 0b0001); // чужой base

        assertEquals(1, sender.inFlight()); // окно не сдвинулось
        assertEquals(0, sent.size());       // ретрансмитов не было
    }

    // --- Ретрансмит по таймауту ---

    @Test
    void retransmitUnconfirmed_resendAllInFlight() throws InterruptedException {
        sender.send(FrameCodec.TYPE_DATA, new byte[]{1});
        sender.send(FrameCodec.TYPE_DATA, new byte[]{2});

        sent.clear();
        sender.retransmitUnconfirmed();

        assertEquals(2, sent.size());
        assertEquals(0, decode(sent.get(0)).seq);
        assertEquals(1, decode(sent.get(1)).seq);
    }

    @Test
    void retransmitUnconfirmed_emptyWindow_sendsNothing() {
        sender.retransmitUnconfirmed();
        assertEquals(0, sent.size());
    }

    // --- Оборачивание SEQ ---

    @Test
    void seq_wrapsAt256() throws InterruptedException {
        SlidingWindowSender s = new SlidingWindowSender(4, f -> {});
        for (int i = 0; i < 256; i++) {
            s.send(FrameCodec.TYPE_DATA, new byte[]{0});
            s.onAck(i & 0xFF, 0b0001);
        }
        assertEquals(0, s.nextSeq()); // 256 & 0xFF == 0
    }

    @Test
    void windowSize1_worksCorrectly() throws InterruptedException {
        SlidingWindowSender s = new SlidingWindowSender(1, sent::add);
        s.send(FrameCodec.TYPE_DATA, new byte[]{42});
        assertEquals(0, s.availableCredits());

        s.onAck(0, 0b0001);
        assertEquals(1, s.availableCredits());
        assertEquals(1, s.nextSeq());
    }

    // --- Вспомогательный метод ---

    private FrameCodec.Frame decode(byte[] raw) {
        FrameCodec.Decoder dec = new FrameCodec.Decoder();
        dec.feed(raw);
        return dec.poll();
    }
}
