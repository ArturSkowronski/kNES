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

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

import vnes.emulator.input.InputHandler;
import vnes.emulator.NES;
import vnes.emulator.ui.ScreenView;

/**
 * Main UI class for the Applet implementation.
 */
public class AppletUI extends JPanel implements InputHandler {
    private NES nes;
    private BufferView bufferView;

    /**
     * Creates a new AppletUI.
     */
    public AppletUI() {
        setLayout(new BorderLayout());
        setFocusable(true);
    }

    /**
     * Initializes the UI with the specified NES instance.
     * 
     * @param nes The NES instance to use
     * @param bufferView The BufferView to use for rendering
     */
    public void init(NES nes, BufferView bufferView) {
        this.nes = nes;
        this.bufferView = bufferView;

        // Add the buffer view to the center of the panel
        add(bufferView, BorderLayout.CENTER);

        // Request focus for keyboard input
        bufferView.requestFocus();
    }

    @Override
    public short getKeyState(int padKey) {
        // Delegate to the input handler
        return 0;
    }

    @Override
    public void mapKey(int padKey, int deviceKey) {
        // Delegate to the input handler
    }

    @Override
    public void reset() {
        // Reset the UI state
    }

    @Override
    public void update() {
        // Update the UI state
    }

    @Override
    public void destroy() {
        // Clean up resources
        if (bufferView != null) {
            bufferView.destroy();
            bufferView = null;
        }

        nes = null;
    }
}
