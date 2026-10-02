package ncslab.chip;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import ncslab.serial.EEBVHardwareInterface;
import net.sf.jaer.biasgen.Biasgen;
import net.sf.jaer.hardwareinterface.HardwareInterface;
import net.sf.jaer.hardwareinterface.HardwareInterfaceException;

/**
 * Raw GenX320 bias sliders and sensor commands (pixel mask, LED, free text)
 * for the eEBV / PSGX320.
 */
public class EEBVControlPanel extends JPanel implements PropertyChangeListener {

    private static final Pattern BIAS_REPLY = Pattern.compile("-B(\\d+)=(\\d+)");

    private final EEBVConfig config;
    private final JSlider[] sliders = new JSlider[EEBVConfig.NUM_BIASES];
    private final JLabel[] valueLabels = new JLabel[EEBVConfig.NUM_BIASES];
    private final JTextArea replyArea = new JTextArea(8, 40);
    private final JTextField commandField = new JTextField();
    private boolean updating;

    public EEBVControlPanel(EEBVConfig config) {
        super(new GridBagLayout());
        this.config = config;
        int row = 0;

        final GridBagConstraints help = new GridBagConstraints();
        help.gridx = 0;
        help.gridy = row++;
        help.gridwidth = 3;
        help.weightx = 1.0;
        help.fill = GridBagConstraints.HORIZONTAL;
        help.insets = new Insets(4, 4, 8, 4);
        add(new JLabel("<html>GenX320 bias values (0–127) sent to the sensor <b>while you drag</b>, as "
                + "<code>!B&lt;i&gt;=&lt;v&gt;</code>.<br>"
                + "Prefer the <b>User-Friendly Controls</b> tab for threshold, ON/OFF balance and filters.<br>"
                + "<b>Revert</b> or <b>File → Load settings</b> restores saved preferences/XML."), help);

        for (int i = 0; i < EEBVConfig.NUM_BIASES; i++) {
            addBiasRow(row++, i);
        }

        final JPanel buttons = new JPanel(new java.awt.GridLayout(0, 3, 6, 4));
        buttons.setBorder(BorderFactory.createTitledBorder("Sensor"));
        buttons.add(button("Read biases from sensor",
                "Reads the values the sensor is using (?B) and makes them the current settings",
                this::readBiasesFromSensor));
        final JComboBox<String> presets = new JComboBox<>(EEBVConfig.PRESETS);
        presets.setToolTipText("Bias presets built into the sensor firmware");
        buttons.add(presets);
        buttons.add(button("Load preset",
                "Loads the chosen firmware bias preset (!BD<n>) and reads the values back",
                () -> loadPreset(presets.getSelectedIndex())));
        buttons.add(button("Mask hot pixels",
                "Keep the sensor still in a static scene: detects pixels that fire continuously and disables"
                + " them (!EMH). Forgotten when the sensor is reset or unplugged.",
                () -> runCommand("!EMH", 4000)));
        buttons.add(button("Clear pixel mask", "Re-enables all pixels (!EMC)", () -> runCommand("!EMC", 800)));
        buttons.add(button("LED on", "!L+", () -> runCommand("!L+", 400)));
        buttons.add(button("LED off", "!L-", () -> runCommand("!L-", 400)));
        final GridBagConstraints bc = new GridBagConstraints();
        bc.gridx = 0;
        bc.gridy = row++;
        bc.gridwidth = 3;
        bc.fill = GridBagConstraints.HORIZONTAL;
        bc.insets = new Insets(8, 4, 4, 4);
        add(buttons, bc);

        final JPanel commandRow = new JPanel(new java.awt.BorderLayout(6, 0));
        commandRow.add(new JLabel("Command"), java.awt.BorderLayout.WEST);
        commandField.setToolTipText("Any sensor command, e.g. ?? for the list. Sent with a line feed.");
        commandRow.add(commandField, java.awt.BorderLayout.CENTER);
        final JButton send = new JButton("Send");
        commandRow.add(send, java.awt.BorderLayout.EAST);
        final Runnable sendTyped = () -> {
            final String cmd = commandField.getText().trim();
            if (!cmd.isEmpty()) {
                runCommand(cmd, 800);
            }
        };
        send.addActionListener(e -> sendTyped.run());
        commandField.addActionListener(e -> sendTyped.run());
        final GridBagConstraints cc = new GridBagConstraints();
        cc.gridx = 0;
        cc.gridy = row++;
        cc.gridwidth = 3;
        cc.fill = GridBagConstraints.HORIZONTAL;
        cc.insets = new Insets(4, 4, 4, 4);
        add(commandRow, cc);

        replyArea.setEditable(false);
        replyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        final GridBagConstraints rc = new GridBagConstraints();
        rc.gridx = 0;
        rc.gridy = row;
        rc.gridwidth = 3;
        rc.weightx = 1.0;
        rc.weighty = 1.0;
        rc.fill = GridBagConstraints.BOTH;
        rc.insets = new Insets(4, 4, 4, 4);
        add(new JScrollPane(replyArea), rc);

        config.getSupport().addPropertyChangeListener(this);
        refreshFromBiases();
    }

    private void addBiasRow(int row, final int bias) {
        final JLabel valueLabel = new JLabel();
        final JSlider slider = new JSlider(EEBVConfig.BIAS_MIN, EEBVConfig.BIAS_MAX, EEBVConfig.BIAS_MIN);
        slider.setMajorTickSpacing(32);
        slider.setPaintTicks(true);
        sliders[bias] = slider;
        valueLabels[bias] = valueLabel;

        final GridBagConstraints labelC = new GridBagConstraints();
        labelC.gridx = 0;
        labelC.gridy = row;
        labelC.anchor = GridBagConstraints.WEST;
        labelC.insets = new Insets(2, 4, 2, 8);
        add(new JLabel(bias + "  " + EEBVConfig.biasName(bias)), labelC);

        final GridBagConstraints sliderC = new GridBagConstraints();
        sliderC.gridx = 1;
        sliderC.gridy = row;
        sliderC.weightx = 1.0;
        sliderC.fill = GridBagConstraints.HORIZONTAL;
        sliderC.insets = new Insets(2, 4, 2, 8);
        add(slider, sliderC);

        final GridBagConstraints valueC = new GridBagConstraints();
        valueC.gridx = 2;
        valueC.gridy = row;
        valueC.anchor = GridBagConstraints.EAST;
        valueC.insets = new Insets(2, 4, 2, 4);
        add(valueLabel, valueC);

        slider.addChangeListener(e -> {
            if (updating) {
                return;
            }
            valueLabel.setText(Integer.toString(slider.getValue()));
            config.setBias(bias, slider.getValue());
        });
    }

    private static JButton button(String text, String tip, Runnable action) {
        final JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    void refreshFromBiases() {
        updating = true;
        try {
            for (int i = 0; i < EEBVConfig.NUM_BIASES; i++) {
                sliders[i].setValue(config.getBias(i));
                valueLabels[i].setText(Integer.toString(config.getBias(i)));
            }
        } finally {
            updating = false;
        }
    }

    private EEBVHardwareInterface openInterface() {
        final HardwareInterface hw = config.getChip().getHardwareInterface();
        if (hw instanceof EEBVHardwareInterface eebv && eebv.isOpen()) {
            return eebv;
        }
        appendReply("Sensor is not open. Choose it from the Interface menu first.");
        return null;
    }

    /** Sends on a worker thread so a slow reply does not block Swing. */
    private void runCommand(final String command, final int waitMs) {
        final EEBVHardwareInterface hw = openInterface();
        if (hw == null) {
            return;
        }
        appendReply("> " + command);
        final Thread t = new Thread(() -> {
            try {
                final List<String> reply = hw.sendCommandForReply(command, waitMs);
                appendReply(reply.isEmpty() ? "(no reply)" : String.join("\n", reply));
            } catch (HardwareInterfaceException e) {
                appendReply("Failed: " + e.getMessage());
            }
        }, "jaer-eebv-command");
        t.setDaemon(true);
        t.start();
    }

    private void readBiasesFromSensor() {
        readBiasesFromSensor(null);
    }

    private void loadPreset(int preset) {
        readBiasesFromSensor("!BD" + preset);
    }

    /** Optionally sends {@code before}, then adopts the sensor's bias values. */
    private void readBiasesFromSensor(final String before) {
        final EEBVHardwareInterface hw = openInterface();
        if (hw == null) {
            return;
        }
        appendReply("> " + (before == null ? "" : before + ", ") + "?B");
        final Thread t = new Thread(() -> {
            try {
                if (before != null) {
                    hw.sendCommandForReply(before, 400);
                }
                final List<String> reply = hw.sendCommandForReply("?B", 800);
                final int[] values = new int[EEBVConfig.NUM_BIASES];
                Arrays.fill(values, -1);
                int found = 0;
                for (String line : reply) {
                    final Matcher m = BIAS_REPLY.matcher(line);
                    if (m.find()) {
                        final int i = Integer.parseInt(m.group(1));
                        if (i < values.length) {
                            values[i] = Integer.parseInt(m.group(2));
                            found++;
                        }
                    }
                }
                appendReply(String.join("\n", reply));
                if (found == 0) {
                    appendReply("No bias values in the reply.");
                    return;
                }
                SwingUtilities.invokeLater(() -> config.setBiasesFromSensor(values));
            } catch (HardwareInterfaceException e) {
                appendReply("Failed: " + e.getMessage());
            }
        }, "jaer-eebv-command");
        t.setDaemon(true);
        t.start();
    }

    private void appendReply(final String text) {
        SwingUtilities.invokeLater(() -> {
            replyArea.append(text + "\n");
            replyArea.setCaretPosition(replyArea.getDocument().getLength());
        });
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        final String name = evt.getPropertyName();
        if (Biasgen.PROPERTY_CHANGE_PREFERENCES_LOADED.equals(name) || EEBVConfig.PROPERTY_BIAS.equals(name)) {
            refreshFromBiases();
        }
    }
}
