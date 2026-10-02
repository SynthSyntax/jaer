package ncslab.chip;

import java.awt.BorderLayout;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import ch.unizh.ini.jaer.chip.retina.DVSAutoControllerPanel;
import ch.unizh.ini.jaer.chip.retina.DVSTweaks;
import ch.unizh.ini.jaer.chip.retina.DVSUserControlPanel;
import net.sf.jaer.biasgen.Biasgen;
import net.sf.jaer.biasgen.ChipControlPanel;
import net.sf.jaer.biasgen.PotArray;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.chip.Chip;
import net.sf.jaer.hardwareinterface.HardwareInterfaceException;

/**
 * GenX320 bias control for the eEBV / PSGX320 sensor. The twelve biases are
 * the ones the firmware lists with {@code ?BN} and sets with
 * {@code !B<i>=<v>}. Values live in the chip Preferences node and can be
 * exported/imported as XML from the Biases frame.
 * User-friendly tweaks are additive offsets around the last loaded/saved
 * snapshot.
 */
public class EEBVConfig extends Biasgen implements ChipControlPanel, DVSTweaks {

    private static final Logger log = Logger.getLogger("net.sf.jaer");

    public static final int PR = 0;
    public static final int FO = 1;
    public static final int FES = 2;
    public static final int HPF = 3;
    public static final int DIFF_ON = 4;
    public static final int DIFF = 5;
    public static final int DIFF_OFF = 6;
    public static final int INV = 7;
    public static final int REFR = 8;
    public static final int INVP = 9;
    public static final int REQ_PU = 10;
    public static final int SM_PDY = 11;
    public static final int NUM_BIASES = 12;

    /** Names as printed by the firmware's {@code ?BN}. */
    private static final String[] NAMES = {"pr", "fo", "fes", "hpf", "diff_on", "diff", "diff_off", "inv", "refr",
        "invp", "req_pu", "sm_pdy"};
    /** Values firmware V0.5 reports with {@code ?B} after power-up. */
    private static final int[] DEFAULTS = {61, 34, 63, 0, 30, 51, 33, 57, 10, 56, 116, 164};

    public static final int BIAS_MIN = 0;
    public static final int BIAS_MAX = 255;

    /** Indexed property: bias index, old value, new value. */
    public static final String PROPERTY_BIAS = "eebvBias";
    /** Fired with the new tweak value in −1…1. */
    public static final String PROPERTY_HIGHPASS_TWEAK = "highpass";

    // Offset from the saved value at tweak +1 / −1, in bias counts.
    static final int DIFF_SPAN = 25;
    static final int FO_SPAN = 30;
    static final int REFR_SPAN = 60;
    static final int HPF_SPAN = 60;

    private static final String PREFS_BIAS = "EEBVConfig.bias.";

    private int[] biases;
    /** Last loaded or saved snapshot; the centre of the tweak sliders. */
    private int[] saved;

    private float thresholdTweak;
    private float onOffBalanceTweak;
    private float bandwidthTweak;
    private float maxFiringRateTweak;
    private float highpassTweak;

    private JPanel controlPanel;
    private EEBVControlPanel rawControlPanel;
    private EEBVUserControlPanel userControlPanel;

    public EEBVConfig(Chip chip) {
        super(chip);
        setName("EEBVConfig");
        setPotArray(new PotArray(this));
    }

    public static String biasName(int i) {
        return NAMES[i];
    }

    public static int defaultBias(int i) {
        return DEFAULTS[i];
    }

    // The Biasgen constructor may load preferences before this class's fields exist.
    private int[] values() {
        if (biases == null) {
            biases = DEFAULTS.clone();
        }
        return biases;
    }

    private int[] baseline() {
        if (saved == null) {
            saved = values().clone();
        }
        return saved;
    }

    /** Copy of the current bias values, indexed as the firmware does. */
    public int[] getBiases() {
        return values().clone();
    }

    public int getBias(int i) {
        return values()[i];
    }

    /** Value at the last load or save; the tweak sliders are offsets from it. */
    public int getSavedBias(int i) {
        return baseline()[i];
    }

    public void setBias(int i, int value) {
        value = clampBias(value);
        final int old = values()[i];
        if (old == value) {
            return;
        }
        values()[i] = value;
        applyToHardware();
        markFileModified();
        support.fireIndexedPropertyChange(PROPERTY_BIAS, i, old, value);
    }

    /** Adopts values read back from the sensor as the new baseline. */
    public void setBiasesFromSensor(int[] fromSensor) {
        for (int i = 0; i < NUM_BIASES && i < fromSensor.length; i++) {
            if (fromSensor[i] >= 0) {
                values()[i] = clampBias(fromSensor[i]);
            }
        }
        saved = values().clone();
        resetTweaks();
        markFileModified();
        refreshControlPanels();
        support.firePropertyChange(PROPERTY_CHANGE_PREFERENCES_LOADED, null, null);
    }

    private void applyToHardware() {
        try {
            sendConfiguration(this);
        } catch (HardwareInterfaceException e) {
            log.warning("Could not send eEBV biases: " + e.getMessage());
        }
    }

    private void markFileModified() {
        if (getChip() instanceof AEChip aeChip && aeChip.getAeViewer() != null
                && aeChip.getAeViewer().getBiasgenFrame() != null) {
            aeChip.getAeViewer().getBiasgenFrame().setFileModified(true);
        }
    }

    private static int clampBias(int value) {
        return Math.max(BIAS_MIN, Math.min(BIAS_MAX, value));
    }

    private static float clampTweak(float val) {
        return Math.max(-1f, Math.min(1f, val));
    }

    private void resetTweaks() {
        thresholdTweak = 0f;
        onOffBalanceTweak = 0f;
        bandwidthTweak = 0f;
        maxFiringRateTweak = 0f;
        highpassTweak = 0f;
    }

    private void tweakBias(int i, float offset) {
        setBias(i, baseline()[i] + Math.round(offset));
    }

    private void applyThresholdAndBalance() {
        // higher diff_on / diff_off = higher threshold for that polarity
        tweakBias(DIFF_ON, (thresholdTweak - onOffBalanceTweak) * DIFF_SPAN);
        tweakBias(DIFF_OFF, (thresholdTweak + onOffBalanceTweak) * DIFF_SPAN);
    }

    /**
     * Tweaks ON and OFF contrast thresholds together.
     *
     * @param val −1…1; larger is a higher threshold (fewer events), 0 is the saved value
     */
    @Override
    public void setThresholdTweak(float val) {
        val = clampTweak(val);
        final float old = thresholdTweak;
        if (old == val) {
            return;
        }
        thresholdTweak = val;
        applyThresholdAndBalance();
        support.firePropertyChange(DVSTweaks.THRESHOLD, old, val);
    }

    @Override
    public float getThresholdTweak() {
        return thresholdTweak;
    }

    /**
     * Tweaks ON against OFF threshold.
     *
     * @param val −1…1; larger gives more ON events (lower ON, higher OFF threshold)
     */
    @Override
    public void setOnOffBalanceTweak(float val) {
        val = clampTweak(val);
        final float old = onOffBalanceTweak;
        if (old == val) {
            return;
        }
        onOffBalanceTweak = val;
        applyThresholdAndBalance();
        support.firePropertyChange(DVSTweaks.ON_OFF_BALANCE, old, val);
    }

    @Override
    public float getOnOffBalanceTweak() {
        return onOffBalanceTweak;
    }

    /**
     * Tweaks the pixel low-pass ({@code fo}).
     *
     * @param val −1…1; larger is a wider bandwidth
     */
    @Override
    public void setBandwidthTweak(float val) {
        val = clampTweak(val);
        final float old = bandwidthTweak;
        if (old == val) {
            return;
        }
        bandwidthTweak = val;
        tweakBias(FO, val * FO_SPAN);
        support.firePropertyChange(DVSTweaks.BANDWIDTH, old, val);
    }

    @Override
    public float getBandwidthTweak() {
        return bandwidthTweak;
    }

    /**
     * Tweaks the refractory period ({@code refr}).
     *
     * @param val −1…1; larger is a higher maximum firing rate
     */
    @Override
    public void setMaxFiringRateTweak(float val) {
        val = clampTweak(val);
        final float old = maxFiringRateTweak;
        if (old == val) {
            return;
        }
        maxFiringRateTweak = val;
        tweakBias(REFR, val * REFR_SPAN);
        support.firePropertyChange(DVSTweaks.MAX_FIRING_RATE, old, val);
    }

    @Override
    public float getMaxFiringRateTweak() {
        return maxFiringRateTweak;
    }

    /**
     * Tweaks the pixel high-pass ({@code hpf}).
     *
     * @param val −1…1; larger rejects more slow change
     */
    public void setHighpassTweak(float val) {
        val = clampTweak(val);
        final float old = highpassTweak;
        if (old == val) {
            return;
        }
        highpassTweak = val;
        tweakBias(HPF, val * HPF_SPAN);
        support.firePropertyChange(PROPERTY_HIGHPASS_TWEAK, old, val);
    }

    public float getHighpassTweak() {
        return highpassTweak;
    }

    @Override
    public float getPhotoreceptorSourceFollowerBandwidthHz() {
        return Float.NaN;
    }

    @Override
    public float getOnThresholdLogE() {
        return Float.NaN;
    }

    @Override
    public float getOffThresholdLogE() {
        return Float.NaN;
    }

    @Override
    public float getRefractoryPeriodS() {
        return Float.NaN;
    }

    private Preferences chipPrefs() {
        return getChip().getPrefs();
    }

    @Override
    public void loadPreferences() {
        final Preferences p = chipPrefs();
        for (int i = 0; i < NUM_BIASES; i++) {
            values()[i] = clampBias(p.getInt(PREFS_BIAS + NAMES[i], DEFAULTS[i]));
        }
        saved = values().clone();
        resetTweaks();
        applyToHardware();
        refreshControlPanels();
        support.firePropertyChange(PROPERTY_CHANGE_PREFERENCES_LOADED, null, null);
    }

    @Override
    public void storePreferences() {
        for (int i = 0; i < NUM_BIASES; i++) {
            putPref(PREFS_BIAS + NAMES[i], values()[i]);
        }
        saved = values().clone();
        resetTweaks();
        refreshControlPanels();
        support.firePropertyChange(PROPERTY_CHANGE_PREFERENCES_STORED, null, null);
    }

    @Override
    public boolean isInitialized() {
        if (getChip() != null && getChip().isDefaultPreferencesLoadedOnce()) {
            return true;
        }
        return chipPrefs().get(PREFS_BIAS + NAMES[DIFF], null) != null;
    }

    private void refreshControlPanels() {
        if (rawControlPanel != null) {
            rawControlPanel.refreshFromBiases();
        }
        if (userControlPanel != null) {
            userControlPanel.syncFromConfig();
        }
    }

    @Override
    public JPanel buildControlPanel() {
        return getControlPanel();
    }

    @Override
    public JPanel getControlPanel() {
        if (controlPanel == null) {
            userControlPanel = new EEBVUserControlPanel(this);
            rawControlPanel = new EEBVControlPanel(this);
            final JTabbedPane tabs = new JTabbedPane();
            tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            tabs.addTab("<html><strong><font color=\"red\">User-Friendly Controls", userControlPanel);
            DVSAutoControllerPanel.addTab(tabs, getChip() instanceof AEChip ae ? ae : null);
            tabs.addTab("Raw biases and sensor", rawControlPanel);
            DVSUserControlPanel.capTabbedPanePreferredWidth(tabs);
            DVSUserControlPanel.selectUserFriendlyTab(tabs);
            controlPanel = new JPanel(new BorderLayout());
            controlPanel.add(tabs, BorderLayout.CENTER);
        }
        return controlPanel;
    }
}
