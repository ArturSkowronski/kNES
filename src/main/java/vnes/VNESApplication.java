package vnes;
/*
vNES
Copyright © 2006-2013 Open Emulation Project

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import javax.swing.*;
import java.awt.*;
import java.io.File;

import vnes.compose.ComposeMainKt;

/**
 * Main application entry point for vNES.
 * This class provides a launcher that allows the user to choose between
 * the Applet UI and the Compose UI.
 */
public class VNESApplication {

    /**
     * Main method.
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        // Set look and feel to system default
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Create the launcher frame
        JFrame frame = new JFrame("vNES Launcher");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 300);
        frame.setLocationRelativeTo(null);

        // Create the content panel
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());

        // Create the title label
        JLabel titleLabel = new JLabel("vNES - NES Emulator", JLabel.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Create the button panel
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(2, 1, 10, 10));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        // Create the Compose UI button
        JButton composeButton = new JButton("Launch Compose UI");
        composeButton.addActionListener(e -> {
            frame.dispose();
            ComposeMainKt.main();
        });
        buttonPanel.add(composeButton);

        // Add the button panel to the content panel
        panel.add(buttonPanel, BorderLayout.CENTER);

        // Create the ROM selection panel
        JPanel romPanel = new JPanel();
        romPanel.setLayout(new FlowLayout());

        JLabel romLabel = new JLabel("ROM: ");
        romPanel.add(romLabel);

        JTextField romField = new JTextField(20);
        romPanel.add(romField);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setDialogTitle("Select NES ROM");

            if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                romField.setText(selectedFile.getAbsolutePath());
            }
        });
        romPanel.add(browseButton);

        // Add the ROM selection panel to the content panel
        panel.add(romPanel, BorderLayout.SOUTH);

        // Set the content pane
        frame.setContentPane(panel);

        // Show the frame
        frame.setVisible(true);
    }
}
