package knes.emulator.ui

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
