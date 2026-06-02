package fsoterminal.protocol;

import java.nio.charset.StandardCharsets;
import java.util.function.DoubleConsumer;

/**
 * Приёмник файла блочным ARQ. См. docs/BULK_PROTOCOL_DESIGN.md.
 * Чистая Java, без JavaFX. Не потокобезопасен — feed() вызывать из одного потока
 * (потока-слушателя канала).
 *
 * Управляющие кадры (NACK/BLOCK_DONE) отправляются через переданный
 * {@link PacedTransmitter} с высоким приоритетом (sendNow).
 */
public class BulkReceiver {

    /** Колбэк завершённого приёма: kind (KIND_*), имя, содержимое. */
    @FunctionalInterface
    public interface FileHandler { void onFile(int kind, String name, byte[] data); }

    private final PacedTransmitter tx;
    private final FileHandler      onComplete;
    private final DoubleConsumer   progress;     // доля 0..1, может быть null

    private boolean   active = false;
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

    /** Скормить декодированный кадр. Чужие типы молча игнорируются. */
    public void feed(FrameCodec.Frame fr) {
        switch (fr.type) {
            case BulkProtocol.TYPE_FILE_BEGIN -> onFileBegin(fr.payload);
            case BulkProtocol.TYPE_DATA       -> onData(fr.payload);
            case BulkProtocol.TYPE_BLOCK_END  -> onBlockEnd(fr.payload);
            case BulkProtocol.TYPE_FILE_END   -> onFileEnd();
            default -> { /* не наш кадр */ }
        }
    }

    private void onFileBegin(byte[] p) {
        if (p.length < 8) return;
        if (active) return;                          // дубликат FILE_BEGIN — игнор
        int kind   = p[0] & 0xFF;
        int blocks = (p[1] & 0xFF) | ((p[2] & 0xFF) << 8);
        int size   = (p[3] & 0xFF) | ((p[4] & 0xFF) << 8)
                   | ((p[5] & 0xFF) << 16) | ((p[6] & 0xFF) << 24);
        int nmLen  = p[7] & 0xFF;
        if (size < 0 || nmLen > p.length - 8) return;
        fileKind    = kind;
        fileName    = new String(p, 8, Math.min(nmLen, p.length - 8), StandardCharsets.UTF_8);
        fileSize    = size;
        numBlocks   = Math.max(1, blocks);
        totalFrames = (int) Math.ceil((double) size / BulkProtocol.DATA_BYTES);
        fileBuf     = new byte[size];
        gotIt       = new boolean[Math.max(1, totalFrames)];
        curBlock    = 0;
        active      = true;
    }

    private void onData(byte[] p) {
        if (!active || p.length < 3) return;
        int blk = p[0] & 0xFF;
        int idx = (p[1] & 0xFF) | ((p[2] & 0xFF) << 8);
        maybeAdvance(blk);
        if (blk != (curBlock & 0xFF)) return;        // кадр чужого блока
        int abs = curBlock * BulkProtocol.BLOCK_FRAMES + idx;
        if (abs >= totalFrames || gotIt[abs]) return;
        gotIt[abs] = true;
        int off = abs * BulkProtocol.DATA_BYTES;
        int len = Math.min(BulkProtocol.DATA_BYTES, fileSize - off);
        System.arraycopy(p, 3, fileBuf, off, Math.min(len, p.length - 3));
    }

    private void onBlockEnd(byte[] p) {
        if (!active || p.length < 3) return;
        int blk   = p[0] & 0xFF;
        int count = (p[1] & 0xFF) | ((p[2] & 0xFF) << 8);
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
            tx.sendNow(FrameCodec.encode(0, BulkProtocol.TYPE_BLOCK_DONE, new byte[]{ (byte) blk }));
            if (progress != null) progress.accept(Math.min(1.0, (double)(curBlock + 1) / numBlocks));
        } else {
            byte[] resp = new byte[3 + bmLen];
            resp[0] = (byte) blk;
            resp[1] = (byte)(count & 0xFF);
            resp[2] = (byte)((count >> 8) & 0xFF);
            System.arraycopy(bm, 0, resp, 3, bmLen);
            tx.sendNow(FrameCodec.encode(0, BulkProtocol.TYPE_NACK, resp));
        }
    }

    private void onFileEnd() {
        if (!active) return;
        active = false;
        if (onComplete != null) onComplete.onFile(fileKind, fileName, fileBuf);
    }

    /** Если пришёл кадр следующего блока — отправитель завершил текущий, продвигаемся. */
    private void maybeAdvance(int blk) {
        if (blk == ((curBlock + 1) & 0xFF) && curBlock + 1 < numBlocks) curBlock++;
    }
}
