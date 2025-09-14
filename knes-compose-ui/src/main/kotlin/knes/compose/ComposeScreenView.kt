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
import knes.compose.utils.ScreenLogger
import knes.emulator.ui.ScreenView
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.awt.image.BufferedImage

/**
 * Screen view for the Compose UI.
 * 
 * This implementation uses Compose Desktop to render the NES screen.
 */
class ComposeScreenView(val scale: Int) : ScreenView {
    private val width = 256
    private val height = 240
    private var currentBuffer = IntArray(width * height)
    private var scaleMode = 0
    private var showFPS = false

    private var frameCounter: Long = 0

    // Timing control variables
    private val timer = HiResTimer()
    private var t1: Long = 0
    private var t2: Long = 0
    private var sleepTime: Int = 0

    // Callback for when a new frame is ready
    var onFrameReady: (() -> Unit)? = null

    init {
        t1 = timer.currentMicros()
    }

    fun getFrameBitmap(): ImageBitmap {
        val imageData = IntArray(currentBuffer.size)

        frameCounter++

        for (i in currentBuffer.indices) {
            val color = ScreenLogger.convertColorToHSB(currentBuffer[i])
            imageData[i] = color or 0xFF000000.toInt()
        }

        // Create a BufferedImage with TYPE_INT_ARGB to ensure alpha channel support
        val newImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, imageData, 0, width)
        }

        return newImage.toComposeImageBitmap()
    }

    override fun getBufferWidth(): Int {
        return width
    }

    override fun getBufferHeight(): Int {
        return height
    }

    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
        // Sleep a bit if sound is disabled:
        if (Globals.timeEmulation && !Globals.enableSound) {
            sleepTime = Globals.frameTime
            t2 = timer.currentMicros()
            val elapsedTime = t2 - t1
            if (elapsedTime < sleepTime) {
                timer.sleepMicros(sleepTime - elapsedTime)
            }
        }

        t1 = timer.currentMicros()
        currentBuffer = buffer.copyOf()
        if (!skipFrame) {
            getFrameBitmap()
            onFrameReady?.invoke()
        }
    }

    override fun scalingEnabled(): Boolean {
        return scaleMode != 0
    }

    override fun useHWScaling(): Boolean {
        return true // Compose uses hardware acceleration
    }

    override fun getScaleMode(): Int {
        return scaleMode
    }

    override fun setScaleMode(newMode: Int) {
        scaleMode = newMode
    }

    override fun getScaleModeScale(mode: Int): Int {
        return when (mode) {
            0 -> 1
            1, 2 -> 2
            else -> 1
        }
    }

    override fun setFPSEnabled(value: Boolean) {
        showFPS = value
    }

    override fun setBgColor(color: Int) {

    }

    override fun destroy() {}
}
