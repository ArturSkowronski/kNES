package knes.api

import knes.emulator.NES
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Wraps a NES instance for API access.
 *
 * Two modes:
 * - **Standalone** (no args): creates its own headless NES. Full control: load ROM, step, input.
 * - **Shared** (pass existing NES): observes a NES driven by the Compose UI.
 *   Read-only: /state, /screen, /watch, /profiles work. /step is disabled.
 *   /press and /release still work (merged with keyboard input).
 */
class EmulatorSession(externalNes: NES? = null) {
    val controller = ApiController()

    var frameCount: Int = 0
        private set

    var romLoaded: Boolean = false
        private set

    val shared: Boolean = externalNes != null

    // Double buffer: writeBuffer receives new frames, readyBuffer is served to API.
    // Swap happens atomically in updateFrameBuffer/imageReady — no torn frames.
    private var writeBuffer = IntArray(256 * 240)
    @Volatile private var readyBuffer = IntArray(256 * 240)

    private var watchedAddresses: MutableMap<String, Int> = mutableMapOf()

    val nes: NES

    init {
        if (externalNes != null) {
            nes = externalNes
            romLoaded = externalNes.isRomLoaded
        } else {
            Globals.appletMode = true
            Globals.enableSound = false
            Globals.palEmulation = false
            Globals.timeEmulation = false

            val inputHandler = object : InputHandler {
                override fun getKeyState(padKey: Int): Short = controller.getKeyState(padKey)
            }

            val gui = object : GUI {
                override fun sendErrorMsg(message: String) {}
                override fun sendDebugMessage(message: String) {}
                override fun destroy() {}
                override fun getJoy1(): InputHandler = inputHandler
                override fun getJoy2(): InputHandler? = null
                override fun getTimer(): HiResTimer = HiResTimer()
                override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
                    System.arraycopy(buffer, 0, writeBuffer, 0, buffer.size)
                    readyBuffer = writeBuffer.also { writeBuffer = readyBuffer }
                    frameCount++
                }
            }

            nes = NES(gui)
        }
    }

    fun loadRom(path: String): Boolean {
        if (shared) return false
        romLoaded = try {
            nes.loadRom(path)
        } catch (e: Exception) {
            false
        }
        if (romLoaded) frameCount = 0
        return romLoaded
    }

    fun reset() {
        if (shared) return
        nes.reset()
        frameCount = 0
        controller.releaseAll()
    }

    fun advanceFrames(n: Int) {
        if (shared) return
        val target = frameCount + n
        val maxSteps = n * 300_000
        var steps = 0
        while (frameCount < target) {
            nes.cpu.step()
            if (++steps > maxSteps) throw IllegalStateException("advanceFrames($n) timed out")
        }
    }

    fun readMemory(addr: Int): Int = nes.cpuMemory.load(addr).toInt() and 0xFF

    fun setWatchedAddresses(addresses: Map<String, Int>) {
        watchedAddresses.clear()
        watchedAddresses.putAll(addresses)
    }

    fun getWatchedState(): Map<String, Int> = watchedAddresses.mapValues { readMemory(it.value) }

    fun getScreenPng(): ByteArray {
        val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 256, 240, readyBuffer, 0, 256)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    fun getScreenBase64(): String = java.util.Base64.getEncoder().encodeToString(getScreenPng())

    /** Allow external frame buffer updates (used by Compose UI to feed frames to shared session) */
    fun updateFrameBuffer(buffer: IntArray) {
        System.arraycopy(buffer, 0, writeBuffer, 0, buffer.size)
        readyBuffer = writeBuffer.also { writeBuffer = readyBuffer }
        frameCount++
        romLoaded = nes.isRomLoaded
    }
}
