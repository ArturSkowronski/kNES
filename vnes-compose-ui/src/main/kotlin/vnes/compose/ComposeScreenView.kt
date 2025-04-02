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

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import vnes.compose.utils.ScreenLogger
import vnes.emulator.NES
import vnes.emulator.ui.ScreenView
import vnes.emulator.utils.Globals
import vnes.emulator.utils.HiResTimer
import java.awt.image.BufferedImage

/**
 * Screen view for the Compose UI.
 * 
 * This implementation uses Compose Desktop to render the NES screen.
 */
class ComposeScreenView(private var scale: Int) : ScreenView {
    private val width = 256
    private val height = 240

    private var buffer: IntArray = IntArray(width * height)
    private var scaleMode = 0
    private var showFPS = false
    private var bgColor = 0xFF333333.toInt()

    private var frameCounter: Long = 0

    // Timing control variables
    private val timer = HiResTimer()
    private var t1: Long = 0
    private var t2: Long = 0
    private var sleepTime: Int = 0

    // NES instance
    private var nes: NES? = null

    // Callback for when a new frame is ready
    var onFrameReady: (() -> Unit)? = null

    init {
        buffer.fill(bgColor)
        t1 = timer.currentMicros()
    }

    fun getFrameBitmap(): ImageBitmap {
        val imageData = IntArray(buffer.size)

        frameCounter++

        for (i in buffer.indices) {
            // Use the conversion method from ScreenLogger
            // Make sure alpha channel is explicitly set to fully opaque
            val color = ScreenLogger.convertColorToHSB(buffer[i])
            imageData[i] = color or 0xFF000000.toInt()
        }

//        // Log some color information for debugging
//        if (frameCounter % 60L == 0L) { // Log once per second at 60fps
//            println("[DEBUG] First few pixels in getFrameBitmap: " +
//                    "${Integer.toHexString(imageData[0])}, " +
//                    "${Integer.toHexString(imageData[1])}, " +
//                    "${Integer.toHexString(imageData[2])}")
//        }

        // Skip the to5Colors call as it might be modifying the colors unexpectedly
        // ScreenLogger.to5Colors(imageData, width, height)

        // Create a BufferedImage with TYPE_INT_ARGB to ensure alpha channel support
        val newImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, imageData, 0, width)
        }

        // Convert to Compose ImageBitmap
        return newImage.toComposeImageBitmap()
    }

    /**
     * Creates a safe copy of the frame bitmap for preview purposes.
     * This method creates a smaller, simplified version of the bitmap
     * to avoid performance issues when previewing.
     *
     * @return A simplified ImageBitmap suitable for preview
     */
    fun getSafePreviewBitmap(): ImageBitmap {
        // Create a smaller version of the bitmap (e.g., 128x120 instead of 256x240)
        val previewWidth = width / 2
        val previewHeight = height / 2
        val imageData = IntArray(previewWidth * previewHeight)

        // Sample the buffer to create a smaller image
        for (y in 0 until previewHeight) {
            for (x in 0 until previewWidth) {
                // Sample from the original buffer (take every other pixel)
                val srcX = x * 2
                val srcY = y * 2
                val srcIndex = srcY * width + srcX

                // Ensure the alpha channel is set
                val color = ScreenLogger.convertColorToHSB(buffer[srcIndex])
                imageData[y * previewWidth + x] = color or 0xFF000000.toInt()
            }
        }

        // Create a smaller BufferedImage
        val previewImage = BufferedImage(previewWidth, previewHeight, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, previewWidth, previewHeight, imageData, 0, previewWidth)
        }

        return previewImage.toComposeImageBitmap()
    }

    override fun init() {
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
        // Sound stuff:
        nes?.let { nes ->
            val tmp = nes.getPapu()!!.bufferIndex
            if (Globals.enableSound && Globals.timeEmulation && tmp > 0) {
                val min_avail = nes.getPapu()!!.line!!.getBufferSize() - 4 * tmp

                var timeToSleep = nes.getPapu()!!.getMillisToAvailableAbove(min_avail)
                do {
                    try {
                        Thread.sleep(timeToSleep.toLong())
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                    timeToSleep = nes.getPapu()!!.getMillisToAvailableAbove(min_avail)
                } while (timeToSleep > 0)

                nes.getPapu()!!.writeBuffer()
            }
        }

        // Sleep a bit if sound is disabled:
        if (Globals.timeEmulation && !Globals.enableSound) {
            sleepTime = Globals.frameTime
            t2 = timer.currentMicros()
            val elapsedTime = t2 - t1
            if (elapsedTime < sleepTime) {
                timer.sleepMicros(sleepTime - elapsedTime)
            }
        }

        // Update timer
        t1 = timer.currentMicros()

        if (!skipFrame) {
            // Mark the image as needing an update
            getFrameBitmap()

            // Notify that a new frame is ready
            onFrameReady?.invoke()
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
     * Sets the NES instance for this screen view.
     * 
     * @param nes The NES instance to use
     */
    fun setNES(nes: NES) {
        this.nes = nes
    }

    /**
     * Clean up resources used by this screen view.
     */
    override fun destroy() {
        buffer = IntArray(0)
        nes = null
    }
}
