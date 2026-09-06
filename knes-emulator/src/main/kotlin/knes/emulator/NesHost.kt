package knes.emulator

import knes.emulator.input.InputHandler
import knes.emulator.utils.HiResTimer

/**
 * Host boundary used by the emulator core.
 *
 * UI implementations may still implement the legacy GUI interface; the core
 * depends on this smaller platform port so it can be driven by tests, APIs,
 * tools, or desktop UIs without naming a presentation layer.
 */
interface NesHost {
    fun sendErrorMsg(message: String)
    fun sendDebugMessage(message: String)
    fun destroy()
    fun getJoy1(): InputHandler
    fun getJoy2(): InputHandler?
    fun getTimer(): HiResTimer
    fun imageReady(skipFrame: Boolean, buffer: IntArray)
}
