package ncslab.serial;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fazecast.jSerialComm.SerialPort;

import net.sf.jaer.hardwareinterface.HardwareInterface;
import net.sf.jaer.hardwareinterface.HardwareInterfaceFactoryInterface;

/**
 * Lists USB serial ports that may be an eEBV / PSGX320 sensor. The scan reads
 * the OS port list only; no port is opened and nothing is sent until the user
 * picks one from the Interface menu.
 */
public final class EEBVHardwareInterfaceFactory implements HardwareInterfaceFactoryInterface {

    private static final Logger log = Logger.getLogger("net.sf.jaer");
    private static final EEBVHardwareInterfaceFactory INSTANCE = new EEBVHardwareInterfaceFactory();

    /**
     * USB VID/PID pairs of the sensor's serial bridge, as {@code (vid << 16) | pid}.
     * While empty, every USB serial port is offered.
     */
    private static final int[] KNOWN_BRIDGES = {};

    private volatile List<SerialPort> snapshot = List.of();

    private EEBVHardwareInterfaceFactory() {
    }

    public static HardwareInterfaceFactoryInterface instance() {
        return INSTANCE;
    }

    private static boolean isCandidate(SerialPort p) {
        final int vid = p.getVendorID();
        final int pid = p.getProductID();
        if (vid <= 0) {
            // not a USB serial port (e.g. motherboard ttyS*)
            return false;
        }
        if (KNOWN_BRIDGES.length == 0) {
            return true;
        }
        final int key = (vid << 16) | (pid & 0xffff);
        for (int k : KNOWN_BRIDGES) {
            if (k == key) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getNumInterfacesAvailable() {
        List<SerialPort> found = new ArrayList<>();
        try {
            for (SerialPort p : SerialPort.getCommPorts()) {
                if (isCandidate(p)) {
                    found.add(p);
                }
            }
        } catch (Throwable t) {
            // native library missing or unsupported platform: no serial cameras
            log.log(Level.FINE, "eEBV serial port scan failed: " + t, t);
        }
        snapshot = List.copyOf(found);
        return found.size();
    }

    @Override
    public HardwareInterface getFirstAvailableInterface() {
        return getInterface(0);
    }

    @Override
    public HardwareInterface getInterface(int n) {
        List<SerialPort> list = snapshot;
        if (n < 0 || n >= list.size()) {
            return null;
        }
        return new EEBVHardwareInterface(list.get(n));
    }

    @Override
    public String getGUID() {
        return null;
    }
}
