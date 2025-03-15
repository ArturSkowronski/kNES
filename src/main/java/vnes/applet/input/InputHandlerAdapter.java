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

import vnes.emulator.InputCallback;
import vnes.emulator.InputHandler;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter class that bridges the gap between InputCallback and InputHandler.
 * This class implements the AWT-specific InputHandler interface
 * and delegates to a platform-agnostic InputCallback instance.
 */
public class InputHandlerAdapter implements InputHandler, KeyListener {
    private final InputCallback callback;
    private final Map<Integer, Integer> keyMap;
    private final int playerIndex;
    private final short[] keyState;
    
    /**
     * Create a new InputHandlerAdapter that wraps the specified InputCallback.
     * 
     * @param callback The InputCallback to wrap
     * @param playerIndex The player index (0 for player 1, 1 for player 2)
     */
    public InputHandlerAdapter(InputCallback callback, int playerIndex) {
        this.callback = callback;
        this.playerIndex = playerIndex;
        this.keyMap = new HashMap<>();
        this.keyState = new short[InputHandler.NUM_KEYS];
    }
    
    @Override
    public short getKeyState(int padKey) {
        return keyState[padKey];
    }
    
    @Override
    public void keyPressed(KeyEvent e) {
        Integer buttonCode = keyMap.get(e.getKeyCode());
        if (buttonCode != null) {
            keyState[buttonCode] = 1;
            callback.buttonDown(buttonCode);
        }
    }
    
    @Override
    public void keyReleased(KeyEvent e) {
        Integer buttonCode = keyMap.get(e.getKeyCode());
        if (buttonCode != null) {
            keyState[buttonCode] = 0;
            callback.buttonUp(buttonCode);
        }
    }
    
    @Override
    public void keyTyped(KeyEvent e) {
        // Not used
    }
    
    @Override
    public void mapKey(int buttonCode, int keyCode) {
        keyMap.put(keyCode, buttonCode);
    }
    
    @Override
    public void update() {
        // Not used in this adapter
    }
    
    @Override
    public void reset() {
        // Reset all button states
        for (int i = 0; i < keyState.length; i++) {
            keyState[i] = 0;
            callback.buttonUp(i);
        }
    }
    
    /**
     * Clean up resources used by this adapter.
     */
    public void destroy() {
        keyMap.clear();
    }
}
