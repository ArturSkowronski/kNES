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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import knes.controllers.ControllerProvider
import knes.emulator.input.InputHandler

/**
 * Input handler for the Compose UI.
 *
 * Handles keyboard input using Compose key codes (mapped internally to NES buttons)
 * and delegates gamepad input to the ControllerProvider.
 */
class ComposeInputHandler(val controllerProvider: ControllerProvider) : InputHandler {

    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    /** Additional input source (e.g. API controller) merged into getKeyState */
    var additionalInput: ControllerProvider? = null

    /** Map Compose Key to NES button index, or -1 if not mapped. */
    private fun mapKey(key: Key): Int {
        return when (key) {
            Key.Z -> InputHandler.KEY_A
            Key.X -> InputHandler.KEY_B
            Key.Enter -> InputHandler.KEY_START
            Key.Spacebar -> InputHandler.KEY_SELECT
            Key.DirectionUp -> InputHandler.KEY_UP
            Key.DirectionDown -> InputHandler.KEY_DOWN
            Key.DirectionLeft -> InputHandler.KEY_LEFT
            Key.DirectionRight -> InputHandler.KEY_RIGHT
            else -> -1
        }
    }

    fun keyEventHandler(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val nesButton = mapKey(event.key)
        if (nesButton == -1) return false

        when (event.type) {
            KeyEventType.KeyDown -> keyStates[nesButton] = 0x41
            KeyEventType.KeyUp -> keyStates[nesButton] = 0x40
        }
        return true
    }

    override fun getKeyState(padKey: Int): Short {
        // Merge keyboard, gamepad, and API: any one pressed = pressed
        val keyboard = keyStates[padKey]
        val gamepad = controllerProvider.getKeyState(padKey)
        val api = additionalInput?.getKeyState(padKey) ?: 0x40
        return if (keyboard == 0x41.toShort() || gamepad == 0x41.toShort() || api == 0x41.toShort()) 0x41 else 0x40
    }
}
