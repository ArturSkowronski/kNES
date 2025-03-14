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

import vnes.ui.DisplayBuffer;

/**
 * Adapter class that bridges the gap between BufferView and DisplayBuffer.
 * This class implements the platform-agnostic DisplayBuffer interface
 * and delegates to an AWT-specific BufferView instance.
 */
public class BufferViewAdapter implements DisplayBuffer {
    private final BufferView bufferView;
    
    /**
     * Create a new BufferViewAdapter that wraps the specified BufferView.
     * 
     * @param bufferView The BufferView to wrap
     */
    public BufferViewAdapter(BufferView bufferView) {
        this.bufferView = bufferView;
    }
    
    /**
     * Get the wrapped BufferView.
     * 
     * @return The wrapped BufferView
     */
    public BufferView getBufferView() {
        return bufferView;
    }
    
    @Override
    public void init(int width, int height) {
        bufferView.init();
    }
    
    @Override
    public void setPixel(int x, int y, int color) {
        // BufferView doesn't have a direct setPixel method, so we need to calculate the index
        int index = y * bufferView.getBufferWidth() + x;
        int[] buffer = bufferView.getBuffer();
        if (buffer != null && index >= 0 && index < buffer.length) {
            buffer[index] = color;
        }
    }
    
    @Override
    public void setPixels(int[] pixelData) {
        // Copy the pixel data to the buffer view
        int[] buffer = bufferView.getBuffer();
        if (buffer != null) {
            System.arraycopy(pixelData, 0, buffer, 0, Math.min(pixelData.length, buffer.length));
        }
    }
    
    @Override
    public int[] getPixels() {
        return bufferView.getBuffer();
    }
    
    @Override
    public int getWidth() {
        return bufferView.getWidth();
    }
    
    @Override
    public int getHeight() {
        return bufferView.getHeight();
    }
    
    @Override
    public void render(boolean skipFrame) {
        bufferView.imageReady(skipFrame);
    }
    
    @Override
    public void destroy() {
        bufferView.destroy();
    }
}
