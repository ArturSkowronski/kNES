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

package knes.compose

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

import knes.emulator.NES

/**
 * Main UI class for the Compose implementation.
 */
class ComposeUI {
    private var nes: NES? = null
    private var screenView: ComposeScreenView? = null
    private var inputHandler: ComposeInputHandler? = null

    /**
     * Initializes the UI with the specified NES instance.
     * 
     * @param nes The NES instance to use
     * @param screenView The ComposeScreenView to use for rendering
     */
    fun init(nes: NES, screenView: ComposeScreenView) {
        this.nes = nes
        this.screenView = screenView

        // Set the NES instance on the screen view
        screenView.setNES(nes)

        // Set the buffer on the PPU to prevent NullPointerException
        // The PPU needs a buffer to render to, and it expects this buffer to be set from outside
        // If the buffer is not set, a NullPointerException will occur in PPU.renderFramePartially
        nes.ppu!!.buffer = screenView.getBuffer()
    }

    /**
     * Sets the input handler for this UI.
     * This is necessary to connect the input handler to the NES instance.
     * 
     * @param inputHandler The input handler to use
     */
    fun setInputHandler(inputHandler: ComposeInputHandler) {
        this.inputHandler = inputHandler
    }

    /**
     * Starts the emulator.
     */
    fun startEmulator() {
        nes?.startEmulation()
    }

    /**
     * Stops the emulator.
     */
    fun stopEmulator() {
        nes?.stopEmulation()
    }

    /**
     * Loads a ROM file.
     * 
     * @param path The path to the ROM file
     * @return True if the ROM was loaded successfully, false otherwise
     */
    fun loadRom(path: String): Boolean {
        return nes?.loadRom(path) ?: false
    }
}
