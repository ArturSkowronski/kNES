package vnes.skiko

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

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import vnes.emulator.ui.ScreenView
import java.awt.image.BufferedImage

/**
 * Screen view for the Skiko UI.
 * 
 * This implementation uses Skiko to render the NES screen.
 */
class SkikoScreenView(private var scale: Int) : ScreenView {
    private val width = 256
    private val height = 240

    private var buffer: IntArray = IntArray(width * height)
    private var scaleMode = 0
    private var showFPS = false
    private var bgColor = 0xFF333333.toInt()

    private var frameCounter: Long = 0

    // Callback for when a new frame is ready
    var onFrameReady: (() -> Unit)? = null
        set(value) {
            field = value
        }

    init {
        buffer.fill(bgColor)
    }

    /**
     * Gets the frame bitmap for rendering.
     * 
     * @return A Skiko Bitmap containing the current frame
     */
    fun getFrameBitmap(): Bitmap {
        frameCounter++

        // Log some color information for debugging
        if (frameCounter % 60L == 0L) { // Log once per second at 60fps
            println("[DEBUG] First few pixels in buffer: " +
                    "${Integer.toHexString(buffer[0])}, " +
                    "${Integer.toHexString(buffer[1])}, " +
                    "${Integer.toHexString(buffer[2])}")
        }

        // Create a Skiko Bitmap
        val bitmap = Bitmap()
        val imageInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        bitmap.allocPixels(imageInfo)

        // Set the pixel data directly from the buffer
        // We need to ensure alpha channel is set for each pixel
        val pixelsWithAlpha = IntArray(buffer.size)
        for (i in buffer.indices) {
            pixelsWithAlpha[i] = buffer[i] or 0xFF000000.toInt()
        }

        // Convert IntArray to ByteArray for installPixels
        val byteBuffer = java.nio.ByteBuffer.allocate(pixelsWithAlpha.size * 4).order(java.nio.ByteOrder.nativeOrder())
        val intBuffer = byteBuffer.asIntBuffer()
        intBuffer.put(pixelsWithAlpha)

        bitmap.installPixels(imageInfo, byteBuffer.array(), width * 4)

        return bitmap
    }

    /**
     * Converts a color from RGB to HSB color space and back to RGB.
     * This is the same conversion used in ScreenLogger.
     * 
     * @param rgbColor The RGB color to convert
     * @return The converted color
     */
    private fun convertColorToHSB(rgbColor: Int): Int {
        // Extract RGB components
        val r = (rgbColor shr 16) and 0xFF
        val g = (rgbColor shr 8) and 0xFF
        val b = rgbColor and 0xFF

        // Convert RGB to HSB
        val hsb = java.awt.Color.RGBtoHSB(r, g, b, null)

        // Convert back to RGB with HSBtoRGB
        return java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) or 0xFF000000.toInt()
    }

    /**
     * Creates a BufferedImage from the current frame for preview purposes.
     * 
     * @return A BufferedImage containing the current frame
     */
    fun getFrameBufferedImage(): BufferedImage {
        // Create a copy of the buffer with alpha channel set
        val pixelsWithAlpha = IntArray(buffer.size)
        for (i in buffer.indices) {
            pixelsWithAlpha[i] = buffer[i] or 0xFF000000.toInt()
        }

        // Log some color information for debugging
        if (frameCounter % 60L == 0L) { // Log once per second at 60fps
            println("[DEBUG] First few pixels in getFrameBufferedImage: " +
                    "${Integer.toHexString(pixelsWithAlpha[0])}, " +
                    "${Integer.toHexString(pixelsWithAlpha[1])}, " +
                    "${Integer.toHexString(pixelsWithAlpha[2])}")
        }

        // Create a BufferedImage with TYPE_INT_ARGB to ensure alpha channel support
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, pixelsWithAlpha, 0, width)
        }
    }

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
        if (!skipFrame) {
            // Notify that a new frame is ready
            // This will trigger a redraw in SkikoMain
            onFrameReady!!.invoke()
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
        return true // Skiko uses hardware acceleration
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
     * Clean up resources used by this screen view.
     */
    override fun destroy() {
        buffer = IntArray(0)
    }
}
