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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import knes.emulator.NES

class ComposeUI(val nes: NES, val screenView: ComposeScreenView) {

    fun startEmulator() {
        nes.startEmulation()
    }

    fun stopEmulator() {
        nes.stopEmulation()
    }

    fun loadRom(path: String): Boolean {
        return nes.loadRom(path)
    }

    @Composable
    fun nesScreenRenderer() {
        var frameCount by remember { mutableStateOf(0) }
        var currentBitmap by remember { mutableStateOf(screenView.getFrameBitmap()) }

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
                .fillMaxSize()
                .aspectRatio(256f / 240f)
        ) {
            drawImage(
                image = currentBitmap,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }
}
