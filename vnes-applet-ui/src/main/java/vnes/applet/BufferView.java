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
import java.awt.image.*;
import javax.swing.*;

import vnes.emulator.ui.ScreenView;

/**
 * BufferView implementation for the Applet UI.
 */
public class BufferView extends JPanel implements ScreenView {
    private static final int DEFAULT_WIDTH = 256;
    private static final int DEFAULT_HEIGHT = 240;
    
    private BufferedImage image;
    private int[] buffer;
    private int width;
    private int height;
    private int scaleMode;
    private boolean showFPS;
    private int bgColor = Color.BLACK.getRGB();
    private int scale = 1;
    
    /**
     * Creates a new BufferView with default dimensions.
     */
    public BufferView() {
        this(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }
    
    /**
     * Creates a new BufferView with the specified dimensions.
     * 
     * @param width The width of the buffer
     * @param height The height of the buffer
     */
    public BufferView(int width, int height) {
        this.width = width;
        this.height = height;
        
        setPreferredSize(new Dimension(width, height));
        setFocusable(true);
        
        createBuffer();
    }
    
    /**
     * Creates the buffer and image.
     */
    private void createBuffer() {
        buffer = new int[width * height];
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        // Initialize the buffer with the background color
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = bgColor;
        }
        
        // Get the raster from the image
        DataBufferInt dbi = (DataBufferInt) image.getRaster().getDataBuffer();
        int[] raster = dbi.getData();
        
        // Copy the buffer to the raster
        System.arraycopy(buffer, 0, raster, 0, buffer.length);
    }
    
    /**
     * Sets the scale factor for the buffer view.
     * 
     * @param scale The scale factor
     */
    public void setScale(int scale) {
        this.scale = scale;
        setPreferredSize(new Dimension(width * scale, height * scale));
        revalidate();
    }
    
    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        if (image != null) {
            g.drawImage(image, 0, 0, width * scale, height * scale, null);
        }
    }
    
    @Override
    public void init() {
        // Initialize the buffer view
    }
    
    @Override
    public int[] getBuffer() {
        return buffer;
    }
    
    @Override
    public int getBufferWidth() {
        return width;
    }
    
    @Override
    public int getBufferHeight() {
        return height;
    }
    
    @Override
    public void imageReady(boolean skipFrame) {
        if (!skipFrame) {
            // Get the raster from the image
            DataBufferInt dbi = (DataBufferInt) image.getRaster().getDataBuffer();
            int[] raster = dbi.getData();
            
            // Copy the buffer to the raster
            System.arraycopy(buffer, 0, raster, 0, buffer.length);
            
            // Repaint the component
            repaint();
        }
    }
    
    @Override
    public boolean scalingEnabled() {
        return scaleMode != 0;
    }
    
    @Override
    public boolean useHWScaling() {
        return false;
    }
    
    @Override
    public int getScaleMode() {
        return scaleMode;
    }
    
    @Override
    public void setScaleMode(int newMode) {
        scaleMode = newMode;
    }
    
    @Override
    public int getScaleModeScale(int mode) {
        return scale;
    }
    
    @Override
    public void setFPSEnabled(boolean value) {
        showFPS = value;
    }
    
    @Override
    public void setBgColor(int color) {
        bgColor = color;
    }
    
    @Override
    public void destroy() {
        buffer = null;
        image = null;
    }
}
