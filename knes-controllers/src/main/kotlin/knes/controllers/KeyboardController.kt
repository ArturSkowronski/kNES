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

package knes.controllers

import knes.emulator.input.InputHandler
import java.awt.event.KeyEvent


class KeyboardController : ControllerProvider {
    private val keyStates = ShortArray(InputHandler.Companion.NUM_KEYS) { 0 }
    private val keyMapping = IntArray(InputHandler.Companion.NUM_KEYS) { 0 }

    init {
        keyMapping[InputHandler.KEY_A] = KeyEvent.VK_Z
        keyMapping[InputHandler.KEY_B] = KeyEvent.VK_X
        keyMapping[InputHandler.KEY_START] = KeyEvent.VK_ENTER
        keyMapping[InputHandler.KEY_SELECT] = KeyEvent.VK_SPACE
        keyMapping[InputHandler.KEY_UP] = KeyEvent.VK_UP
        keyMapping[InputHandler.KEY_DOWN] = KeyEvent.VK_DOWN
        keyMapping[InputHandler.KEY_LEFT] = KeyEvent.VK_LEFT
        keyMapping[InputHandler.KEY_RIGHT] = KeyEvent.VK_RIGHT
    }

    override fun setKeyState(keyCode: Int, isPressed: Boolean) {
        for (i in keyMapping.indices) {
            if (keyMapping[i] == keyCode) {
                keyStates[i] = if (isPressed) 0x41 else 0x40
            }
        }
    }

    override fun getKeyState(padKey: Int): Short {
        return keyStates[padKey]
    }

    companion object {
        fun getKeyName(keyCode: Int): String {
            return when (keyCode) {
                KeyEvent.VK_Z -> "Z"
                KeyEvent.VK_X -> "X"
                KeyEvent.VK_ENTER -> "ENTER"
                KeyEvent.VK_SPACE -> "SPACE"
                KeyEvent.VK_UP -> "UP"
                KeyEvent.VK_DOWN -> "DOWN"
                KeyEvent.VK_LEFT -> "LEFT"
                KeyEvent.VK_RIGHT -> "RIGHT"
                else -> "UNKNOWN"
            }
        }
    }
}
