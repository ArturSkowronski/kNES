/*
 *
 *  * Copyright (C) 2025 Artur Skowroński
 *  * This file is part of kNES, a fork of vNES (GPLv3) rewritten in Kotlin.
 *  *
 *  * vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license.
 *  * This project is a reimplementation and extension of that work.
 *  *
 *  * kNES is licensed under the GNU General Public License v3.0.
 *  * See the LICENSE file for more details.
 *
 */

package knes.launcher;
import knes.applet.AppletMain;

import java.applet.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * AppletLauncher - A standalone application that can run the kNES applet
 * without requiring a browser or appletviewer.
 * 
 * This class creates a JFrame and embeds the kNES applet in it, providing
 * an AppletStub implementation to handle applet parameters.
 */
public class AppletLauncher {
    private static JFrame frame;
    private static AppletMain applet;
    private static AppletStubImpl stub;
    private static String romPath = null;
    
    public static void main(String[] args) {
        // Set security manager with permissions
        System.setProperty("java.security.policy", "all.policy");
        
        SwingUtilities.invokeLater(() -> {
            try {
                // Create a JFrame to host the applet
                frame = new JFrame("kNES - NES Emulator");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.setSize(512, 480);
                
                // Check if a ROM file was provided as an argument
                if (args.length > 0 && new File(args[0]).exists()) {
                    romPath = args[0];
                }
                
                // If no ROM file was provided, show a file chooser
                if (romPath == null) {
                    showWelcomeScreen();
                } else {
                    System.out.println(romPath);
                    launchEmulator(romPath);
                }
                
                // Center the frame on screen
                frame.setLocationRelativeTo(null);
                
                // Show the frame
                frame.setVisible(true);
                
                System.out.println("kNES Applet Launcher started successfully");
            } catch (Exception e) {
                System.err.println("Error launching kNES applet: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    private static void showWelcomeScreen() {
        JPanel welcomePanel = new JPanel(new BorderLayout());
        welcomePanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        
        JLabel titleLabel = new JLabel("kNES - Fork of vNES Emulator", JLabel.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        welcomePanel.add(titleLabel, BorderLayout.NORTH);
        
        JPanel centerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 0, 10, 0);
        
        JLabel infoLabel = new JLabel("<html><div style='text-align: center;'>No ROM file loaded.<br>Please select a NES ROM file to play.</div></html>", JLabel.CENTER);
        centerPanel.add(infoLabel, gbc);
        
        JButton openButton = new JButton("Open ROM File");
        openButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select NES ROM File");
            fileChooser.setFileFilter(new FileNameExtensionFilter("NES ROM Files (*.nes)", "nes"));
            
            // Try to set initial directory to roms/ if it exists
            File romsDir = new File("roms");
            if (romsDir.exists() && romsDir.isDirectory()) {
                fileChooser.setCurrentDirectory(romsDir);
            }
            
            int result = fileChooser.showOpenDialog(frame);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                frame.getContentPane().removeAll();
                launchEmulator(selectedFile.getAbsolutePath());
                frame.revalidate();
                frame.repaint();
            }
        });
        centerPanel.add(openButton, gbc);
        
        welcomePanel.add(centerPanel, BorderLayout.CENTER);
        
        JLabel footerLabel = new JLabel("<html><div style='text-align: center;'>vNES 2.16 © 2006-2013 Open Emulation Project<br>For updates visit www.openemulation.com<br>Use of this program subject to GNU GPL Version 3.</div></html>", JLabel.CENTER);
        footerLabel.setFont(new Font("Arial", Font.PLAIN, 10));
        welcomePanel.add(footerLabel, BorderLayout.SOUTH);
        
        frame.getContentPane().add(welcomePanel);
    }
    
    private static void launchEmulator(String romPath) {
        try {
            // Copy the selected ROM file to knes.nes in the project root
            File sourceRom = new File(romPath);
            File targetRom = new File("knes.nes");
            
            if (sourceRom.exists()) {
                // Copy the ROM file to knes.nes
                try (FileInputStream fis = new FileInputStream(sourceRom);
                     FileOutputStream fos = new FileOutputStream(targetRom)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                    
                    System.out.println("ROM file copied to knes.nes");
                } catch (IOException e) {
                    System.err.println("Error copying ROM file: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            // Create the applet instance
            applet = new AppletMain();
            
            // Create and set the AppletStub with the ROM path
            stub = new AppletStubImpl(applet, "knes.nes");
            applet.setStub(stub);
            
            // Initialize the applet
            applet.init();
            applet.start();
            
            // Add the applet to the frame
            frame.getContentPane().add(applet);
            
            System.out.println("Emulator launched with ROM: " + romPath);
        } catch (Exception e) {
            System.err.println("Error launching emulator with ROM: " + romPath);
            e.printStackTrace();
            showWelcomeScreen();
        }
    }
    
    /**
     * Implementation of AppletStub to provide the necessary environment for the applet
     */
    static class AppletStubImpl implements AppletStub {
        private final Applet applet;
        private final Map<String, String> parameters;
        
        public AppletStubImpl(Applet applet) {
            this(applet, null);
        }
        
        public AppletStubImpl(Applet applet, String romPath) {
            this.applet = applet;
            this.parameters = new HashMap<>();
            
            // Add default parameters that the kNES applet might need
            parameters.put("ROMPATH", "roms/");
            parameters.put("ROM", romPath != null ? new File(romPath).getName() : "");
            parameters.put("SCALE", "1");
            parameters.put("SOUND", "1");
        }
        
        @Override
        public boolean isActive() {
            return true;
        }
        
        @Override
        public URL getDocumentBase() {
            try {
                return new File(".").toURI().toURL();
            } catch (MalformedURLException e) {
                return null;
            }
        }
        
        @Override
        public URL getCodeBase() {
            return getDocumentBase();
        }
        
        @Override
        public String getParameter(String name) {
            return parameters.get(name);
        }
        
        @Override
        public AppletContext getAppletContext() {
            return null; // Not needed for basic functionality
        }
        
        @Override
        public void appletResize(int width, int height) {
            // Resize the applet if needed
            applet.setSize(width, height);
        }
    }
}
