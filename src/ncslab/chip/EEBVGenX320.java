package ncslab.chip;

import java.io.Serializable;

import ch.unizh.ini.jaer.chip.EventOnlyChipDisplay;
import ch.unizh.ini.jaer.chip.retina.AETemporalConstastRetina;
import ncslab.serial.PsGx320Parser;
import net.sf.jaer.Description;
import net.sf.jaer.DevelopmentStatus;
import net.sf.jaer.aemonitor.AEPacketRaw;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.chip.RetinaExtractor;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.event.TypedEvent;
import net.sf.jaer.graphics.ChipRendererDisplayMethodRGBA;
import net.sf.jaer.graphics.DisplayMethod;
import net.sf.jaer.hardwareinterface.HardwareInterface;

/**
 * eEBV / PSGX320: Prophesee GenX320 (320x320) on an STM32, connected as a
 * virtual serial port.
 *
 * @see ncslab.serial.EEBVHardwareInterface
 */
@Description("eEBV / PSGX320 embedded Prophesee GenX320 320x320 DVS with IMU; serial port")
@DevelopmentStatus(DevelopmentStatus.Status.Experimental)
public class EEBVGenX320 extends AETemporalConstastRetina implements Serializable {

    public EEBVGenX320() {
        setName("EEBVGenX320");
        setSizeX(PsGx320Parser.WIDTH);
        setSizeY(PsGx320Parser.HEIGHT);
        setNumCellTypes(2);
        setEventExtractor(new Extractor(this));
        getRenderer().ensurePixmapReadyForDisplay();

        EventOnlyChipDisplay.apply(this);
    }

    public EEBVGenX320(HardwareInterface hardwareInterface) {
        this();
        setHardwareInterface(hardwareInterface);
    }

    @Override
    public void onRegistration() {
        super.onRegistration();
        EventOnlyChipDisplay.apply(this);
    }

    @Override
    public DisplayMethod getPreferredDisplayMethod() {
        EventOnlyChipDisplay.clearRgbaPreference(this);
        return super.getPreferredDisplayMethod();
    }

    @Override
    public void setPreferredDisplayMethod(Class<? extends DisplayMethod> clazz) {
        if (clazz == null || ChipRendererDisplayMethodRGBA.class.isAssignableFrom(clazz)) {
            EventOnlyChipDisplay.clearRgbaPreference(this);
            return;
        }
        super.setPreferredDisplayMethod(clazz);
    }

    /** Raw address is the low 19 bits of the sensor's event word. */
    public class Extractor extends RetinaExtractor {

        public Extractor(AEChip chip) {
            super(chip);
            setXmask(PsGx320Parser.XMASK);
            setXshift((byte) PsGx320Parser.XSHIFT);
            setYmask(PsGx320Parser.YMASK);
            setYshift((byte) PsGx320Parser.YSHIFT);
            setTypemask(PsGx320Parser.POLMASK);
            setTypeshift((byte) PsGx320Parser.POLSHIFT);
            setFlipx(false);
            setFlipy(true);
            setFliptype(false);
        }

        @Override
        public int reconstructRawAddressFromEvent(TypedEvent e) {
            return reconstructDefaultRawAddressFromEvent(e);
        }

        @Override
        synchronized public EventPacket extractPacket(AEPacketRaw in) {
            return super.extractPacket(in);
        }
    }
}
