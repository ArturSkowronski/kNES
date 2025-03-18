package vnes.compose

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

import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.JPanel
import vnes.emulator.ui.ScreenView

/**
 * Screen view for the Compose UI.
 * 
 * Note: This is a temporary implementation using Swing instead of Compose
 * until the Compose UI dependencies are properly configured.
 */
class ComposeScreenView(private var scale: Int) : ScreenView {
    private val width = 256
    private val height = 240
    private var buffer: IntArray = IntArray(width * height)
    private var image: BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private var scaleMode = 0
    private var showFPS = false
    private var bgColor = 0xFF333333.toInt()
    private var panel: ScreenPanel? = null

    init {
        // Initialize the buffer with the background color
        buffer.fill(bgColor)

        // Create the image from the buffer
        updateImage()

        // Create the panel
        panel = ScreenPanel()
    }

    /**
     * Updates the image from the current buffer
     */
    private fun updateImage() {
        // Get the image's data buffer
        val imageData = (image.raster.dataBuffer as DataBufferInt).data

        // Copy the buffer data to the image's data buffer
        System.arraycopy(buffer, 0, imageData, 0, buffer.size)
    }

    /**
     * Initializes the screen view.
     */
    override fun init() {
        // Initialize the screen view
    }

    /**
     * Gets the buffer of pixel data for the screen.
     * 
     * @return Array of pixel data in RGB format
     */
    override fun getBuffer(): IntArray {
        return buffer
    }

    /**
     * Gets the width of the buffer.
     * 
     * @return The width in pixels
     */
    override fun getBufferWidth(): Int {
        return width
    }

    /**
     * Gets the height of the buffer.
     * 
     * @return The height in pixels
     */
    override fun getBufferHeight(): Int {
        return height
    }

    /**
     * Notify that an image is ready to be displayed.
     * 
     * @param skipFrame Whether this frame should be skipped
     */
    override fun imageReady(skipFrame: Boolean) {
        if (!skipFrame) {
            // Update the image from the current buffer
            updateImage()

            // Repaint the panel
            panel?.repaint()
        }
    }

    /**
     * Check if scaling is enabled for this screen view.
     * 
     * @return true if scaling is enabled, false otherwise
     */
    override fun scalingEnabled(): Boolean {
        return scaleMode != 0
    }

    /**
     * Check if hardware scaling is being used.
     * 
     * @return true if hardware scaling is being used, false otherwise
     */
    override fun useHWScaling(): Boolean {
        return false
    }

    /**
     * Get the current scale mode.
     * 
     * @return The current scale mode
     */
    override fun getScaleMode(): Int {
        return scaleMode
    }

    /**
     * Set the scale mode for the screen view.
     * 
     * @param newMode The new scale mode
     */
    override fun setScaleMode(newMode: Int) {
        scaleMode = newMode
        updatePanelSize()
    }

    /**
     * Get the scale factor for a given scale mode.
     * 
     * @param mode The scale mode
     * @return The scale factor
     */
    override fun getScaleModeScale(mode: Int): Int {
        return when (mode) {
            0 -> 1
            1, 2 -> 2
            else -> 1
        }
    }

    /**
     * Set whether to show the FPS counter.
     * 
     * @param val true to show FPS, false to hide
     */
    override fun setFPSEnabled(value: Boolean) {
        showFPS = value
    }

    /**
     * Set the background color.
     * 
     * @param color The background color in RGB format
     */
    override fun setBgColor(color: Int) {
        bgColor = color
    }

    /**
     * Sets the scale factor for the screen view.
     * 
     * @param scale The new scale factor
     */
    fun setScale(scale: Int) {
        this.scale = scale
        updatePanelSize()
    }

    /**
     * Updates the panel size based on the current scale.
     */
    private fun updatePanelSize() {
        val scaleFactor = getScaleModeScale(scaleMode) * scale
        panel?.preferredSize = Dimension(width * scaleFactor, height * scaleFactor)
        panel?.revalidate()
    }

    /**
     * Gets the panel for this screen view.
     * 
     * @return The panel
     */
    fun getPanel(): JPanel? {
        return panel
    }

    /**
     * Clean up resources used by this screen view.
     */
    override fun destroy() {
        buffer = IntArray(0)
        panel = null
    }

    /**
     * Panel for displaying the screen.
     */
    inner class ScreenPanel : JPanel() {
        init {
            preferredSize = Dimension(width * scale, height * scale)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            // Draw the image
            val scaleFactor = getScaleModeScale(scaleMode) * scale
            g.drawImage(image, 0, 0, width * scaleFactor, height * scaleFactor, null)

            // Draw the FPS counter if enabled
            if (showFPS) {
                g.color = java.awt.Color.WHITE
                g.drawString("FPS: 60", 10, 20)
            }
        }
    }
}
