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
import knes.emulator.utils.HiResTimer

/**
 * UI interface for the knes.emulator.NES emulator.
 * This interface defines the core functionality required by any UI implementation,
 * without dependencies on specific UI frameworks like AWT or Compose.
 * It combines both platform-agnostic UI functionality and legacy UI requirements.
 */

interface GUI {

    // Methods from UiInfoMessageBus
    fun showErrorMsg(message: String)
    fun showLoadProgress(percentComplete: Int)
    fun destroy()

    // GUI-specific methods
    fun getJoy1(): InputHandler
    fun getJoy2(): InputHandler?
    fun getScreenView(): ScreenView
    fun getTimer(): HiResTimer
    fun imageReady(skipFrame: Boolean)
    fun init(papuAppletFunctionality: PAPU_Applet_Functionality, showGui: Boolean)
    fun println(s: String)
}
