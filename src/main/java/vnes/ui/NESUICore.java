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

import vnes.input.InputCallback;
import vnes.input.InputHandler;
import vnes.NES;

/**
 * Platform-agnostic UI interface for the NES emulator.
 * This interface defines the core functionality required by any UI implementation,
 * without dependencies on specific UI frameworks like AWT or Compose.
 */
public interface NESUICore {
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
