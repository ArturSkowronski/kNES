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

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import knes.controllers.KeyboardController
import knes.emulator.input.InputHandler

/**
 * Input handler for the Compose UI.
 *
 * Note: This is a temporary implementation using Swing instead of Compose
 * until the Compose UI dependencies are properly configured.
 */
class ComposeKeyboardInputHandler(val keyboardController: KeyboardController) : InputHandler {

    fun keyEventHandler(
        event: androidx.compose.ui.input.key.KeyEvent
    ): Boolean {
        val keyCode = event.key.keyCode.toInt()

        return if (keyCode != 0) {
            println("Key event: ${event.type} ${event.key} keyCode: $keyCode (${KeyboardController.getKeyName(keyCode)})")

            when (event.type) {
                KeyEventType.KeyDown -> {
                    keyboardController.setKeyState(keyCode, true)
                    true
                }

                KeyEventType.KeyUp -> {
                    keyboardController.setKeyState(keyCode, false)
                    true
                }
                else -> true
            }
        } else {
            false
        }
    }

    override fun getKeyState(padKey: Int): Short {
        return keyboardController.getKeyState(padKey)
    }

}