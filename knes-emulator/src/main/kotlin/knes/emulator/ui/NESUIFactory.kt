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
import knes.controllers.ControllerProvider

/**
 * Factory interface for creating UI components for the knes.emulator.NES emulator.
 * This interface allows different UI implementations to be plugged into the emulator core.
 */
interface NESUIFactory {
    /**
     * Creates a UI controller that handles input and lifecycle management
     *
     * @param controller The controller provider to use for input
     * @return An InputHandler implementation
     */
    fun createInputHandler(controller: ControllerProvider): InputHandler

    /**
     * Creates a rendering surface that implements ScreenView interface
     *
     * @param scale The initial scale factor for the screen view
     * @return A ScreenView implementation
     */
    fun createScreenView(scale: Int): ScreenView?

    /**
     * Optional: Configuration for UI-specific settings
     *
     * @param enableAudio Whether audio should be enabled
     * @param fpsLimit The maximum FPS to target, or 0 for unlimited
     * @param enablePpuLogging Whether PPU logging should be enabled
     */
    fun configureUISettings(enableAudio: Boolean, fpsLimit: Int, enablePpuLogging: Boolean) {}
}
