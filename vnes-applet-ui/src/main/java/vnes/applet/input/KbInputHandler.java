package vnes.applet.input;
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

import java.awt.event.*;
import javax.swing.*;

import vnes.emulator.input.InputHandler;
import vnes.emulator.NES;

import static vnes.emulator.input.InputHandler.*;  // Import constants

/**
 * Keyboard input handler for the Applet UI.
 */
public class KbInputHandler implements InputHandler, KeyListener {
    private final NES nes;
    private final short[] keyState = new short[NUM_KEYS];
    private final int[] keyMapping = new int[NUM_KEYS];

    /**
     * Creates a new KbInputHandler.
     * 
     * @param nes The NES instance to use
     */
    public KbInputHandler(NES nes) {
        this.nes = nes;

        // Default key mappings
        mapKey(KEY_A, KeyEvent.VK_Z);
        mapKey(KEY_B, KeyEvent.VK_X);
        mapKey(KEY_START, KeyEvent.VK_ENTER);
        mapKey(KEY_SELECT, KeyEvent.VK_SPACE);
        mapKey(KEY_UP, KeyEvent.VK_UP);
        mapKey(KEY_DOWN, KeyEvent.VK_DOWN);
        mapKey(KEY_LEFT, KeyEvent.VK_LEFT);
        mapKey(KEY_RIGHT, KeyEvent.VK_RIGHT);
    }

    @Override
    public short getKeyState(int padKey) {
        return keyState[padKey];
    }

    @Override
    public void mapKey(int padKey, int deviceKey) {
        keyMapping[padKey] = deviceKey;
    }

    @Override
    public void reset() {
        for (int i = 0; i < keyState.length; i++) {
            keyState[i] = 0;
        }
    }

    @Override
    public void update() {
        // Update key states
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int keyCode = e.getKeyCode();

        for (int i = 0; i < keyMapping.length; i++) {
            if (keyMapping[i] == keyCode) {
                keyState[i] = 0x41;
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int keyCode = e.getKeyCode();

        for (int i = 0; i < keyMapping.length; i++) {
            if (keyMapping[i] == keyCode) {
                keyState[i] = 0x40;
            }
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Not used
    }

    /**
     * Registers this input handler with a component.
     * 
     * @param component The component to register with
     */
    public void registerWith(JComponent component) {
        component.addKeyListener(this);
    }

    @Override
    public void destroy() {
        // Clean up resources
    }
}
