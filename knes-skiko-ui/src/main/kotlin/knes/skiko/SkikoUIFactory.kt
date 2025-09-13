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

import knes.emulator.input.InputHandler
import knes.emulator.ui.NESUIFactory
import knes.emulator.ui.ScreenView

/**
 * Factory for creating Skiko UI components for the NES emulator.
 */
class SkikoUIFactory : NESUIFactory {
    private val skikoUI = SkikoUI()
    override val inputHandler: InputHandler = SkikoInputHandler()

    /**
     * Creates a screen view for the NES emulator.
     * 
     * @param scale The initial scale factor for the screen view
     * @return A ScreenView implementation
     */
    override fun createScreenView(scale: Int): ScreenView {
        return SkikoScreenView(scale)
    }

    /**
     * Configures UI-specific settings.
     * 
     * @param enableAudio Whether audio should be enabled
     * @param fpsLimit The maximum FPS to target, or 0 for unlimited
     * @param enablePpuLogging Whether PPU logging should be enabled
     */
    override fun configureUISettings(enableAudio: Boolean, fpsLimit: Int, enablePpuLogging: Boolean) {
        // Configure Skiko-specific settings
    }

    /**
     * Gets the SkikoUI instance.
     * 
     * @return The SkikoUI instance
     */
    fun getSkikoUI(): SkikoUI {
        return skikoUI
    }
}
