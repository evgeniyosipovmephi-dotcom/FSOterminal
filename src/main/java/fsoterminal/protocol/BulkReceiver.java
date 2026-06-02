package fsoterminal.protocol;

import java.nio.charset.StandardCharsets;
import java.util.function.DoubleConsumer;

/**
 * Приёмник файла блочным ARQ. См. docs/BULK_PROTOCOL_DESIGN.md.
 * Чистая Java, без JavaFX. Не потокобезопасен — feed() вызывать из одного потока.
 *
 * tid (transfer-id) защищает от смешивания передач: новый tid в FILE_BEGIN сбрасывает
 * недопринятый файл и начинает свежий; кадры с чужим tid игнорируются (хвосты отменённой
 * передачи безвредны).
 *
 * Управляющие кадры (NACK/BLOCK_DONE) отправляются через PacedTransmitter с высоким
 * приоритетом (sendNow).
 */
public class BulkReceiver {

    /** Колбэк завершённого приёма: kind (KIND_*), имя, содержимое. */
    @FunctionalInterface
    public interface FileHandler { void onFile(int kind, String name, byte[] data); }

    /** Колбэк начала приёма (создать пузырь прогресса): kind, имя, размер. */
    @FunctionalInterface
    public interface BeginHandler { void onBegin(int kind, String name, int size); }

    private final PacedTransmitter tx;
    private final FileHandler      onComplete;
    private final DoubleConsumer   progress;     // доля 0..1, может быть null
    private BeginHandler           onBegin;      // опционально, через setOnBegin

    private boolean   active = false;
    private int       curTid = -1;
    private int       fileKind;
    private String    fileName;
    private int       fileSize;
    private int       totalFrames;
    private int       numBlocks;
    private byte[]    fileBuf;
    private boolean[] gotIt;
    private int       curBlock;   // абсолютный номер текущего блока

    public BulkReceiver(PacedTransmitter tx, FileHandler onComplete, DoubleConsumer progress) {
        this.tx         = tx;
        this.onComplete = onComplete;
        this.progress   = progress;
    }

    /** Установить колбэк начала приёма (создание пузыря). */
    public void setOnBegin(BeginHandler h) { this.onBegin = h; }

    /** Скормить декодированный кадр. Чужие типы молча игнорируются. */
    public void feed(FrameCodec.Frame fr) {
        switch (fr.type) {
            case BulkProtocol.TYPE_FILE_BEGIN -> onFileBegin(fr.payload);
            case BulkProtocol.TYPE_DATA       -> onData(fr.payload);
            case BulkProtocol.TYPE_BLOCK_END  -> onBlockEnd(fr.payload);
            case BulkProtocol.TYPE_FILE_END   -> onFileEnd(fr.payload);
            default -> { /* не наш кадр */ }
        }
    }

    private void onFileBegin(byte[] p) {
        if (p.length < 9) return;
        int tid = p[0] & 0xFF;
        if (active && tid == curTid) return;         // дубликат FILE_BEGIN той же передачи
        // Новый tid (или мы не были активны) → начинаем свежий приём, прежний бросаем.
        int kind   = p[1] & 0xFF;
        int blocks = (p[2] & 0xFF) | ((p[3] & 0xFF) << 8);
        int size   = (p[4] & 0xFF) | ((p[5] & 0xFF) << 8)
                   | ((p[6] & 0xFF) << 16) | ((p[7] & 0xFF) << 24);
        int nmLen  = p[8] & 0xFF;
        if (size < 0 || nmLen > p.length - 9) return;
        curTid      = tid;
        fileKind    = kind;
        fileName    = new String(p, 9, Math.min(nmLen, p.length - 9), StandardCharsets.UTF_8);
        fileSize    = size;
        numBlocks   = Math.max(1, blocks);
        totalFrames = (int) Math.ceil((double) size / BulkProtocol.DATA_BYTES);
        fileBuf     = new byte[size];
        gotIt       = new boolean[Math.max(1, totalFrames)];
        curBlock    = 0;
        active      = true;
        if (onBegin != null) onBegin.onBegin(fileKind, fileName, fileSize);
    }

    private void onData(byte[] p) {
        if (!active || p.length < 4 || (p[0] & 0xFF) != curTid) return;
        int blk = p[1] & 0xFF;
        int idx = (p[2] & 0xFF) | ((p[3] & 0xFF) << 8);
        maybeAdvance(blk);
        if (blk != (curBlock & 0xFF)) return;        // кадр чужого блока
        int abs = curBlock * BulkProtocol.BLOCK_FRAMES + idx;
        if (abs >= totalFrames || gotIt[abs]) return;
        gotIt[abs] = true;
        int off = abs * BulkProtocol.DATA_BYTES;
        int len = Math.min(BulkProtocol.DATA_BYTES, fileSize - off);
        System.arraycopy(p, 4, fileBuf, off, Math.min(len, p.length - 4));
    }

    private void onBlockEnd(byte[] p) {
        if (!active || p.length < 4 || (p[0] & 0xFF) != curTid) return;
        int blk   = p[1] & 0xFF;
        int count = (p[2] & 0xFF) | ((p[3] & 0xFF) << 8);
        maybeAdvance(blk);
        if (blk != (curBlock & 0xFF)) return;        // END чужого блока
        int base  = curBlock * BulkProtocol.BLOCK_FRAMES;
        int bmLen = (count + 7) / 8;
        byte[] bm = new byte[bmLen];
        int missing = 0;
        for (int i = 0; i < count; i++) {
            if (base + i < totalFrames && gotIt[base + i]) bm[i / 8] |= (byte)(1 << (i % 8));
            else missing++;
        }
        if (missing == 0) {
            tx.sendNow(FrameCodec.encode(0, BulkProtocol.TYPE_BLOCK_DONE,
                new byte[]{ (byte) curTid, (byte) blk }));
            if (progress != null) progress.accept(Math.min(1.0, (double)(curBlock + 1) / numBlocks));
        } else {
            byte[] resp = new byte[4 + bmLen];
            resp[0] = (byte) curTid;
            resp[1] = (byte) blk;
            resp[2] = (byte)(count & 0xFF);
            resp[3] = (byte)((count >> 8) & 0xFF);
            System.arraycopy(bm, 0, resp, 4, bmLen);
            tx.sendNow(FrameCodec.encode(0, BulkProtocol.TYPE_NACK, resp));
        }
    }

    private void onFileEnd(byte[] p) {
        if (!active || p.length < 1 || (p[0] & 0xFF) != curTid) return;
        active = false;
        if (onComplete != null) onComplete.onFile(fileKind, fileName, fileBuf);
    }

    /** Если пришёл кадр следующего блока — отправитель завершил текущий, продвигаемся. */
    private void maybeAdvance(int blk) {
        if (blk == ((curBlock + 1) & 0xFF) && curBlock + 1 < numBlocks) curBlock++;
    }
}
