package fsoterminal.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FSOEmulatorTest {

    private static final byte[] FRAME = {0x7E, 0x01, 0x02, 0x03};

    // --- Граничные значения потерь ---

    @Test
    void pass_zeroLoss_neverDrops() {
        FSOEmulator em = new FSOEmulator(0.0, 42);
        for (int i = 0; i < 100; i++)
            assertNotNull(em.pass(FRAME));
    }

    @Test
    void pass_fullLoss_alwaysDrops() {
        FSOEmulator em = new FSOEmulator(1.0, 42);
        for (int i = 0; i < 100; i++)
            assertNull(em.pass(FRAME));
    }

    // --- Детерминизм ---

    @Test
    void pass_sameSeed_sameResults() {
        FSOEmulator em1 = new FSOEmulator(0.3, 123);
        FSOEmulator em2 = new FSOEmulator(0.3, 123);

        for (int i = 0; i < 50; i++) {
            boolean dropped1 = (em1.pass(FRAME) == null);
            boolean dropped2 = (em2.pass(FRAME) == null);
            assertEquals(dropped1, dropped2, "Расхождение на шаге " + i);
        }
    }

    // --- Приближённая вероятность потерь ---

    @Test
    void pass_approximateLossRate_10percent() {
        FSOEmulator em = new FSOEmulator(0.1, 0);
        int dropped = 0;
        int total   = 2000;

        for (int i = 0; i < total; i++) {
            if (em.pass(FRAME) == null) dropped++;
        }

        // Ожидаем ~10% ± 3% (6..14% — вероятность ошибки пренебрежимо мала)
        double rate = (double) dropped / total;
        assertTrue(rate >= 0.06 && rate <= 0.14,
            "lossRate=0.1 but actual=" + rate);
    }

    // --- Burst-потери ---

    @Test
    void pass_burstSize3_threeConsecutiveDrops() {
        // lossRate=1.0 → burst срабатывает каждый раз
        FSOEmulator em = new FSOEmulator(1.0, 3, 42);

        // Первые 3 кадра должны быть потеряны как один burst
        assertNull(em.pass(FRAME));
        assertNull(em.pass(FRAME));
        assertNull(em.pass(FRAME));
        // Четвёртый — начало следующего burst (lossRate=1.0)
        assertNull(em.pass(FRAME));
    }

    @Test
    void pass_burstSize1_individualLosses() {
        // burstSize=1 — обычные одиночные потери (поведение по умолчанию)
        FSOEmulator em = new FSOEmulator(0.5, 1, 0);
        int drops = 0;
        int passes = 0;

        for (int i = 0; i < 200; i++) {
            if (em.pass(FRAME) == null) drops++;
            else passes++;
        }

        // Оба варианта должны встретиться
        assertTrue(drops > 0,  "Не было потерь при lossRate=0.5");
        assertTrue(passes > 0, "Не было пропусков при lossRate=0.5");
    }

    // --- Проверка настроек ---

    @Test
    void setLossRate_changesRate() {
        FSOEmulator em = new FSOEmulator(0.0, 42);
        for (int i = 0; i < 20; i++) assertNotNull(em.pass(FRAME)); // без потерь

        em.setLossRate(1.0);
        assertNull(em.pass(FRAME)); // теперь всё теряется
    }

    // --- Некорректные аргументы ---

    @Test
    void constructor_negativeLossRate_throws() {
        assertThrows(IllegalArgumentException.class, () -> new FSOEmulator(-0.1, 0));
    }

    @Test
    void constructor_lossRateOver1_throws() {
        assertThrows(IllegalArgumentException.class, () -> new FSOEmulator(1.1, 0));
    }

    @Test
    void constructor_zeroBurstSize_throws() {
        assertThrows(IllegalArgumentException.class, () -> new FSOEmulator(0.1, 0, 0));
    }
}
