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
 * Platform-agnostic interface for display buffer operations.
 * This interface defines methods for manipulating pixel data without
 * dependencies on specific UI frameworks.
 */
public interface DisplayBuffer {
    /**
     * Initialize the display buffer with the specified dimensions.
     * 
     * @param width The width of the buffer in pixels
     * @param height The height of the buffer in pixels
     */
    void init(int width, int height);
    
    /**
     * Set a pixel in the buffer to the specified color.
     * 
     * @param x The x-coordinate of the pixel
     * @param y The y-coordinate of the pixel
     * @param color The color value in RGB format
     */
    void setPixel(int x, int y, int color);
    
    /**
     * Fill the buffer with the specified pixel data.
     * 
     * @param pixelData Array of pixel data in RGB format
     */
    void setPixels(int[] pixelData);
    
    /**
     * Get the current pixel data from the buffer.
     * 
     * @return Array of pixel data in RGB format
     */
    int[] getPixels();
    
    /**
     * Get the width of the buffer.
     * 
     * @return The width in pixels
     */
    int getWidth();
    
    /**
     * Get the height of the buffer.
     * 
     * @return The height in pixels
     */
    int getHeight();
    
    /**
     * Render the buffer to the display.
     * 
     * @param skipFrame Whether this frame should be skipped
     */
    void render(boolean skipFrame);
    
    /**
     * Clean up resources used by this buffer.
     */
    void destroy();
}
