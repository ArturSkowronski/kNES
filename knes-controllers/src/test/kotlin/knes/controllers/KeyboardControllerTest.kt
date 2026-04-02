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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import java.awt.event.KeyEvent

class KeyboardControllerTest : FunSpec({

    test("initially all keys return not pressed state (0x40)") {
        val controller = KeyboardController()
        for (i in 0 until InputHandler.NUM_KEYS) {
            controller.getKeyState(i) shouldBe 0x40.toShort()
        }
    }

    test("after setKeyState with true, getKeyState returns pressed (0x41)") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_Z, true)
        controller.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()
    }

    test("after setKeyState with false, getKeyState returns not pressed (0x40)") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_Z, true)
        controller.setKeyState(KeyEvent.VK_Z, false)
        controller.getKeyState(InputHandler.KEY_A) shouldBe 0x40.toShort()
    }

    test("multiple keys can be pressed simultaneously") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_Z, true)
        controller.setKeyState(KeyEvent.VK_X, true)
        controller.setKeyState(KeyEvent.VK_UP, true)

        controller.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()
        controller.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()
        controller.getKeyState(InputHandler.KEY_UP) shouldBe 0x41.toShort()
        // Keys not pressed remain not pressed
        controller.getKeyState(InputHandler.KEY_DOWN) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_LEFT) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_RIGHT) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_START) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_SELECT) shouldBe 0x40.toShort()
    }

    test("unknown key codes do not crash") {
        val controller = KeyboardController()
        controller.setKeyState(99999, true)  // unknown key code, should not throw
        // All keys should remain not pressed
        for (i in 0 until InputHandler.NUM_KEYS) {
            controller.getKeyState(i) shouldBe 0x40.toShort()
        }
    }

    test("default mapping: KEY_A maps to VK_Z") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_Z, true)
        controller.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()
    }

    test("default mapping: KEY_B maps to VK_X") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_X, true)
        controller.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()
    }

    test("default mapping: KEY_START maps to VK_ENTER") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_ENTER, true)
        controller.getKeyState(InputHandler.KEY_START) shouldBe 0x41.toShort()
    }

    test("default mapping: KEY_SELECT maps to VK_SPACE") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_SPACE, true)
        controller.getKeyState(InputHandler.KEY_SELECT) shouldBe 0x41.toShort()
    }

    test("default mapping: KEY_UP maps to VK_UP") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_UP, true)
        controller.getKeyState(InputHandler.KEY_UP) shouldBe 0x41.toShort()
    }

    test("default mapping: KEY_DOWN maps to VK_DOWN") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_DOWN, true)
        controller.getKeyState(InputHandler.KEY_DOWN) shouldBe 0x41.toShort()
    }

    test("default mapping: KEY_LEFT maps to VK_LEFT") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_LEFT, true)
        controller.getKeyState(InputHandler.KEY_LEFT) shouldBe 0x41.toShort()
    }

    test("default mapping: KEY_RIGHT maps to VK_RIGHT") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_RIGHT, true)
        controller.getKeyState(InputHandler.KEY_RIGHT) shouldBe 0x41.toShort()
    }

    test("pressing one key does not affect other keys") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_Z, true)  // KEY_A

        controller.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()
        controller.getKeyState(InputHandler.KEY_B) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_START) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_SELECT) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_UP) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_DOWN) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_LEFT) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_RIGHT) shouldBe 0x40.toShort()
    }

    test("releasing a key does not affect other pressed keys") {
        val controller = KeyboardController()
        controller.setKeyState(KeyEvent.VK_Z, true)   // KEY_A
        controller.setKeyState(KeyEvent.VK_X, true)   // KEY_B
        controller.setKeyState(KeyEvent.VK_Z, false)  // release KEY_A

        controller.getKeyState(InputHandler.KEY_A) shouldBe 0x40.toShort()
        controller.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()
    }

    test("getKeyName returns correct name for known key codes") {
        KeyboardController.getKeyName(KeyEvent.VK_Z) shouldBe "Z"
        KeyboardController.getKeyName(KeyEvent.VK_X) shouldBe "X"
        KeyboardController.getKeyName(KeyEvent.VK_ENTER) shouldBe "ENTER"
        KeyboardController.getKeyName(KeyEvent.VK_SPACE) shouldBe "SPACE"
        KeyboardController.getKeyName(KeyEvent.VK_UP) shouldBe "UP"
        KeyboardController.getKeyName(KeyEvent.VK_DOWN) shouldBe "DOWN"
        KeyboardController.getKeyName(KeyEvent.VK_LEFT) shouldBe "LEFT"
        KeyboardController.getKeyName(KeyEvent.VK_RIGHT) shouldBe "RIGHT"
    }

    test("getKeyName returns UNKNOWN for unknown key codes") {
        KeyboardController.getKeyName(99999) shouldBe "UNKNOWN"
    }
})
