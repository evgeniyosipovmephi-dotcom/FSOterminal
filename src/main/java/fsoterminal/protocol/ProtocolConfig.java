package fsoterminal.protocol;

/**
 * Конфигурация протокола FSO Terminal.
 * Параметры читаются из настроек (Preferences) при старте
 * и применяются при каждом подключении к COM-порту.
 */
public class ProtocolConfig {

    /** Интервал отправки PROBE-запросов, сек. */
    public int probeIntervalSec = 5;

    /** Количество пропущенных ответов до разрыва (таймаут = probeIntervalSec × probeMaxMiss). */
    public int probeMaxMiss = 3;

    /**
     * Папка для сохранения принятых файлов.
     * null или пустая строка → ~/Downloads (или ~/если Downloads нет).
     */
    public String downloadPath = null;

    // ── Bulk-протокол (передача файлов, см. docs/BULK_PROTOCOL_DESIGN.md) ───────

    /**
     * Over-drive: насколько слать кадры быстрее физического пола FSO, мс.
     * delay = floor − overdrive. Для кадра 64 Б floor = 31 мс, overdrive 11 → 20 мс
     * (подтверждённый замерами оптимум throughput). 0 = безопасно по полу.
     */
    public int bulkOverdriveMs = 11;

    public ProtocolConfig() {}
}
