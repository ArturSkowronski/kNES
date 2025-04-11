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

/**
 * Platform-agnostic interface for handling input events.
 * This interface defines callbacks for button press and release events
 * without dependencies on specific UI frameworks.
 */
interface InputCallback {
    /**
     * Called when a button is pressed.
     *
     * @param buttonCode The code of the button that was pressed
     */
    fun buttonDown(buttonCode: Int)

    /**
     * Called when a button is released.
     *
     * @param buttonCode The code of the button that was released
     */
    fun buttonUp(buttonCode: Int)
}