package vnes.applet;
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

import java.applet.Applet;
import java.awt.*;
import javax.swing.*;

import vnes.emulator.NES;
import vnes.emulator.NESUIFactory;

/**
 * Applet launcher for the NES emulator.
 */
public class AppletLauncher extends JApplet {
    private NES nes;
    private AppletUIFactory uiFactory;
    
    /**
     * Initializes the applet.
     */
    @Override
    public void init() {
        try {
            // Set up the UI factory
            uiFactory = new AppletUIFactory();
            
            // Create the NES instance with the UI factory
            nes = new NES(uiFactory);
            
            // Set up the content pane
            Container contentPane = getContentPane();
            contentPane.setLayout(new BorderLayout());
            
            // Add the applet UI to the content pane
            AppletUI appletUI = uiFactory.getAppletUI();
            contentPane.add(appletUI, BorderLayout.CENTER);
            
            // Initialize the UI
            BufferView bufferView = (BufferView) uiFactory.createScreenView(1);
            appletUI.init(nes, bufferView);
            
            // Load a ROM if specified
            String romPath = getParameter("rom");
            if (romPath != null && !romPath.isEmpty()) {
                nes.loadRom(romPath);
            }
            
            // Set up the applet size
            setSize(256, 240);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Starts the applet.
     */
    @Override
    public void start() {
        if (nes != null) {
            nes.startEmulation();
        }
    }
    
    /**
     * Stops the applet.
     */
    @Override
    public void stop() {
        if (nes != null) {
            nes.stopEmulation();
        }
    }
    
    /**
     * Destroys the applet.
     */
    @Override
    public void destroy() {
        if (nes != null) {
            nes.destroy();
            nes = null;
        }
        
        uiFactory = null;
    }
    
    /**
     * Main method for running as a standalone application.
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        JFrame frame = new JFrame("vNES Applet");
        AppletLauncher applet = new AppletLauncher();
        
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(applet);
        
        applet.init();
        applet.start();
        
        frame.pack();
        frame.setVisible(true);
    }
}
