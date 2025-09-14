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

package knes.emulator.ui

import knes.emulator.input.InputHandler
import knes.emulator.utils.HiResTimer

/**
 * Adapter class that implements the GUI interface by delegating to components
 * created by a NESUIFactory.
 */
class GUIAdapter(
    private val inputHandler: InputHandler,
    private val screenView: ScreenView
) : GUI {
    private val timer: HiResTimer = HiResTimer()

    override fun getJoy1(): InputHandler {
        return inputHandler
    }

    override fun getJoy2(): InputHandler? {
        return null
    }

    override fun getTimer(): HiResTimer {
        return timer
    }

    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
        screenView.imageReady(skipFrame, buffer)
    }

    override fun sendErrorMsg(message: String) {
        System.err.println("ERROR: $message")
    }

    override fun sendDebugMessage(message: String) {}

    override fun destroy() {
        screenView.destroy()
    }
}
