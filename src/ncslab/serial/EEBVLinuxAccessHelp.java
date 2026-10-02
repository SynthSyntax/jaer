package ncslab.serial;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Linux how-to dialog for a serial port the user may not open: a udev rule
 * that grants access to the eEBV sensor's port.
 */
public final class EEBVLinuxAccessHelp {

    public static final String UDEV_RULES_FILE = "/etc/udev/rules.d/99-eebv-genx320.rules";
    public static final String UDEV_RULE = "SUBSYSTEM==\"tty\", ATTRS{idVendor}==\"cafe\", ATTRS{idProduct}==\"4001\","
            + " MODE=\"0666\", TAG+=\"uaccess\"";

    private static final AtomicBoolean SHOWN = new AtomicBoolean(false);

    private EEBVLinuxAccessHelp() {
    }

    static String installCommands() {
        return "sudo tee " + UDEV_RULES_FILE + " >/dev/null <<'EOF'\n"
                + UDEV_RULE + "\n"
                + "EOF\n"
                + "sudo udevadm control --reload-rules\n"
                + "sudo udevadm trigger\n";
    }

    /**
     * Once per JVM, on Linux, when {@code error} is an access-denied open
     * failure, posts the how-to dialog on the EDT. Safe to call from ViewLoop.
     */
    public static void maybeShowDialog(final Component parent, final Throwable error) {
        if (error == null || error.getMessage() == null
                || !error.getMessage().contains(EEBVHardwareInterface.ACCESS_DENIED)) {
            return;
        }
        if (!System.getProperty("os.name", "").contains("Linux")) {
            return;
        }
        if (!SHOWN.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(() -> showDialog(parent));
    }

    private static void showDialog(final Component parent) {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        final JLabel intro = new JLabel("<html>"
                + "jAER cannot open the eEBV / PSGX320 sensor (USB <code>cafe:4001</code>)."
                + " Linux denied access to its serial port.<br><br>"
                + "<b>1.</b> Copy the commands below.<br>"
                + "<b>2.</b> Paste them into a terminal and run them (enter your sudo password).<br>"
                + "<b>3.</b> Unplug the sensor, plug it back in, then choose it again from the"
                + " Interface menu.<br><br>"
                + "Alternatively add your user to the group that owns the port (<code>uucp</code> or"
                + " <code>dialout</code>) and log in again."
                + "</html>");
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(intro);
        panel.add(Box.createVerticalStrut(8));

        final JTextArea area = new JTextArea(installCommands());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        final JScrollPane scroll = new JScrollPane(area);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(640, 110));
        panel.add(scroll);
        panel.add(Box.createVerticalStrut(8));

        final JButton copy = new JButton("Copy commands");
        copy.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(area.getText()), null);
            copy.setText("Copied");
        });
        panel.add(copy);

        final JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        wrap.add(panel, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(parent, wrap, "Linux serial port permission for eEBV",
                JOptionPane.WARNING_MESSAGE);
    }
}
