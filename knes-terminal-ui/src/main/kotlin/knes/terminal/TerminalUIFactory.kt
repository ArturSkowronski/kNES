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

package knes.terminal

import knes.emulator.input.InputHandler
import knes.emulator.ui.NESUIFactory
import knes.emulator.ui.ScreenView

/**
 * Factory for creating Terminal UI components for the NES emulator.
 */
class TerminalUIFactory : NESUIFactory {
    val terminalUI = TerminalUI()
    override val inputHandler: InputHandler = TerminalInputHandler()
    override val screenView: ScreenView = TerminalScreenView(1)
}
