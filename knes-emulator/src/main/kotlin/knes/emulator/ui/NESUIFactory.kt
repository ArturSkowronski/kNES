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

/**
 * Factory interface for creating UI components for the knes.emulator.NES emulator.
 * This interface allows different UI implementations to be plugged into the emulator core.
 */
interface NESUIFactory {
    val inputHandler: InputHandler
    val screenView: ScreenView
}
