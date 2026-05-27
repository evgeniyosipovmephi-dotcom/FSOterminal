package fsoterminal.protocol;

/**
 * Конфигурация протокола FSO Terminal.
 * Параметры читаются из настроек (Preferences) при старте
 * и применяются при каждом подключении к COM-порту.
 */
public class ProtocolConfig {

    /** Размер скользящего окна (1–16 кадров). */
    public int windowSize = 8;

    /** Интервал ретрансмиссии неподтверждённых кадров, мс. */
    public int retransmitIntervalMs = 400;

    /** Интервал отправки PROBE-запросов, сек. */
    public int probeIntervalSec = 5;

    /** Количество пропущенных ответов до разрыва (таймаут = probeIntervalSec × probeMaxMiss). */
    public int probeMaxMiss = 3;

    /**
     * Папка для сохранения принятых файлов.
     * null или пустая строка → ~/Downloads (или ~/если Downloads нет).
     */
    public String downloadPath = null;

    public ProtocolConfig() {}
}
