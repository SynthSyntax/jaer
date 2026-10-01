package ncslab.serial;

/**
 * Decoder for the PSGX320 / eEBV serial stream (Prophesee GenX320 on STM32).
 * No I/O; feed it the bytes read from the port.
 * <p>
 * The stream mixes text replies and binary words. At a word boundary a byte
 * with the top bit clear starts a text line that runs to LF; a byte with the
 * top bit set starts a 4-byte big-endian word:
 *
 * <pre>
 * bit 31      always 1
 * bits 30-19  timestamp, microseconds, wraps every 4096 us
 * bit 18      polarity
 * bits 17-9   x [0..319]
 * bits 8-0    y [0..319]
 * </pre>
 *
 * x bits 17-15 = 101 marks an exception / status word, 110 an IMU channel
 * word, 111 is reserved.
 */
public final class PsGx320Parser {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 320;

    /** Device timestamp wrap period in microseconds. */
    public static final int WRAP_US = 4096;

    /** Low 19 bits of the word: polarity, x, y. Used as the jAER raw address. */
    public static final int ADDRESS_MASK = 0x7FFFF;
    public static final int YMASK = 0x1FF;
    public static final int YSHIFT = 0;
    public static final int XMASK = 0x1FF << 9;
    public static final int XSHIFT = 9;
    public static final int POLMASK = 1 << 18;
    public static final int POLSHIFT = 18;

    /** Exception identifiers (bits 13-12 of an exception word). */
    public static final int EXC_TIMESTAMP_OVERRUN = 0;
    public static final int EXC_DCMI_DROP = 1;
    public static final int EXC_SPI_USB_DROP = 2;

    /** IMU channel ids (bits 14-12 of an IMU word). */
    public static final int IMU_GYRO_X = 0;
    public static final int IMU_GYRO_Y = 1;
    public static final int IMU_GYRO_Z = 2;
    public static final int IMU_TEMPERATURE = 3;
    public static final int IMU_ACCEL_X = 4;
    public static final int IMU_ACCEL_Y = 5;
    public static final int IMU_ACCEL_Z = 6;
    public static final int IMU_TIME = 7;
    public static final int IMU_CHANNELS = 8;

    private static final int IMU_MOTION_MASK = (1 << IMU_GYRO_X) | (1 << IMU_GYRO_Y) | (1 << IMU_GYRO_Z)
            | (1 << IMU_ACCEL_X) | (1 << IMU_ACCEL_Y) | (1 << IMU_ACCEL_Z);

    /**
     * Device time may fall this far behind host time before whole wraps are
     * added. The 12-bit timestamp cannot show a quiet gap longer than one
     * wrap; USB-serial delivery jitter makes host time too coarse to recover
     * single wraps, so only larger gaps are corrected.
     */
    public static final long HOST_RESYNC_US = 50_000;

    private static final int MAX_TEXT_LINE = 1024;

    /** Receives decoded stream content. Called on the thread that calls {@link #feed}. */
    public interface Sink {

        /** Pixel event. {@code address} is the low 19 bits of the word. */
        void onEvent(int x, int y, boolean on, int timestampUs, int address);

        /**
         * One complete IMU sample. {@code raw} holds the 12-bit channel values
         * indexed by the {@code IMU_*} ids and is reused between calls.
         */
        default void onImu(int timestampUs, int[] raw) {
        }

        /** Text reply line without its line terminator. */
        default void onText(String line) {
        }

        /** Exception word: {@code id} is one of the {@code EXC_*} values. */
        default void onException(int id, int info, int timestampUs) {
        }
    }

    private final Sink sink;

    private final byte[] word = new byte[4];
    private int wordFill;
    private boolean inText;
    private final StringBuilder text = new StringBuilder();

    private long wrapAdd;
    private int lastShortTs = -1;
    private long lastTs;
    private boolean haveHostOrigin;
    private long hostOriginNanos;

    private final int[] imuRaw = new int[IMU_CHANNELS];
    private int imuMask;

    private long illegalCoordinateCount;
    private long statusWordCount;

    public PsGx320Parser(Sink sink) {
        this.sink = sink;
    }

    /** Zeroes the timestamp origin; the next word is at time 0. Keeps byte alignment. */
    public void resetTimestamps() {
        wrapAdd = 0;
        lastShortTs = -1;
        lastTs = 0;
        haveHostOrigin = false;
        imuMask = 0;
    }

    /** Forgets partial words and text, e.g. after reopening the port. */
    public void resetStream() {
        wordFill = 0;
        inText = false;
        text.setLength(0);
        resetTimestamps();
    }

    /** Words whose x or y was outside the array and that were not a known special word. */
    public long getIllegalCoordinateCount() {
        return illegalCoordinateCount;
    }

    /** Status (object tracking) words seen; they are not decoded. */
    public long getStatusWordCount() {
        return statusWordCount;
    }

    /**
     * Decode a chunk of the stream.
     *
     * @param hostNanos {@link System#nanoTime()} when this chunk was read
     */
    public void feed(byte[] buf, int offset, int length, long hostNanos) {
        boolean checkHost = true;
        final int end = offset + length;
        for (int i = offset; i < end; i++) {
            final byte b = buf[i];
            if (wordFill == 0) {
                if (b >= 0) {
                    textByte(b);
                    continue;
                }
                if (inText) {
                    // a word can only start a new line; flush what we have
                    endTextLine();
                }
            }
            word[wordFill++] = b;
            if (wordFill == 4) {
                wordFill = 0;
                final int w = ((word[0] & 0xff) << 24) | ((word[1] & 0xff) << 16)
                        | ((word[2] & 0xff) << 8) | (word[3] & 0xff);
                decodeWord(w, checkHost, hostNanos);
                checkHost = false;
            }
        }
    }

    private void textByte(byte b) {
        if (b == '\n') {
            endTextLine();
            return;
        }
        if (b == '\r') {
            return;
        }
        inText = true;
        if (text.length() < MAX_TEXT_LINE) {
            text.append((char) b);
        }
    }

    private void endTextLine() {
        inText = false;
        if (text.length() > 0) {
            final String line = text.toString();
            text.setLength(0);
            sink.onText(line);
        }
    }

    private void decodeWord(int w, boolean checkHost, long hostNanos) {
        final int shortTs = (w >>> 19) & 0xFFF;
        final int special = (w >>> 15) & 0x7;
        final boolean tsOverrun = special == 5 && (w & (1 << 14)) != 0
                && ((w >>> 12) & 0x3) == EXC_TIMESTAMP_OVERRUN;
        final int ts = unwrap(shortTs, tsOverrun, checkHost, hostNanos);

        switch (special) {
            case 5:
                if ((w & (1 << 14)) != 0) {
                    sink.onException((w >>> 12) & 0x3, w & 0xFFF, ts);
                } else {
                    statusWordCount++;
                }
                return;
            case 6:
                imuWord((w >>> 12) & 0x7, w & 0xFFF, ts);
                return;
            case 7:
                return;
            default:
                break;
        }
        final int x = (w & XMASK) >>> XSHIFT;
        final int y = w & YMASK;
        if (x >= WIDTH || y >= HEIGHT) {
            // also covers firmware <= 0.3 codes sent as y = 509 / 510
            illegalCoordinateCount++;
            return;
        }
        sink.onEvent(x, y, (w & POLMASK) != 0, ts, w & ADDRESS_MASK);
    }

    private int unwrap(int shortTs, boolean tsOverrun, boolean checkHost, long hostNanos) {
        if (lastShortTs >= 0 && (shortTs < lastShortTs || (tsOverrun && shortTs == lastShortTs))) {
            wrapAdd += WRAP_US;
        }
        lastShortTs = shortTs;
        long ts = wrapAdd + shortTs;
        if (!haveHostOrigin) {
            haveHostOrigin = true;
            hostOriginNanos = hostNanos - ts * 1000L;
        } else if (checkHost) {
            final long hostUs = (hostNanos - hostOriginNanos) / 1000L;
            final long lag = hostUs - ts;
            if (lag > HOST_RESYNC_US) {
                final long add = (lag / WRAP_US) * WRAP_US;
                wrapAdd += add;
                ts += add;
            }
        }
        if (ts < lastTs) {
            ts = lastTs;
        }
        lastTs = ts;
        return (int) ts;
    }

    private void imuWord(int channel, int value, int ts) {
        final int bit = 1 << channel;
        if ((imuMask & bit) != 0) {
            // channel repeated: the previous set is complete
            emitImu(ts);
        }
        imuRaw[channel] = value;
        imuMask |= bit;
        if (channel == IMU_TIME) {
            emitImu(ts);
        }
    }

    private void emitImu(int ts) {
        if ((imuMask & IMU_MOTION_MASK) == IMU_MOTION_MASK) {
            sink.onImu(ts, imuRaw);
        }
        // temperature and IMU time may be sent less often; keep their last values
        imuMask = 0;
    }
}
