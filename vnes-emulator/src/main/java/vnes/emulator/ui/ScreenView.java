package vnes.emulator.ui;
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

/**
 * Platform-agnostic interface for screen display operations.
 * This interface defines methods for manipulating and displaying the NES screen
 * without dependencies on specific UI frameworks.
 */
public interface ScreenView {
    
    /**
     * Initialize the screen view.
     */
    void init();
    
    /**
     * Get the buffer of pixel data for the screen.
     * 
     * @return Array of pixel data in RGB format
     */
    int[] getBuffer();
    
    /**
     * Get the width of the buffer.
     * 
     * @return The width in pixels
     */
    int getBufferWidth();
    
    /**
     * Get the height of the buffer.
     * 
     * @return The height in pixels
     */
    int getBufferHeight();
    
    /**
     * Notify that an image is ready to be displayed.
     * 
     * @param skipFrame Whether this frame should be skipped
     */
    void imageReady(boolean skipFrame);
    
    /**
     * Check if scaling is enabled for this screen view.
     * 
     * @return true if scaling is enabled, false otherwise
     */
    boolean scalingEnabled();
    
    /**
     * Check if hardware scaling is being used.
     * 
     * @return true if hardware scaling is being used, false otherwise
     */
    boolean useHWScaling();
    
    /**
     * Get the current scale mode.
     * 
     * @return The current scale mode
     */
    int getScaleMode();
    
    /**
     * Set the scale mode for the screen view.
     * 
     * @param newMode The new scale mode
     */
    void setScaleMode(int newMode);
    
    /**
     * Get the scale factor for a given scale mode.
     * 
     * @param mode The scale mode
     * @return The scale factor
     */
    int getScaleModeScale(int mode);
    
    /**
     * Set whether to show the FPS counter.
     * 
     * @param val true to show FPS, false to hide
     */
    void setFPSEnabled(boolean val);
    
    /**
     * Set the background color.
     * 
     * @param color The background color in RGB format
     */
    void setBgColor(int color);
    
    /**
     * Clean up resources used by this screen view.
     */
    void destroy();
}
