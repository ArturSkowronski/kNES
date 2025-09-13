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
    private val terminalUI = TerminalUI()
    override val inputHandler: InputHandler = TerminalInputHandler()

    /**
     * Creates a screen view for the NES emulator.
     * 
     * @param scale The initial scale factor for the screen view
     * @return A ScreenView implementation
     */
    override fun createScreenView(scale: Int): ScreenView {
        return TerminalScreenView(scale)
    }

    /**
     * Configures UI-specific settings.
     * 
     * @param enableAudio Whether audio should be enabled
     * @param fpsLimit The maximum FPS to target, or 0 for unlimited
     * @param enablePpuLogging Whether PPU logging should be enabled
     */
    override fun configureUISettings(enableAudio: Boolean, fpsLimit: Int, enablePpuLogging: Boolean) {
        // Configure Terminal-specific settings
        // Terminal UI doesn't support audio, so we ignore the enableAudio parameter
    }

    /**
     * Gets the TerminalUI instance.
     * 
     * @return The TerminalUI instance
     */
    fun getTerminalUI(): TerminalUI {
        return terminalUI
    }
}
