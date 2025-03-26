package vnes.compose

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
 * Main UI class for the Compose implementation.
 */
class ComposeUI {
    private var nes: NES? = null
    private var screenView: ComposeScreenView? = null

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
        nes.getPpu().setBuffer(screenView.getBuffer())
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

    /**
     * Cleans up resources.
     */
    fun destroy() {
        screenView?.destroy()
        screenView = null

        nes?.destroy()
        nes = null
    }
}
