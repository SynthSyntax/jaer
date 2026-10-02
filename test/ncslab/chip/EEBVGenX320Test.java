package ncslab.chip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ncslab.serial.PsGx320Parser;
import net.sf.jaer.aemonitor.AEPacketRaw;
import net.sf.jaer.aemonitor.EventRaw;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.event.PolarityEvent;

public class EEBVGenX320Test {

    private static int word(boolean on, int x, int y) {
        return ((on ? 1 : 0) << PsGx320Parser.POLSHIFT) | (x << PsGx320Parser.XSHIFT) | y;
    }

    /** Recorded raw addresses are the sensor word; extraction must match the live driver's mapping. */
    @Test
    public void extractorMatchesSensorWordAndRoundTrips() {
        EEBVGenX320 chip = new EEBVGenX320();
        int[][] cases = {{0, 0, 1}, {319, 0, 0}, {0, 319, 1}, {319, 319, 0}, {256, 17, 1}};
        AEPacketRaw raw = new AEPacketRaw(cases.length);
        for (int i = 0; i < cases.length; i++) {
            raw.addEvent(new EventRaw(word(cases[i][2] != 0, cases[i][0], cases[i][1]), 100 + i));
        }
        EventPacket<?> cooked = chip.getEventExtractor().extractPacket(raw);
        assertEquals(cases.length, cooked.getSize());
        for (int i = 0; i < cases.length; i++) {
            PolarityEvent e = (PolarityEvent) cooked.getEvent(i);
            assertEquals(cases[i][0], e.x);
            // sensor row 0 is the top; jAER row 0 is the bottom
            assertEquals(PsGx320Parser.HEIGHT - 1 - cases[i][1], e.y);
            assertEquals(cases[i][2] != 0, e.polarity == PolarityEvent.Polarity.On);
            assertEquals(100 + i, e.timestamp);
        }
        AEPacketRaw back = chip.getEventExtractor().reconstructRawPacket(cooked);
        assertEquals(cases.length, back.getNumEvents());
        for (int i = 0; i < cases.length; i++) {
            assertEquals(word(cases[i][2] != 0, cases[i][0], cases[i][1]), back.getAddresses()[i]);
        }
    }

    @Test
    public void tweaksMoveBiasesAroundSavedValues() {
        EEBVConfig config = (EEBVConfig) new EEBVGenX320().getBiasgen();
        for (int i = 0; i < EEBVConfig.NUM_BIASES; i++) {
            config.setBias(i, EEBVConfig.defaultBias(i));
        }
        config.setBiasesFromSensor(config.getBiases());
        int on = config.getBias(EEBVConfig.DIFF_ON);
        int off = config.getBias(EEBVConfig.DIFF_OFF);

        config.setThresholdTweak(1f);
        assertEquals(on + EEBVConfig.DIFF_SPAN, config.getBias(EEBVConfig.DIFF_ON));
        assertEquals(off + EEBVConfig.DIFF_SPAN, config.getBias(EEBVConfig.DIFF_OFF));
        config.setThresholdTweak(0f);
        config.setOnOffBalanceTweak(1f);
        assertTrue(config.getBias(EEBVConfig.DIFF_ON) < on);
        assertTrue(config.getBias(EEBVConfig.DIFF_OFF) > off);
        config.setOnOffBalanceTweak(0f);
        assertEquals(on, config.getBias(EEBVConfig.DIFF_ON));
        assertEquals(off, config.getBias(EEBVConfig.DIFF_OFF));

        config.setHighpassTweak(-1f);
        assertEquals(0, config.getBias(EEBVConfig.HPF));
        config.setBias(EEBVConfig.PR, 999);
        assertEquals(EEBVConfig.BIAS_MAX, config.getBias(EEBVConfig.PR));
    }
}
