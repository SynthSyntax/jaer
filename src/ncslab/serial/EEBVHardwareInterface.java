package ncslab.serial;

import java.beans.PropertyChangeSupport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fazecast.jSerialComm.SerialPort;

import net.sf.jaer.aemonitor.AEListener;
import net.sf.jaer.aemonitor.AEMonitorInterface;
import net.sf.jaer.aemonitor.AEPacketRaw;
import net.sf.jaer.aemonitor.DroppedDataInfo;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.ImuPacket;
import net.sf.jaer.event.PacketBundle;
import net.sf.jaer.event.PacketBundlePool;
import net.sf.jaer.hardwareinterface.HardwareInterface;
import net.sf.jaer.hardwareinterface.HardwareInterfaceException;
import net.sf.jaer.hardwareinterface.usb.UsbPolarityBundleBuilder;

/**
 * eEBV / PSGX320 camera (Prophesee GenX320 on STM32) on a virtual serial port.
 * ASCII commands out, {@link PsGx320Parser} stream in; delivers typed polarity
 * and IMU packets.
 */
public class EEBVHardwareInterface implements AEMonitorInterface, PsGx320Parser.Sink {

    private static final Logger log = Logger.getLogger("net.sf.jaer");
    private static final Set<String> OPEN_PORTS = new CopyOnWriteArraySet<>();

    /** The quickstart guide asks for 12 Mbps; a virtual port ignores the value. */
    public static final int BAUD = 12_000_000;
    private static final int FALLBACK_BAUD = 4_000_000;
    /** {@code -Djaer.eebv.baud=N} overrides {@link #BAUD}, e.g. behind a slower UART bridge. */
    public static final String BAUD_PROP = "jaer.eebv.baud";
    /** {@code -Djaer.eebv.rtscts=false} opens the port without RTS/CTS handshaking. */
    public static final String RTSCTS_PROP = "jaer.eebv.rtscts";
    public static final String CMD_START_STREAM = "+";
    public static final String CMD_STOP_STREAM = "-";
    public static final String CMD_HELP = "??";

    private static final int READ_BUFFER_BYTES = 1 << 16;
    private static final int READ_TIMEOUT_MS = 100;
    /** Events kept per ViewLoop cycle before the write buffer counts as overrun. */
    private static final int MAX_EVENTS_PER_BUNDLE = 2_000_000;
    private static final int INITIAL_EVENT_CAPACITY = 1 << 16;
    /** Sensor y = 0 is the top row; jAER y = 0 is the bottom row. */
    private static final boolean FLIP_Y = true;

    // IMU conversion. Not in the quickstart guide: 12-bit two's complement and
    // these full-scale ranges are assumed until checked against the sensor.
    private static final float ACCEL_G_PER_LSB = 2f / 2048;
    private static final float GYRO_DPS_PER_LSB = 250f / 2048;
    private static final float ACCEL_CLIP_G = 3.99f;
    private static final float GYRO_CLIP_DPS = 499f;

    private final String portName;
    private final String label;
    private final int vid;
    private final int pid;

    private final PacketBundlePool pool = new PacketBundlePool();
    private final UsbPolarityBundleBuilder builder = new UsbPolarityBundleBuilder();
    private final PsGx320Parser parser = new PsGx320Parser(this);
    private final AEPacketRaw emptyRaw = new AEPacketRaw(0);
    private final PropertyChangeSupport support = new PropertyChangeSupport(this);
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicBoolean acquire = new AtomicBoolean(false);
    private final AtomicBoolean overrun = new AtomicBoolean(false);
    private final AtomicLong deviceDropCount = new AtomicLong();
    private final Object writeLock = new Object();

    private volatile AEChip chip;
    private volatile SerialPort port;
    private volatile Thread readerThread;
    private volatile PacketBundle lastBundle = new PacketBundle();
    private volatile List<String> textCapture;
    private volatile boolean closing;

    // guarded by pool
    private int eventsInWriteBuffer;
    private ImuPacket imuInWriteBuffer;
    private long deviceDropsAtLastAcquire;
    private boolean deviceDroppedLastAcquire;

    private int lastNumEvents;
    private volatile int estimatedRate;
    private long rateWindowStartNanos;
    private long rateWindowEvents;
    private int aeBufferSize = READ_BUFFER_BYTES;

    public EEBVHardwareInterface(SerialPort candidate) {
        this(candidate.getSystemPortName(), describe(candidate), candidate.getVendorID(), candidate.getProductID());
    }

    public EEBVHardwareInterface(String portName, String description, int vid, int pid) {
        this.portName = portName;
        this.vid = vid;
        this.pid = pid;
        this.label = "eEBV GenX320 on " + portName
                + (description == null || description.isBlank() ? "" : " (" + description + ")");
    }

    private static String describe(SerialPort p) {
        String d = p.getPortDescription();
        if (d == null || d.isBlank() || d.equals(p.getSystemPortName())) {
            d = p.getDescriptivePortName();
        }
        return d;
    }

    public String getPortName() {
        return portName;
    }

    /** USB vendor id of the serial bridge, or -1 if the OS does not report it. */
    public int getUsbVendorId() {
        return vid;
    }

    /** USB product id of the serial bridge, or -1 if the OS does not report it. */
    public int getUsbProductId() {
        return pid;
    }

    public static boolean isPortOpen(String portName) {
        return OPEN_PORTS.contains(portName);
    }

    /** True when both are eEBV interfaces on the same serial port. */
    public static boolean sameDevice(HardwareInterface a, HardwareInterface b) {
        if (!(a instanceof EEBVHardwareInterface) || !(b instanceof EEBVHardwareInterface)) {
            return false;
        }
        return ((EEBVHardwareInterface) a).portName.equals(((EEBVHardwareInterface) b).portName);
    }

    @Override
    public String getTypeName() {
        return "eEBV GenX320";
    }

    @Override
    public String toString() {
        return label;
    }

    @Override
    public synchronized void open() throws HardwareInterfaceException {
        if (open.get()) {
            return;
        }
        if (OPEN_PORTS.contains(portName)) {
            throw new HardwareInterfaceException("Serial port " + portName + " is already open");
        }
        final boolean rtscts = !"false".equalsIgnoreCase(System.getProperty(RTSCTS_PROP, "true"));
        SerialPort p = null;
        int error = 0;
        // Linux cdc_acm rejects the non-standard 12 Mbps rate; the USB link ignores
        // the value, so fall back to the highest standard rate.
        for (int baud : new int[]{Integer.getInteger(BAUD_PROP, BAUD), FALLBACK_BAUD}) {
            SerialPort candidate;
            try {
                candidate = SerialPort.getCommPort(portName);
            } catch (RuntimeException e) {
                throw new HardwareInterfaceException("No serial port " + portName + ": " + e.getMessage());
            }
            candidate.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            candidate.setFlowControl(rtscts
                    ? (SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED)
                    : SerialPort.FLOW_CONTROL_DISABLED);
            candidate.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);
            if (candidate.openPort()) {
                p = candidate;
                log.fine("eEBV " + portName + " opened at " + baud + " baud");
                break;
            }
            error = candidate.getLastErrorCode();
        }
        if (p == null) {
            throw new HardwareInterfaceException("Could not open serial port " + portName
                    + " (error " + error + "). On Linux the user needs access to the port:"
                    + " add yourself to the group that owns it (uucp or dialout), or chmod 666 the /dev/tty* device.");
        }
        port = p;
        closing = false;
        OPEN_PORTS.add(portName);
        open.set(true);
        try {
            identify(p);
        } catch (HardwareInterfaceException e) {
            close();
            throw e;
        }
        synchronized (pool) {
            parser.resetStream();
            pool.reset();
            eventsInWriteBuffer = 0;
            imuInWriteBuffer = null;
            builder.ensureCapacity(INITIAL_EVENT_CAPACITY);
        }
        log.info("Opened " + label);
    }

    /** Stops any running stream, then logs the device's reply to the help command. */
    private void identify(SerialPort p) throws HardwareInterfaceException {
        write(p, CMD_STOP_STREAM);
        drain(p, 200);
        write(p, CMD_HELP);
        byte[] reply = drain(p, 500);
        // the stream may still have been running when "-" arrived: keep text bytes only
        StringBuilder sb = new StringBuilder();
        for (byte b : reply) {
            if (b == '\n' || (b >= 0x20 && b < 0x7f)) {
                sb.append((char) b);
            }
        }
        String text = sb.toString().strip();
        if (text.isEmpty()) {
            throw new HardwareInterfaceException("No reply to \"" + CMD_HELP + "\" on " + portName
                    + "; is this an eEBV / PSGX320 sensor?");
        }
        log.info("eEBV on " + portName + " replied to " + CMD_HELP + ":\n" + text);
    }

    /** Reads until the port has been quiet for one read timeout or {@code maxMs} passed. */
    private static byte[] drain(SerialPort p, int maxMs) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        final long deadline = System.nanoTime() + maxMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            int n = p.readBytes(buf, buf.length);
            if (n <= 0) {
                if (out.size() > 0) {
                    break;
                }
                continue;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void write(SerialPort p, String command) throws HardwareInterfaceException {
        byte[] b = (command + "\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            if (p.writeBytes(b, b.length) != b.length) {
                throw new HardwareInterfaceException("Write of \"" + command + "\" to " + portName + " failed");
            }
        }
    }

    /**
     * Sends a text command; LF is appended. Replies arrive asynchronously and
     * are logged, see {@link #sendCommandForReply}.
     */
    public void sendCommand(String command) throws HardwareInterfaceException {
        SerialPort p = port;
        if (!open.get() || p == null) {
            throw new HardwareInterfaceException("eEBV on " + portName + " is not open");
        }
        write(p, command);
    }

    /**
     * Sends a command and returns the text lines received within
     * {@code waitMs}. Works while streaming. Do not call from the Swing thread.
     */
    public List<String> sendCommandForReply(String command, int waitMs) throws HardwareInterfaceException {
        List<String> capture = new ArrayList<>();
        Thread reader = readerThread;
        if (reader == null || !reader.isAlive()) {
            // not streaming: nobody else reads the port
            SerialPort p = port;
            sendCommand(command);
            String text = new String(drain(p, waitMs), StandardCharsets.US_ASCII);
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    capture.add(line.strip());
                }
            }
            return capture;
        }
        synchronized (capture) {
            textCapture = capture;
            try {
                sendCommand(command);
                capture.wait(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                textCapture = null;
            }
            return new ArrayList<>(capture);
        }
    }

    @Override
    public synchronized void close() {
        closing = true;
        SerialPort p = port;
        if (acquire.getAndSet(false) && p != null) {
            try {
                write(p, CMD_STOP_STREAM);
            } catch (HardwareInterfaceException e) {
                log.log(Level.FINE, "eEBV stop stream on close: " + e, e);
            }
        }
        Thread t = readerThread;
        readerThread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        port = null;
        if (p != null) {
            try {
                p.closePort();
            } catch (RuntimeException e) {
                log.log(Level.FINE, "eEBV close: " + e, e);
            }
        }
        OPEN_PORTS.remove(portName);
        if (open.getAndSet(false)) {
            log.info("Closed " + label);
        }
        synchronized (pool) {
            pool.reset();
            eventsInWriteBuffer = 0;
            imuInWriteBuffer = null;
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public AEPacketRaw acquireAvailableEventsFromDriver() throws HardwareInterfaceException {
        return emptyRaw;
    }

    @Override
    public PacketBundle acquireAvailablePacketBundle() throws HardwareInterfaceException {
        if (!open.get()) {
            open();
        }
        if (!acquire.get()) {
            setEventAcquisitionEnabled(true);
        }
        synchronized (pool) {
            pool.swap();
            lastBundle = pool.readBuffer();
            lastNumEvents = eventsInWriteBuffer;
            eventsInWriteBuffer = 0;
            imuInWriteBuffer = null;
            builder.attach(pool.writeBuffer());
            long drops = deviceDropCount.get();
            deviceDroppedLastAcquire = drops != deviceDropsAtLastAcquire;
            deviceDropsAtLastAcquire = drops;
        }
        return lastBundle;
    }

    @Override
    public int getNumEventsAcquired() {
        return lastNumEvents;
    }

    @Override
    public AEPacketRaw getEvents() {
        return emptyRaw;
    }

    @Override
    public void resetTimestamps() {
        synchronized (pool) {
            parser.resetTimestamps();
            builder.rewindCurrentSlot();
            eventsInWriteBuffer = 0;
            imuInWriteBuffer = null;
        }
        log.info("eEBV resetTimestamps(): zeroing timestamp origin (" + label + ")");
    }

    @Override
    public boolean overrunOccurred() {
        return overrun.getAndSet(false);
    }

    @Override
    public DroppedDataInfo getDroppedDataInfo() {
        if (overrunOccurred()) {
            return DroppedDataInfo.hostBufferOverrun();
        }
        if (deviceDroppedLastAcquire) {
            return DroppedDataInfo.hostBufferOverrun("eEBV sensor reported dropped data (DCMI or SPI/USB packet drop)");
        }
        return DroppedDataInfo.none();
    }

    /** Packet-drop exceptions reported by the sensor since open. */
    public long getDeviceDropCount() {
        return deviceDropCount.get();
    }

    @Override
    public int getAEBufferSize() {
        return aeBufferSize;
    }

    @Override
    public void setAEBufferSize(int AEBufferSize) {
        this.aeBufferSize = Math.max(1, AEBufferSize);
    }

    @Override
    public synchronized void setEventAcquisitionEnabled(boolean enable) throws HardwareInterfaceException {
        if (!open.get()) {
            acquire.set(false);
            return;
        }
        if (acquire.getAndSet(enable) == enable) {
            return;
        }
        SerialPort p = port;
        if (enable) {
            startReaderThread();
            write(p, CMD_START_STREAM);
        } else {
            write(p, CMD_STOP_STREAM);
        }
    }

    @Override
    public boolean isEventAcquisitionEnabled() {
        return acquire.get();
    }

    private void startReaderThread() {
        Thread t = readerThread;
        if (t != null && t.isAlive()) {
            return;
        }
        t = new Thread(this::readLoop, "jaer-eebv-reader-" + portName);
        t.setDaemon(true);
        readerThread = t;
        t.start();
    }

    /** Runs while the port is open, so replies to commands are still read when streaming is off. */
    private void readLoop() {
        final byte[] buf = new byte[READ_BUFFER_BYTES];
        final SerialPort p = port;
        boolean lost = false;
        rateWindowStartNanos = System.nanoTime();
        while (open.get() && !closing) {
            final int n = p.readBytes(buf, buf.length);
            if (n < 0) {
                lost = true;
                break;
            }
            if (n == 0) {
                if (!p.isOpen()) {
                    lost = true;
                    break;
                }
                continue;
            }
            final long now = System.nanoTime();
            synchronized (pool) {
                builder.attach(pool.writeBuffer());
                parser.feed(buf, 0, n, now);
                builder.flushAll();
            }
            updateRate(now);
        }
        if (lost && !closing) {
            log.warning("eEBV serial port " + portName + " stopped delivering data (unplugged?); closing");
            close();
        }
    }

    private void updateRate(long now) {
        final long dt = now - rateWindowStartNanos;
        if (dt >= 500_000_000L) {
            estimatedRate = (int) Math.min(Integer.MAX_VALUE, rateWindowEvents * 1_000_000_000L / dt);
            rateWindowEvents = 0;
            rateWindowStartNanos = now;
        }
    }

    // PsGx320Parser.Sink, called from readLoop under the pool lock

    @Override
    public void onEvent(int x, int y, boolean on, int timestampUs, int address) {
        if (!acquire.get()) {
            return;
        }
        if (eventsInWriteBuffer >= MAX_EVENTS_PER_BUNDLE) {
            overrun.set(true);
            return;
        }
        builder.addPolarity(x, FLIP_Y ? (PsGx320Parser.HEIGHT - 1 - y) : y, on, timestampUs, address);
        eventsInWriteBuffer++;
        rateWindowEvents++;
    }

    @Override
    public void onImu(int timestampUs, int[] raw) {
        if (!acquire.get()) {
            return;
        }
        if (imuInWriteBuffer == null) {
            imuInWriteBuffer = new ImuPacket();
            pool.writeBuffer().addAllowEmpty(imuInWriteBuffer);
        }
        imuInWriteBuffer.nextOutput().setFromPhysicalUnits(timestampUs,
                clip(signed12(raw[PsGx320Parser.IMU_ACCEL_X]) * ACCEL_G_PER_LSB, ACCEL_CLIP_G),
                clip(signed12(raw[PsGx320Parser.IMU_ACCEL_Y]) * ACCEL_G_PER_LSB, ACCEL_CLIP_G),
                clip(signed12(raw[PsGx320Parser.IMU_ACCEL_Z]) * ACCEL_G_PER_LSB, ACCEL_CLIP_G),
                clip(signed12(raw[PsGx320Parser.IMU_GYRO_X]) * GYRO_DPS_PER_LSB, GYRO_CLIP_DPS),
                clip(signed12(raw[PsGx320Parser.IMU_GYRO_Y]) * GYRO_DPS_PER_LSB, GYRO_CLIP_DPS),
                clip(signed12(raw[PsGx320Parser.IMU_GYRO_Z]) * GYRO_DPS_PER_LSB, GYRO_CLIP_DPS),
                raw[PsGx320Parser.IMU_TEMPERATURE]);
    }

    private static int signed12(int v) {
        return (v << 20) >> 20;
    }

    private static float clip(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    @Override
    public void onText(String line) {
        List<String> capture = textCapture;
        if (capture != null) {
            synchronized (capture) {
                capture.add(line);
            }
        }
        log.info("eEBV " + portName + ": " + line);
    }

    @Override
    public void onException(int id, int info, int timestampUs) {
        if (id == PsGx320Parser.EXC_DCMI_DROP || id == PsGx320Parser.EXC_SPI_USB_DROP) {
            deviceDropCount.incrementAndGet();
        }
        log.fine("eEBV " + portName + " exception id=" + id + " info=" + info + " t=" + timestampUs);
    }

    @Override
    public void addAEListener(AEListener listener) {
        support.addPropertyChangeListener(listener);
    }

    @Override
    public void removeAEListener(AEListener listener) {
        support.removePropertyChangeListener(listener);
    }

    @Override
    public int getMaxCapacity() {
        // 12 Mbps, 10 bits per byte on the wire, 4 bytes per event
        return BAUD / 40;
    }

    @Override
    public int getEstimatedEventRate() {
        return estimatedRate;
    }

    @Override
    public int getTimestampTickUs() {
        return 1;
    }

    @Override
    public void setChip(AEChip chip) {
        this.chip = chip;
    }

    @Override
    public AEChip getChip() {
        return chip;
    }
}
