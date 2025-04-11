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

package knes.skiko

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

import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import knes.emulator.input.InputHandler
import knes.emulator.input.InputHandler.Companion.KEY_A
import knes.emulator.input.InputHandler.Companion.KEY_B
import knes.emulator.input.InputHandler.Companion.KEY_DOWN
import knes.emulator.input.InputHandler.Companion.KEY_LEFT
import knes.emulator.input.InputHandler.Companion.KEY_RIGHT
import knes.emulator.input.InputHandler.Companion.KEY_SELECT
import knes.emulator.input.InputHandler.Companion.KEY_START
import knes.emulator.input.InputHandler.Companion.KEY_UP
import knes.emulator.input.InputHandler.Companion.NUM_KEYS

/**
 * Input handler for the Skiko UI.
 * 
 * This implementation uses AWT/Swing for keyboard input.
 */
class SkikoInputHandler() : InputHandler {
    private val keyStates = ShortArray(NUM_KEYS) { 0 }
    private val keyMapping = IntArray(NUM_KEYS) { 0 }
    private val keyAdapter = KeyInputAdapter()

    init {
        // Default key mappings
        mapKey(KEY_A, KeyEvent.VK_Z)
        mapKey(KEY_B, KeyEvent.VK_X)
        mapKey(KEY_START, KeyEvent.VK_ENTER)
        mapKey(KEY_SELECT, KeyEvent.VK_SPACE)
        mapKey(KEY_UP, KeyEvent.VK_UP)
        mapKey(KEY_DOWN, KeyEvent.VK_DOWN)
        mapKey(KEY_LEFT, KeyEvent.VK_LEFT)
        mapKey(KEY_RIGHT, KeyEvent.VK_RIGHT)
    }

    /**
     * Gets the state of a key.
     * 
     * @param padKey The key to check
     * @return 0x41 if the key is pressed, 0x40 otherwise
     */
    override fun getKeyState(padKey: Int): Short {
        return keyStates[padKey]
    }

    /**
     * Maps a pad key to a device key.
     * 
     * @param padKey The pad key to map
     * @param deviceKey The device key to map to
     */
    override fun mapKey(padKey: Int, deviceKey: Int) {
        keyMapping[padKey] = deviceKey
    }

    /**
     * Resets the input handler.
     */
    override fun reset() {
        for (i in keyStates.indices) {
            keyStates[i] = 0
        }
    }

    /**
     * Updates the input handler.
     */
    override fun update() {
        // No need to update key states here, as they are updated by the key adapter
    }

    /**
     * Sets the state of a key.
     * 
     * @param keyCode The key code
     * @param isPressed Whether the key is pressed
     */
    fun setKeyState(keyCode: Int, isPressed: Boolean) {
        for (i in keyMapping.indices) {
            if (keyMapping[i] == keyCode) {
                keyStates[i] = if (isPressed) 0x41 else 0x40
            }
        }
    }

    /**
     * Registers the key adapter with a component.
     * 
     * @param component The component to register with
     */
    fun registerKeyAdapter(component: JComponent) {
        component.addKeyListener(keyAdapter)
        component.isFocusable = true
        component.requestFocus()
    }

    /**
     * Unregisters the key adapter from a component.
     * 
     * @param component The component to unregister from
     */
    fun unregisterKeyAdapter(component: JComponent) {
        component.removeKeyListener(keyAdapter)
    }

    /**
     * Cleans up resources.
     */
    override fun destroy() {
        // Clean up resources
    }

    /**
     * Key adapter for handling keyboard input.
     */
    inner class KeyInputAdapter : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            setKeyState(e.keyCode, true)
        }

        override fun keyReleased(e: KeyEvent) {
            setKeyState(e.keyCode, false)
        }
    }
}
