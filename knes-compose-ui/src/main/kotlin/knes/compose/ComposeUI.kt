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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import knes.emulator.NES

class ComposeUI(val nes: NES, val screenView: ComposeScreenView, val inputHandler: ComposeKeyboardInputHandler)  {

    fun startEmulator() {
        nes.startEmulation()
    }

    fun stopEmulator() {
        nes.stopEmulation()
    }

    fun loadRom(path: String): Boolean {
        return nes.loadRom(path) == true
    }

    @Composable
    fun nesScreenRenderer() {
        var frameCount by remember { mutableStateOf(0) }
        var currentBitmap by remember { mutableStateOf(screenView.getFrameBitmap()) }
        val baseScale = screenView.scale
        val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
        val scale = if (isMacOS) baseScale * 1 else baseScale

        val scaledWidth = 512 * scale
        val scaledHeight = 480 * scale

        DisposableEffect(Unit) {
            screenView.onFrameReady = {
                currentBitmap = screenView.getFrameBitmap()
                frameCount++
            }

            onDispose {
                screenView.onFrameReady = null
            }
        }

        Canvas(
            modifier = Modifier
                .width(scaledWidth.dp)
                .height(scaledHeight.dp)
        ) {
            drawImage(
                image = currentBitmap,
                dstSize = IntSize(scaledWidth, scaledHeight)
            )
        }
    }
}
