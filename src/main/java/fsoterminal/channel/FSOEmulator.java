package fsoterminal.channel;

import java.util.Random;

/**
 * Эмулятор FSO-канала: имитирует случайные потери кадров.
 *
 * Один экземпляр — одно направление (A→B или B→A).
 * Для двунаправленного канала создай два экземпляра.
 *
 * Детерминированный (seed) — тесты воспроизводимы.
 */
public class FSOEmulator {

    private double lossRate;
    private int    burstSize;      // сколько кадров подряд теряется при одной «аварии»
    private final  Random rng;

    private int burstRemaining = 0; // оставшихся потерь в текущей серии

    public FSOEmulator(double lossRate, long seed) {
        this(lossRate, 1, seed);
    }

    public FSOEmulator(double lossRate, int burstSize, long seed) {
        if (lossRate < 0 || lossRate > 1)
            throw new IllegalArgumentException("lossRate: 0..1");
        if (burstSize < 1)
            throw new IllegalArgumentException("burstSize >= 1");
        this.lossRate  = lossRate;
        this.burstSize = burstSize;
        this.rng       = new Random(seed);
    }

    public void setLossRate(double rate) { this.lossRate = rate; }
    public void setBurstSize(int size)   { this.burstSize = size; }

    public double getLossRate()  { return lossRate;  }
    public int    getBurstSize() { return burstSize; }

    /**
     * Пропустить кадр через эмулируемый канал.
     *
     * @return тот же frame если кадр прошёл, null если потерян
     */
    public byte[] pass(byte[] frame) {
        if (burstRemaining > 0) {
            burstRemaining--;
            return null;
        }
        if (rng.nextDouble() < lossRate) {
            burstRemaining = burstSize - 1;
            return null;
        }
        return frame;
    }
}
