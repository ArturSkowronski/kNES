package knes.terminal

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

import vnes.emulator.NES

/**
 * Main UI class for the Terminal implementation.
 */
class TerminalUI {
    private var nes: NES? = null
    private var screenView: TerminalScreenView? = null

    /**
     * Initializes the UI with the specified NES instance.
     * 
     * @param nes The NES instance to use
     * @param screenView The TerminalScreenView to use for rendering
     */
    fun init(nes: NES, screenView: TerminalScreenView) {
        this.nes = nes
        this.screenView = screenView

        // Set the buffer on the PPU to prevent NullPointerException
        // The PPU needs a buffer to render to, and it expects this buffer to be set from outside
        // If the buffer is not set, a NullPointerException will occur in PPU.renderFramePartially
        nes.ppu!!.buffer = screenView.getBuffer()
    }

    /**
     * Starts the emulator.
     */
    fun startEmulator() {
        println("Starting NES emulation in terminal mode...")
        nes?.startEmulation()
    }

    /**
     * Stops the emulator.
     */
    fun stopEmulator() {
        println("Stopping NES emulation...")
        nes?.stopEmulation()
    }

    /**
     * Loads a ROM file.
     * 
     * @param path The path to the ROM file
     * @return True if the ROM was loaded successfully, false otherwise
     */
    fun loadRom(path: String): Boolean {
        println("Loading ROM: $path")
        return nes?.loadRom(path) ?: false
    }

    /**
     * Cleans up resources.
     */
    fun destroy() {
        println("Cleaning up resources...")
        screenView?.destroy()
        screenView = null

        nes?.destroy()
        nes = null
    }
}