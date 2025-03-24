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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vnes.emulator.ui.ScreenView
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import javax.imageio.ImageIO

/**
 * Screen view for the Compose UI.
 * 
 * This implementation uses Compose Desktop to render the NES screen.
 */
class ComposeScreenView(private var scale: Int) : ScreenView {
    private val width = 256
    private val height = 240
    private var buffer: IntArray = IntArray(width * height)
    private var image: BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private var imageBitmap by mutableStateOf<ImageBitmap?>(null)
    private var scaleMode = 0
    private var showFPS = false
    private var bgColor = 0xFF333333.toInt()

    // Flag to control whether to draw the buffer to the terminal
    private var drawBufferToTerminal = false

    // Mutex to protect access to the buffer and image
    private val bufferMutex = Mutex()

    // Frame counter to force recomposition
    private var frameCounter: Long = 0

    // State that will be observed by Compose
    private val _frameUpdateCounter = androidx.compose.runtime.mutableStateOf(0L)

    // Store previous frame's top 5 colors for comparison
    private var previousTopColors: List<Map.Entry<Int, Int>> = emptyList()

    init {
        // Initialize the buffer with the background color
        buffer.fill(bgColor)

        // Create the image from the buffer
        getFrameBitmap()
    }


    private fun to5Colors(buffer: IntArray) {
        // Get the top 5 colors sorted by color value
        // Log the aggregated count of pixels in particular colors
        val colorCounts = mutableMapOf<Int, Int>()
        for (pixel in buffer) {
            colorCounts[pixel] = (colorCounts[pixel] ?: 0) + 1
        }

        val topColors = colorCounts.entries.sortedBy { it.key }.take(5)

        // Check if the top 5 colors have changed and log them if they have
        val topColorsChanged = ScreenLogger.logColorChanges(topColors, previousTopColors)

        // Draw the buffer to the terminal line by line if the flag is set
        if (drawBufferToTerminal) {
            ScreenLogger.visualizeBufferInTerminal(buffer, width, height, topColors)
        }

        // Update previous top colors for next comparison
        previousTopColors = topColors
    }

    /**
     * Gets the current frame as an ImageBitmap.
     * This method updates the image from the current buffer and returns it.
     * 
     * @return The current frame as an ImageBitmap
     */
    fun getFrameBitmap(): ImageBitmap? {
        // Create a new buffer with alpha channel added
        frameCounter++

        val imageData = IntArray(buffer.size)
        for (i in buffer.indices) {
            // Add alpha channel (0xFF) to each pixel
            imageData[i] = buffer[i] or 0xFF000000.toInt()
        }

        to5Colors(buffer)

        // Create a new image from the buffer
        val newImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, imageData, 0, width)
        }

        // Log the frame image to file
        ScreenLogger.logFrameImage(newImage)

        // Update the image bitmap with the new pixel data
        // Convert to Compose ImageBitmap
        // Always update the image with new instance to trigger recomposition
        imageBitmap = newImage.toComposeImageBitmap()

        // Return the updated image bitmap
        return imageBitmap
    }

    /**
     * Gets a minimal dummy frame as an ImageBitmap with color changes.
     * This is a simplified version of getFrameBitmap() for testing purposes.
     * The colors change between frames based on the frame counter.
     * 
     * @return A minimal dummy frame as an ImageBitmap
     */
    fun getDUMMYFrameBitmap(): ImageBitmap {
        // Create a small 16x16 image with different colors
        val width = 16
        val height = 16
        val imageData = IntArray(width * height)

        // Increment frame counter to ensure colors change between frames
        frameCounter++

        // Use frame counter to shift colors
        val colorShift = (frameCounter % 360).toInt()

        // Calculate color components with shifting hues
        val hue1 = (0 + colorShift) % 360
        val hue2 = (90 + colorShift) % 360
        val hue3 = (180 + colorShift) % 360
        val hue4 = (270 + colorShift) % 360

        // Convert HSB to RGB colors
        val color1 = java.awt.Color.HSBtoRGB(hue1 / 360f, 1f, 1f) or 0xFF000000.toInt()
        val color2 = java.awt.Color.HSBtoRGB(hue2 / 360f, 1f, 1f) or 0xFF000000.toInt()
        val color3 = java.awt.Color.HSBtoRGB(hue3 / 360f, 1f, 1f) or 0xFF000000.toInt()
        val color4 = java.awt.Color.HSBtoRGB(hue4 / 360f, 1f, 1f) or 0xFF000000.toInt()

        // Fill with different colors
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Create a pattern with different colors that change between frames
                val color = when {
                    (x < width / 2 && y < height / 2) -> color1 // Top-left quadrant
                    (x >= width / 2 && y < height / 2) -> color2 // Top-right quadrant
                    (x < width / 2 && y >= height / 2) -> color3 // Bottom-left quadrant
                    else -> color4 // Bottom-right quadrant
                }
                imageData[y * width + x] = color
            }
        }

        // Create a BufferedImage from the pixel data
        val newImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, imageData, 0, width)
        }

        // Convert to Compose ImageBitmap and return
        return newImage.toComposeImageBitmap()
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
            // Mark the image as needing an update
            getFrameBitmap()
            // Increment the frame update counter to trigger recomposition in Compose
            _frameUpdateCounter.value++
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
        return true // Compose uses hardware acceleration
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
        drawBufferToTerminal = value
    }

    /**
     * Gets whether buffer visualization is enabled.
     * 
     * @return true if buffer visualization is enabled, false otherwise
     */
    fun getDrawBufferToTerminal(): Boolean {
        return drawBufferToTerminal
    }

    /**
     * Clean up resources used by this screen view.
     */
    override fun destroy() {
        buffer = IntArray(0)
        imageBitmap = null
    }
}
