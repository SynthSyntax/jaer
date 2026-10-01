package ncslab.serial;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class PsGx320ParserTest {

    private static final class Recorder implements PsGx320Parser.Sink {

        final List<int[]> events = new ArrayList<>();
        final List<int[]> imu = new ArrayList<>();
        final List<Integer> imuTs = new ArrayList<>();
        final List<String> text = new ArrayList<>();
        final List<int[]> exceptions = new ArrayList<>();

        @Override
        public void onEvent(int x, int y, boolean on, int timestampUs, int address) {
            events.add(new int[]{x, y, on ? 1 : 0, timestampUs, address});
        }

        @Override
        public void onImu(int timestampUs, int[] raw) {
            imu.add(raw.clone());
            imuTs.add(timestampUs);
        }

        @Override
        public void onText(String line) {
            text.add(line);
        }

        @Override
        public void onException(int id, int info, int timestampUs) {
            exceptions.add(new int[]{id, info, timestampUs});
        }
    }

    private static int eventWord(int ts, boolean on, int x, int y) {
        return 0x80000000 | ((ts & 0xFFF) << 19) | ((on ? 1 : 0) << 18) | ((x & 0x1FF) << 9) | (y & 0x1FF);
    }

    private static int imuWord(int ts, int channel, int value) {
        return 0x80000000 | ((ts & 0xFFF) << 19) | (6 << 15) | (channel << 12) | (value & 0xFFF);
    }

    private static int exceptionWord(int ts, int id, int info) {
        return 0x80000000 | ((ts & 0xFFF) << 19) | (5 << 15) | (1 << 14) | (id << 12) | (info & 0xFFF);
    }

    private static void put(ByteArrayOutputStream out, int word) {
        out.write(word >>> 24);
        out.write(word >>> 16);
        out.write(word >>> 8);
        out.write(word);
    }

    private static void put(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static void feed(PsGx320Parser p, ByteArrayOutputStream out) {
        byte[] b = out.toByteArray();
        p.feed(b, 0, b.length, 0L);
    }

    @Test
    public void decodesEventFields() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(100, true, 319, 0));
        put(out, eventWord(200, false, 0, 319));
        put(out, eventWord(300, true, 256, 17));
        feed(p, out);
        assertEquals(3, r.events.size());
        assertArrayEquals(new int[]{319, 0, 1, 100, (1 << 18) | (319 << 9)}, r.events.get(0));
        assertArrayEquals(new int[]{0, 319, 0, 200, 319}, r.events.get(1));
        assertArrayEquals(new int[]{256, 17, 1, 300, (1 << 18) | (256 << 9) | 17}, r.events.get(2));
    }

    @Test
    public void textBetweenWords() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, "EBV ready\r\n");
        put(out, eventWord(1, true, 1, 2));
        put(out, "-L+\n");
        put(out, eventWord(2, false, 3, 4));
        feed(p, out);
        assertEquals(List.of("EBV ready", "-L+"), r.text);
        assertEquals(2, r.events.size());
        assertEquals(3, r.events.get(1)[0]);
    }

    @Test
    public void wordBytesThatLookLikeTextAreNotText() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // ts=0, x=0, y=10: bytes 0x80 0x00 0x00 0x0A; the last byte is LF
        put(out, eventWord(0, false, 0, 10));
        feed(p, out);
        assertTrue(r.text.isEmpty());
        assertEquals(1, r.events.size());
        assertEquals(10, r.events.get(0)[1]);
    }

    @Test
    public void wordSplitAcrossFeeds() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(5, true, 100, 200));
        put(out, "ok\n");
        byte[] b = out.toByteArray();
        for (byte one : b) {
            p.feed(new byte[]{one}, 0, 1, 0L);
        }
        assertEquals(1, r.events.size());
        assertArrayEquals(new int[]{100, 200, 1, 5, (1 << 18) | (100 << 9) | 200}, r.events.get(0));
        assertEquals(List.of("ok"), r.text);
    }

    @Test
    public void timestampUnwraps() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(4000, true, 1, 1));
        put(out, eventWord(10, true, 1, 1));
        put(out, eventWord(4095, true, 1, 1));
        put(out, eventWord(0, true, 1, 1));
        feed(p, out);
        assertEquals(4000, r.events.get(0)[3]);
        assertEquals(4096 + 10, r.events.get(1)[3]);
        assertEquals(4096 + 4095, r.events.get(2)[3]);
        assertEquals(2 * 4096, r.events.get(3)[3]);
    }

    @Test
    public void resetTimestampsStartsAtFirstWord() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(4000, true, 1, 1));
        put(out, eventWord(10, true, 1, 1));
        feed(p, out);
        p.resetTimestamps();
        out.reset();
        put(out, eventWord(5, true, 1, 1));
        feed(p, out);
        assertEquals(5, r.events.get(2)[3]);
    }

    @Test
    public void hostTimeRecoversLongQuietGap() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(100, true, 1, 1));
        byte[] b = out.toByteArray();
        p.feed(b, 0, b.length, 0L);
        // one second of silence, then the same short timestamp again
        p.feed(b, 0, b.length, 1_000_000_000L);
        int ts = r.events.get(1)[3];
        assertTrue("ts=" + ts, ts > 1_000_000 - PsGx320Parser.WRAP_US && ts <= 1_000_000 + 100);
        assertEquals(100, ts % PsGx320Parser.WRAP_US);
    }

    @Test
    public void hostJitterDoesNotMoveTime() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(100, true, 1, 1));
        byte[] a = out.toByteArray();
        out.reset();
        put(out, eventWord(200, true, 1, 1));
        byte[] b = out.toByteArray();
        p.feed(a, 0, a.length, 0L);
        p.feed(b, 0, b.length, 20_000_000L); // delivered 20 ms late
        assertEquals(200, r.events.get(1)[3]);
    }

    @Test
    public void illegalCoordinatesDropped() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(1, true, 5, 509)); // firmware <= 0.3 dropped-packet code
        put(out, eventWord(2, true, 5, 510));
        put(out, eventWord(3, true, 5, 320));
        put(out, eventWord(4, true, 5, 319));
        feed(p, out);
        assertEquals(1, r.events.size());
        assertEquals(319, r.events.get(0)[1]);
        assertEquals(3, p.getIllegalCoordinateCount());
    }

    @Test
    public void exceptionAndStatusWords() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, exceptionWord(7, PsGx320Parser.EXC_DCMI_DROP, 42));
        put(out, exceptionWord(8, PsGx320Parser.EXC_SPI_USB_DROP, 3));
        // status word: 101, bit 14 clear
        put(out, 0x80000000 | (9 << 19) | (5 << 15) | (2 << 12) | (1 << 9) | 77);
        // reserved 111
        put(out, 0x80000000 | (9 << 19) | (7 << 15));
        feed(p, out);
        assertTrue(r.events.isEmpty());
        assertEquals(2, r.exceptions.size());
        assertArrayEquals(new int[]{PsGx320Parser.EXC_DCMI_DROP, 42, 7}, r.exceptions.get(0));
        assertArrayEquals(new int[]{PsGx320Parser.EXC_SPI_USB_DROP, 3, 8}, r.exceptions.get(1));
        assertEquals(1, p.getStatusWordCount());
        assertEquals(0, p.getIllegalCoordinateCount());
    }

    @Test
    public void repeatedTimestampOverrunAddsWrap() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, eventWord(50, true, 1, 1));
        put(out, exceptionWord(0, PsGx320Parser.EXC_TIMESTAMP_OVERRUN, 0));
        put(out, exceptionWord(0, PsGx320Parser.EXC_TIMESTAMP_OVERRUN, 1));
        put(out, eventWord(60, true, 1, 1));
        feed(p, out);
        assertEquals(50, r.events.get(0)[3]);
        assertEquals(2 * 4096 + 60, r.events.get(1)[3]);
    }

    @Test
    public void imuSetAssembledOnImuTime() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int ch = 0; ch < PsGx320Parser.IMU_CHANNELS; ch++) {
            put(out, imuWord(10 + ch, ch, 100 + ch));
        }
        put(out, eventWord(30, true, 1, 1));
        feed(p, out);
        assertEquals(1, r.imu.size());
        assertArrayEquals(new int[]{100, 101, 102, 103, 104, 105, 106, 107}, r.imu.get(0));
        assertEquals(17, (int) r.imuTs.get(0));
        assertEquals(1, r.events.size());
    }

    @Test
    public void imuSetWithoutImuTimeEmittedWhenChannelRepeats() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] channels = {PsGx320Parser.IMU_GYRO_X, PsGx320Parser.IMU_GYRO_Y, PsGx320Parser.IMU_GYRO_Z,
            PsGx320Parser.IMU_ACCEL_X, PsGx320Parser.IMU_ACCEL_Y, PsGx320Parser.IMU_ACCEL_Z};
        for (int ch : channels) {
            put(out, imuWord(1, ch, ch + 1));
        }
        feed(p, out);
        assertTrue(r.imu.isEmpty());
        out.reset();
        put(out, imuWord(2, PsGx320Parser.IMU_GYRO_X, 99));
        feed(p, out);
        assertEquals(1, r.imu.size());
        assertEquals(1, r.imu.get(0)[PsGx320Parser.IMU_GYRO_X]);
        assertEquals(7, r.imu.get(0)[PsGx320Parser.IMU_ACCEL_Z]);
    }

    @Test
    public void incompleteImuSetNotEmitted() {
        Recorder r = new Recorder();
        PsGx320Parser p = new PsGx320Parser(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, imuWord(1, PsGx320Parser.IMU_GYRO_X, 1));
        put(out, imuWord(1, PsGx320Parser.IMU_TIME, 2));
        feed(p, out);
        assertFalse(r.imu.size() > 0);
    }
}
