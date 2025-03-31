package vnes.emulator;
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

import vnes.emulator.input.InputHandler;
import vnes.emulator.ui.ScreenView;

/**
 * Factory interface for creating UI components for the NES emulator.
 * This interface allows different UI implementations to be plugged into the emulator core.
 */
public interface NESUIFactory {
    /**
     * Creates a UI controller that handles input and lifecycle management
     * 
     * @param nes The NES instance to associate with the input handler
     * @return An InputHandler implementation
     */
    InputHandler createInputHandler(NES nes);

    /**
     * Creates a rendering surface that implements ScreenView interface
     * 
     * @param scale The initial scale factor for the screen view
     * @return A ScreenView implementation
     */
    ScreenView createScreenView(int scale);

    /**
     * Optional: Configuration for UI-specific settings
     * 
     * @param enableAudio Whether audio should be enabled
     * @param fpsLimit The maximum FPS to target, or 0 for unlimited
     * @param enablePpuLogging Whether PPU logging should be enabled
     */
    default void configureUISettings(boolean enableAudio, int fpsLimit, boolean enablePpuLogging) {}

    /**
     * @deprecated Use {@link #configureUISettings(boolean, int, boolean)} instead
     */
    @Deprecated
    default void configureUISettings(boolean enableAudio, int fpsLimit) {
        configureUISettings(enableAudio, fpsLimit, true);
    }
}
