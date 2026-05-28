package fsoterminal.protocol;

/**
 * Кодирует и декодирует кадры протокола FSO Terminal.
 *
 * Формат кадра:
 *   [SOF 0xAA] [SEQ 1B] [TYPE 1B] [LEN 1B] [PAYLOAD 0..250B] [CRC8 1B]
 *
 * LEN — длина PAYLOAD в байтах (0..250).
 * CRC-8 (poly 0x07) считается над SEQ+TYPE+LEN+PAYLOAD.
 * При сбое синхронизации декодер сканирует вперёд до следующего SOF.
 */
public class FrameCodec {

    public static final byte SOF       = (byte) 0xAA; // 0x7E → 0xAA: PtPP2 не стаффирует 0xAA
    public static final int  HEADER    = 4;   // SOF + SEQ + TYPE + LEN
    public static final int  TRAILER   = 1;   // CRC8
    public static final int  MAX_PAYLOAD = 240; // оптимум по sweep-тесту; кадр=245 байт < лимит STM32 (255)
    public static final int  MAX_FRAME   = HEADER + MAX_PAYLOAD + TRAILER; // 245

    // Типы кадров
    public static final int TYPE_DATA       = 0x01;
    public static final int TYPE_ACK        = 0x02;
    public static final int TYPE_PROBE      = 0x03;
    public static final int TYPE_PROBE_RESP = 0x04;
    public static final int TYPE_FILE_BEGIN   = 0x10;
    public static final int TYPE_FILE_END    = 0x11;
    public static final int TYPE_FILE_DATA   = 0x12;
    public static final int TYPE_FILE_CANCEL = 0x13; // отправитель отменил передачу
    public static final int TYPE_VOICE       = 0x20;

    /** Декодированный кадр. */
    public static final class Frame {
        public final int    seq;
        public final int    type;
        public final byte[] payload;

        Frame(int seq, int type, byte[] payload) {
            this.seq     = seq;
            this.type    = type;
            this.payload = payload;
        }
    }

    // -------------------------------------------------------------------------
    // Кодирование
    // -------------------------------------------------------------------------

    /**
     * Упаковывает данные в кадр.
     *
     * @param seq     порядковый номер (0..255, старшие биты игнорируются)
     * @param type    тип кадра (TYPE_*)
     * @param payload полезная нагрузка; длина 0..MAX_PAYLOAD
     * @return готовый байтовый кадр
     * @throws IllegalArgumentException если payload превышает MAX_PAYLOAD
     */
    public static byte[] encode(int seq, int type, byte[] payload) {
        if (payload == null) payload = new byte[0];
        if (payload.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("payload too large: " + payload.length);

        int len = payload.length;
        byte[] frame = new byte[HEADER + len + TRAILER];

        frame[0] = SOF;
        frame[1] = (byte) (seq  & 0xFF);
        frame[2] = (byte) (type & 0xFF);
        frame[3] = (byte) (len  & 0xFF);
        System.arraycopy(payload, 0, frame, HEADER, len);

        // CRC считается с позиции 1 (SEQ) по конец PAYLOAD
        int crc = crc8(frame, 1, HEADER - 1 + len);
        frame[HEADER + len] = (byte) crc;

        return frame;
    }

    // -------------------------------------------------------------------------
    // Декодирование (stateful)
    // -------------------------------------------------------------------------

    /**
     * Буферизованный декодер. Накапливает байты из канала и возвращает
     * полные кадры по мере их получения.
     *
     * Использование:
     *   Decoder dec = new Decoder();
     *   dec.feed(receivedBytes, offset, length);
     *   Frame f;
     *   while ((f = dec.poll()) != null) { ... }
     */
    public static final class Decoder {

        private final byte[] buf = new byte[MAX_FRAME * 2]; // двойной запас
        private int head = 0; // индекс первого занятого байта
        private int tail = 0; // индекс следующей свободной позиции

        /** Добавить принятые байты в буфер декодера. */
        public void feed(byte[] data, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                if (tail - head >= buf.length)
                    head++; // переполнение — теряем старый байт
                buf[tail++ % buf.length] = data[i];
            }
        }

        /** Добавить весь массив. */
        public void feed(byte[] data) {
            feed(data, 0, data.length);
        }

        /**
         * Попытаться извлечь следующий корректный кадр из буфера.
         * Возвращает null, если данных ещё недостаточно.
         */
        public Frame poll() {
            while (available() >= HEADER + TRAILER) {

                // Ищем SOF
                if (peek(0) != (SOF & 0xFF)) {
                    consume(1);
                    continue;
                }

                int len = peek(3);
                int frameLen = HEADER + len + TRAILER;

                if (len > MAX_PAYLOAD) {
                    // невозможная длина — это не SOF, сдвигаемся
                    consume(1);
                    continue;
                }

                if (available() < frameLen)
                    return null; // ждём больше данных

                // Проверяем CRC по «сырым» байтам из кольцевого буфера
                byte[] raw = copyOut(frameLen);
                int crcCalc     = crc8(raw, 1, HEADER - 1 + len);
                int crcReceived = raw[HEADER + len] & 0xFF;

                if (crcCalc != crcReceived) {
                    // CRC не совпал — это не настоящий SOF, ищем следующий
                    consume(1);
                    continue;
                }

                consume(frameLen);

                byte[] payload = new byte[len];
                System.arraycopy(raw, HEADER, payload, 0, len);
                return new Frame(raw[1] & 0xFF, raw[2] & 0xFF, payload);
            }
            return null;
        }

        private int available() { return tail - head; }

        private int peek(int offset) {
            return buf[(head + offset) % buf.length] & 0xFF;
        }

        private void consume(int n) { head += n; }

        private byte[] copyOut(int n) {
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++)
                out[i] = buf[(head + i) % buf.length];
            return out;
        }
    }

    // -------------------------------------------------------------------------
    // CRC-8 (poly 0x07, init 0x00)
    // -------------------------------------------------------------------------

    static int crc8(byte[] data, int offset, int length) {
        int crc = 0x00;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xFF;
            for (int j = 0; j < 8; j++)
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1;
        }
        return crc & 0xFF;
    }
}
