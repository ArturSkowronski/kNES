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

package knes.emulator.input

interface InputHandler {
    fun getKeyState(padKey: Int): Short
    fun mapKey(padKey: Int, deviceKey: Int)
    fun reset()
    fun update()

    /**
     * Clean up resources used by this input handler.
     */
    fun destroy()

    companion object {
        const val KEY_A: Int = 0
        const val KEY_B: Int = 1
        const val KEY_START: Int = 2
        const val KEY_SELECT: Int = 3
        const val KEY_UP: Int = 4
        const val KEY_DOWN: Int = 5
        const val KEY_LEFT: Int = 6
        const val KEY_RIGHT: Int = 7

        const val NUM_KEYS: Int = 8
    }
}
