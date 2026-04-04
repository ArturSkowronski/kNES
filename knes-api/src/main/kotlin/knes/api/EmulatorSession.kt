package knes.api

import knes.emulator.NES
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class EmulatorSession {
    val controller = ApiController()

    var frameCount: Int = 0
        private set

    var romLoaded: Boolean = false
        private set

    private var currentBuffer = IntArray(256 * 240)
    private var watchedAddresses: MutableMap<String, Int> = mutableMapOf()

    private val inputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = controller.getKeyState(padKey)
    }

    val nes: NES

    init {
        Globals.appletMode = true
        Globals.enableSound = false
        Globals.palEmulation = false
        Globals.timeEmulation = false

        val gui = object : GUI {
            override fun sendErrorMsg(message: String) {}
            override fun sendDebugMessage(message: String) {}
            override fun destroy() {}
            override fun getJoy1(): InputHandler = inputHandler
            override fun getJoy2(): InputHandler? = null
            override fun getTimer(): HiResTimer = HiResTimer()
            override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
                System.arraycopy(buffer, 0, currentBuffer, 0, buffer.size)
                frameCount++
            }
        }

        nes = NES(gui)
    }

    fun loadRom(path: String): Boolean {
        romLoaded = try {
            nes.loadRom(path)
        } catch (e: Exception) {
            false
        }
        if (romLoaded) frameCount = 0
        return romLoaded
    }

    fun reset() {
        nes.reset()
        frameCount = 0
        controller.releaseAll()
    }

    fun advanceFrames(n: Int) {
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
        img.setRGB(0, 0, 256, 240, currentBuffer, 0, 256)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    fun getScreenBase64(): String = java.util.Base64.getEncoder().encodeToString(getScreenPng())
}
