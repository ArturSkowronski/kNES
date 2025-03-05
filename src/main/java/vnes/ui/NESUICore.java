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
import NES;

/**
 * Platform-agnostic UI interface for the NES emulator.
 * This interface defines the core functionality required by any UI implementation,
 * without dependencies on specific UI frameworks like AWT or Compose.
 */
public interface NESUICore {
    /**
     * Initialize the display with the specified dimensions.
     * 
     * @param width The width of the display in pixels
     * @param height The height of the display in pixels
     */
    void initDisplay(int width, int height);
    
    /**
     * Get the display buffer for this UI.
     * 
     * @return The display buffer
     */
    DisplayBuffer getDisplayBuffer();
    
    /**
     * Render a frame to the display.
     * 
     * @param skipFrame Whether this frame should be skipped
     */
    void renderFrame(boolean skipFrame);
    
    /**
     * Register an input callback for this UI.
     * 
     * @param callback The input callback to register
     * @param playerIndex The player index (0 for player 1, 1 for player 2)
     */
    void registerInputCallback(InputCallback callback, int playerIndex);
    
    /**
     * Set the input handler for this UI (legacy method).
     * 
     * @param handler The input handler to use
     * @param playerIndex The player index (0 for player 1, 1 for player 2)
     */
    void setInputHandler(InputHandler handler, int playerIndex);
    
    /**
     * Get the NES instance associated with this UI.
     * 
     * @return The NES instance
     */
    NES getNES();
    
    /**
     * Get the width of the UI component.
     * 
     * @return The width in pixels
     */
    int getWidth();
    
    /**
     * Get the height of the UI component.
     * 
     * @return The height in pixels
     */
    int getHeight();
    
    /**
     * Show an error message to the user.
     * 
     * @param message The error message to display
     */
    void showErrorMsg(String message);
    
    /**
     * Show the ROM loading progress.
     * 
     * @param percentComplete The percentage of loading completed
     */
    void showLoadProgress(int percentComplete);
    
    /**
     * Clean up resources used by this UI.
     */
    void destroy();
}
