package vnes.ui;
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

import InputHandler;
import InputHandlerAdapter;
import NES;

/**
 * Abstract base implementation of the NESUICore interface.
 * This class provides common functionality for all UI implementations.
 */
public abstract class AbstractNESUI implements NESUICore {
    protected NES nes;
    protected DisplayBuffer displayBuffer;
    protected InputCallback[] inputCallbacks;
    protected InputHandler[] inputHandlers;
    
    /**
     * Create a new AbstractNESUI.
     * 
     * @param nes The NES instance to use
     */
    public AbstractNESUI(NES nes) {
        this.nes = nes;
        this.inputCallbacks = new InputCallback[2];
        this.inputHandlers = new InputHandler[2];
    }
    
    @Override
    public NES getNES() {
        return nes;
    }
    
    @Override
    public DisplayBuffer getDisplayBuffer() {
        return displayBuffer;
    }
    
    @Override
    public void renderFrame(boolean skipFrame) {
        if (displayBuffer != null) {
            displayBuffer.render(skipFrame);
        }
    }
    
    @Override
    public void registerInputCallback(InputCallback callback, int playerIndex) {
        if (playerIndex >= 0 && playerIndex < inputCallbacks.length) {
            inputCallbacks[playerIndex] = callback;
            
            // Create an adapter for the callback if needed
            if (callback != null && inputHandlers[playerIndex] == null) {
                inputHandlers[playerIndex] = new InputHandlerAdapter(callback, playerIndex);
            }
        }
    }
    
    @Override
    public void setInputHandler(InputHandler handler, int playerIndex) {
        if (playerIndex >= 0 && playerIndex < inputHandlers.length) {
            inputHandlers[playerIndex] = handler;
        }
    }
    
    @Override
    public void destroy() {
        // Clean up resources
        if (displayBuffer != null) {
            displayBuffer.destroy();
            displayBuffer = null;
        }
        
        for (int i = 0; i < inputHandlers.length; i++) {
            if (inputHandlers[i] != null) {
                inputHandlers[i].reset();
                if (inputHandlers[i] instanceof InputHandlerAdapter) {
                    ((InputHandlerAdapter) inputHandlers[i]).destroy();
                }
                inputHandlers[i] = null;
            }
            inputCallbacks[i] = null;
        }
        
        nes = null;
    }
}
