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
 * Adapter class that implements the GUI interface by delegating to components
 * created by a NESUIFactory.
 */
class GUIAdapter(
    private val inputHandler: InputHandler,
    private val screenView: ScreenView
) : GUI {
    private val timer: HiResTimer = HiResTimer()

    override fun getJoy1(): InputHandler {
        return inputHandler
    }

    override fun getJoy2(): InputHandler? {
        // Currently only supporting one input handler
        return null
    }

    override fun getScreenView(): ScreenView {
        return screenView
    }

    override fun getTimer(): knes.emulator.utils.HiResTimer {
        return timer
    }

    override fun imageReady(skipFrame: Boolean) {
        screenView.imageReady(skipFrame)
    }

    override fun init(papuAppletFunctionality: PAPU_Applet_Functionality, showGui: Boolean) {
        screenView.init()
    }

    override fun println(s: String) {
        System.out.println(s)
    }

    override fun showErrorMsg(message: String) {
        System.err.println("ERROR: $message")
    }

    override fun showLoadProgress(percentComplete: Int) {
        // Default implementation does nothing
    }

    /**
     * Cleans up resources used by this GUI adapter.
     */
    override fun destroy() {
        inputHandler.destroy()
        screenView.destroy()
    }
}
