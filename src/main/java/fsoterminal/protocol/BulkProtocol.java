package fsoterminal.protocol;

/**
 * Константы и расчёты bulk-протокола передачи файлов.
 * См. docs/BULK_PROTOCOL_DESIGN.md.
 *
 * Все DATA-кадры — ровно 64 байта на проводе (1 USB-кусок). Это единственный
 * размер, который можно «передавливать» (over-drive) без каскадных потерь.
 */
public final class BulkProtocol {

    private BulkProtocol() {}

    // ── Вид содержимого (байт kind в FILE_BEGIN) ───────────────────────────────
    public static final int KIND_FILE  = 0; // обычный файл
    public static final int KIND_VOICE = 1; // голосовое сообщение
    public static final int KIND_IMAGE = 2; // изображение (превью в чате)

    // ── Типы кадров (диапазон 0x30–0x39) ───────────────────────────────────────
    public static final int TYPE_FILE_BEGIN  = 0x30; // [kind 1][blocks 2][size 4][nameLen 1][name…]
    public static final int TYPE_BLOCK_BEGIN = 0x31; // [blk 1][count 2]
    public static final int TYPE_DATA        = 0x32; // [blk 1][idx 2][data ≤56]
    public static final int TYPE_BLOCK_END   = 0x33; // [blk 1][count 2]
    public static final int TYPE_NACK        = 0x34; // [blk 1][count 2][bitmap: бит=0 → потерян]
    public static final int TYPE_BLOCK_DONE  = 0x35; // [blk 1]
    public static final int TYPE_FILE_END    = 0x36; // []
    public static final int TYPE_MSG         = 0x38; // [msg_id 1][flag 1][текст ≤57]
    public static final int TYPE_MSG_ACK     = 0x39; // [msg_id 1]

    // MSG flag
    public static final int MSG_MORE = 0xFF; // ещё фрагмент следует
    public static final int MSG_LAST = 0x00; // последний (или единственный) фрагмент

    // ── Размеры ────────────────────────────────────────────────────────────────
    public static final int FSO_BAUD     = 24_000; // бод (8N1 → 2400 байт/с)
    public static final int DATA_BYTES   = 56;     // данных в DATA-кадре → кадр 64 Б
    public static final int BLOCK_FRAMES = 400;    // кадров в блоке (NACK = 1 USB-кусок)
    public static final int MSG_BYTES    = 57;     // текста в одном MSG-кадре

    /** Длина DATA-кадра на проводе для dataLen байт данных. */
    public static int dataFrameLen(int dataLen) {
        // HEADER(4) + [blk 1 + idx 2 + data] + CRC(1)
        return FrameCodec.HEADER + 1 + 2 + dataLen + FrameCodec.TRAILER;
    }

    /**
     * Задержка между DATA-кадрами с учётом упаковки PtPP2 по USB-кускам ≤64 Б.
     * Пол = время передачи кадра по FSO; над-драйв вычитается только для 1-кускового
     * кадра (≤64 Б) — большой кадр передавливать нельзя (каскад).
     *
     * @param frameLen    длина кадра на проводе, Б
     * @param overdriveMs насколько слать быстрее пола, мс
     */
    public static long pacingMs(int frameLen, int overdriveMs) {
        int  chunks   = (frameLen + 63) / 64;          // USB-кусков ≤64 Б
        int  fsoBytes = frameLen + 10 * chunks;        // PtPP2 +10 Б на каждый кусок
        long floorMs  = (long) Math.ceil(fsoBytes * 10.0 / FSO_BAUD * 1000.0);
        if (chunks > 1) return (long) Math.ceil(floorMs * 1.10); // большой кадр — безопасно
        return Math.max(0, floorMs - overdriveMs);
    }
}
