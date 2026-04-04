/*
 *
 *  * Copyright (C) 2025 Artur Skowroński
 *  * This file is part of kNES, a fork of vNES (GPLv3) rewritten in Kotlin.
 *  *
 *  * vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license.
 *  * This project is a reimplementation and extension of that work.
 *  *
 *  * kNES is licensed under the GNU General Public License v3.0.
 *  * See the LICENSE file for more details.
 *
 */

package knes.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import knes.emulator.ui.ScreenView
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

class ComposeScreenView(val scale: Int) : ScreenView {
    private val width = 256
    private val height = 240
    private val pixelCount = width * height

    // Reusable byte buffer — avoids allocation per frame
    private val byteBuffer = ByteArray(pixelCount * 4)

    // Pre-built Skia ImageInfo — reused every frame
    private val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)

    private var currentBuffer = IntArray(pixelCount)
    private var scaleMode = 0
    private var showFPS = false

    private val timer = HiResTimer()
    private var t1: Long = timer.currentMicros()
    private var sleepTime: Int = 0

    var onFrameReady: (() -> Unit)? = null

    fun getFrameBitmap(): ImageBitmap {
        // Convert PPU RGB (0x00RRGGBB) directly to BGRA bytes — single pass, no intermediates
        val bytes = byteBuffer
        for (i in 0 until pixelCount) {
            val c = currentBuffer[i]
            val off = i * 4
            bytes[off] = (c and 0xFF).toByte()              // B
            bytes[off + 1] = ((c shr 8) and 0xFF).toByte()  // G
            bytes[off + 2] = ((c shr 16) and 0xFF).toByte() // R
            bytes[off + 3] = 0xFF.toByte()                   // A
        }

        val skiaImage = org.jetbrains.skia.Image.makeRaster(imageInfo, bytes, width * 4)
        return skiaImage.toComposeImageBitmap()
    }

    override fun getBufferWidth(): Int = width
    override fun getBufferHeight(): Int = height

    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
        if (Globals.timeEmulation && !Globals.enableSound) {
            sleepTime = Globals.frameTime
            val t2 = timer.currentMicros()
            val elapsed = t2 - t1
            if (elapsed < sleepTime) {
                timer.sleepMicros(sleepTime - elapsed)
            }
        }

        t1 = timer.currentMicros()
        System.arraycopy(buffer, 0, currentBuffer, 0, pixelCount)

        if (!skipFrame) {
            onFrameReady?.invoke()
        }
    }

    override fun scalingEnabled(): Boolean = scaleMode != 0
    override fun useHWScaling(): Boolean = true
    override fun getScaleMode(): Int = scaleMode
    override fun setScaleMode(newMode: Int) { scaleMode = newMode }
    override fun getScaleModeScale(mode: Int): Int = if (mode in 1..2) 2 else 1
    override fun setFPSEnabled(enabled: Boolean) { showFPS = enabled }
    override fun setBgColor(color: Int) {}
    override fun destroy() {}
}
