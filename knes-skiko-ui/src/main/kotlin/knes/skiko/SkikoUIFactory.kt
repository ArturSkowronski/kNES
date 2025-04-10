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

import vnes.emulator.input.InputHandler
import vnes.emulator.ui.NESUIFactory
import vnes.emulator.ui.ScreenView

/**
 * Factory for creating Skiko UI components for the NES emulator.
 */
class SkikoUIFactory : NESUIFactory {
    private val skikoUI = SkikoUI()

    /**
     * Creates an input handler for the NES emulator.
     * 
     * @return An InputHandler implementation
     */
    override fun createInputHandler(): InputHandler {
        return SkikoInputHandler()
    }

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
