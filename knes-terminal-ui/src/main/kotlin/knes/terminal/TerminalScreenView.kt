package knes.terminal

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

import vnes.emulator.ui.ScreenView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen view for the Terminal UI.
 * 
 * This implementation uses ANSI escape codes to render the NES screen in the terminal.
 */
class TerminalScreenView(private var scale: Int) : ScreenView {
    private val width = 256
    private val height = 240

    private var buffer: IntArray = IntArray(width * height)
    private var scaleMode = 0
    private var showFPS = false
    private var bgColor = 0xFF333333.toInt()

    private var frameCounter: Long = 0
    private val drawBufferToTerminal = AtomicBoolean(true)
    private val frameRateLimit = 4 // Only render every 4th frame to avoid terminal spam

    init {
        buffer.fill(bgColor)
    }

    /**
     * Visualizes the buffer in the terminal.
     * 
     * This is based on the visualizeBufferInTerminal function from ComposeScreenView.
     * 
     * @param buffer The buffer to visualize
     * @param width The width of the buffer
     * @param height The height of the buffer
     */
    private fun visualizeBufferInTerminal() {

        // ANSI escape code for reset
        val reset = "\u001B[0m"

        // Draw the buffer line by line
        for (y in 0 until height) {
            val line = StringBuilder()
            // Cut 30 pixels from left and 30 pixels from right
            for (x in 30 until width - 30) {
                val pixel = buffer[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Convert RGB to ANSI color code
                // Using 8-bit color mode (256 colors)
                // Format: \u001B[38;5;{color_code}m
                // For simplicity, we'll use a basic mapping to the 216 color cube (6x6x6)
                val ansiR = (r * 5 / 255)
                val ansiG = (g * 5 / 255)
                val ansiB = (b * 5 / 255)
                val colorCode = 16 + (36 * ansiR) + (6 * ansiG) + ansiB

                // Apply the color and add a block character
                line.append("\u001B[38;5;${colorCode}m█$reset")
            }
            // Print every 8th line to reduce output volume
            if (y % 8 == 0) {
                println(line.toString())
            }
        }
    }

    /**
     * Initialize the screen view.
     */
    override fun init() {
        // No initialization needed
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
        frameCounter++

        if (!skipFrame && drawBufferToTerminal.get() && frameCounter % frameRateLimit == 0L) {
            // Visualize the buffer in the terminal
            visualizeBufferInTerminal()
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
        return false // Terminal UI doesn't use hardware scaling
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
    }

    /**
     * Gets the current scale factor.
     * 
     * @return The current scale factor
     */
    fun getScale(): Int {
        return scale
    }

    /**
     * Sets whether to draw the buffer to the terminal.
     *
     * @param value true to enable buffer visualization, false to disable
     */
    fun setDrawBufferToTerminal(value: Boolean) {
        drawBufferToTerminal.set(value)
    }

    /**
     * Gets whether buffer visualization is enabled.
     *
     * @return true if buffer visualization is enabled, false otherwise
     */
    fun getDrawBufferToTerminal(): Boolean {
        return drawBufferToTerminal.get()
    }

    /**
     * Clean up resources used by this screen view.
     */
    override fun destroy() {
        buffer = IntArray(0)
    }
}
