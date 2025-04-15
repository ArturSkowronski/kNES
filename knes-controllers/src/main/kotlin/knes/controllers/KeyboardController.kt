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

import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent

class KeyboardController : ControllerProvider {
    private val keyStates = mutableMapOf<Int, Boolean>()
    private val buttonMappings = mutableMapOf<ControllerProvider.NESButton, Int>().apply {
        this[ControllerProvider.NESButton.A] = KeyEvent.VK_Z
        this[ControllerProvider.NESButton.B] = KeyEvent.VK_X
        this[ControllerProvider.NESButton.START] = KeyEvent.VK_ENTER
        this[ControllerProvider.NESButton.SELECT] = KeyEvent.VK_SPACE
        this[ControllerProvider.NESButton.UP] = KeyEvent.VK_UP
        this[ControllerProvider.NESButton.DOWN] = KeyEvent.VK_DOWN
        this[ControllerProvider.NESButton.LEFT] = KeyEvent.VK_LEFT
        this[ControllerProvider.NESButton.RIGHT] = KeyEvent.VK_RIGHT
    }

    override fun getButtonState(button: ControllerProvider.NESButton): Short {
        val keyCode = buttonMappings[button]
        // NES expects 0x41 for pressed, 0x40 for not pressed
        return if (keyCode != null && keyStates[keyCode] == true) 0x41 else 0x40
    }

    override fun mapButton(button: ControllerProvider.NESButton, code: Int) {
        buttonMappings[button] = code
    }

    override fun update() {
        // No need to update key states here, handled by key adapter
    }

    fun registerKeyComponent(component: JComponent) {
        component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { handleKeyEvent(e.keyCode, true) }
            override fun keyReleased(e: KeyEvent) { handleKeyEvent(e.keyCode, false) }
        })
        component.isFocusable = true
        component.requestFocus()
    }

    private fun handleKeyEvent(keyCode: Int, pressed: Boolean) {
        keyStates[keyCode] = pressed
    }

    fun setKeyState(keyCode: Int, isPressed: Boolean) {
        keyStates[keyCode] = isPressed
    }
}
