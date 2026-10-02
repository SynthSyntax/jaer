package ncslab.chip;

import java.awt.Component;
import java.beans.PropertyChangeEvent;

import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;

import ch.unizh.ini.jaer.chip.retina.DVSUserControlPanel;
import net.sf.jaer.biasgen.Biasgen;
import net.sf.jaer.biasgen.PotTweaker;
import net.sf.jaer.chip.AEChip;

/**
 * User-friendly bias tweaks for the eEBV / PSGX320 (GenX320).
 */
public class EEBVUserControlPanel extends DVSUserControlPanel {

    private static final String HELP_HTML = "<html>This panel tweaks bias values around the nominal ones loaded from "
            + "preferences/XML. <b>Changes are not permanent</b> until settings are saved. "
            + "On save (or restart after save), these become the new nominal (slider center).<br>"
            + "Values are bias counts, not calibrated threshold % or filter Hz. "
            + "<b>File→Revert</b> restores the last save.";

    private final EEBVConfig config;
    private final PotTweaker highpassTweaker = new PotTweaker();
    private final JLabel thresholdValueLabel = new JLabel();
    private final JLabel bandwidthValueLabel = new JLabel();
    private final JLabel highpassValueLabel = new JLabel();
    private final JLabel refractoryValueLabel = new JLabel();

    public EEBVUserControlPanel(EEBVConfig config) {
        super(config.getChip() instanceof AEChip ae ? ae : null, config, false);
        this.config = config;
        finishInit();
        config.getSupport().addPropertyChangeListener(highpassTweaker);
        syncFromConfig();
    }

    @Override
    protected String helpHtml() {
        return HELP_HTML;
    }

    @Override
    protected void configureTweakers() {
        configurePotTweaker(thresholdTweaker, "Brightness change threshold", "Lower / more events",
                "Higher / less events",
                "Raises both ON and OFF contrast thresholds (diff_on and diff_off).");
        configurePotTweaker(onOffBalanceTweaker, "ON/OFF balance", "More OFF", "More ON",
                "Shifts ON vs OFF threshold: right lowers the ON threshold and raises the OFF threshold.");
        configurePotTweaker(bandwidthTweaker, "Pixel low-pass", "Slower", "Faster",
                "Pixel low-pass (fo): faster = wider bandwidth, more flicker/noise.");
        configurePotTweaker(maxFiringRateTweaker, "Maximum firing rate", "Slower", "Faster",
                "Pixel refractory period (refr): right shortens the dead time, more events.");
        configurePotTweaker(highpassTweaker, "Pixel high-pass", "Pass slow changes", "Reject slow / background",
                "Pixel high-pass (hpf): right rejects more slow change.");
        highpassTweaker.addChangeListener(e -> {
            if (!updatingFromConfig) {
                config.setHighpassTweak(highpassTweaker.getValue());
                updateChipSpecificLabels();
            }
        });
    }

    @Override
    protected void addExtraControls(JPanel extra) {
        extra.add(Box.createVerticalStrut(6));
        extra.add(highpassTweaker);
        extra.add(highpassValueLabel);
        extra.add(thresholdValueLabel);
        extra.add(bandwidthValueLabel);
        extra.add(refractoryValueLabel);
        for (JLabel lab : new JLabel[]{thresholdValueLabel, bandwidthValueLabel, highpassValueLabel,
            refractoryValueLabel}) {
            lab.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        stretchHorizontal(highpassTweaker);
    }

    void syncFromConfig() {
        syncFromTweaks();
        updatingFromConfig = true;
        try {
            highpassTweaker.setValue(config.getHighpassTweak());
        } finally {
            updatingFromConfig = false;
        }
        updateChipSpecificLabels();
    }

    @Override
    protected void updateChipSpecificLabels() {
        if (config == null) {
            return;
        }
        thresholdValueLabel.setText("diff_on " + offset(EEBVConfig.DIFF_ON)
                + "    diff_off " + offset(EEBVConfig.DIFF_OFF));
        bandwidthValueLabel.setText("fo " + offset(EEBVConfig.FO));
        refractoryValueLabel.setText("refr " + offset(EEBVConfig.REFR));
        highpassValueLabel.setText("hpf " + offset(EEBVConfig.HPF));
    }

    private String offset(int bias) {
        final int current = config.getBias(bias);
        return String.format("%d (%+d from saved)", current, current - config.getSavedBias(bias));
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        super.propertyChange(evt);
        final String name = evt.getPropertyName();
        if (Biasgen.PROPERTY_CHANGE_PREFERENCES_LOADED.equals(name)
                || Biasgen.PROPERTY_CHANGE_PREFERENCES_STORED.equals(name)) {
            syncFromConfig();
        } else if (EEBVConfig.PROPERTY_HIGHPASS_TWEAK.equals(name) && evt.getNewValue() instanceof Float v) {
            updatingFromConfig = true;
            highpassTweaker.setValue(v);
            updatingFromConfig = false;
            updateChipSpecificLabels();
        } else if (EEBVConfig.PROPERTY_BIAS.equals(name)) {
            updateChipSpecificLabels();
        }
    }
}
