package knes.mcp

import knes.api.FrameInput
import knes.api.InputQueue
import knes.api.StepRequest
import knes.debug.GameProfile
import knes.emulator.NES
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import javax.imageio.ImageIO

class NesEmulatorSession {
    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    private val buttonNames = mapOf(
        "A" to InputHandler.KEY_A,
        "B" to InputHandler.KEY_B,
        "START" to InputHandler.KEY_START,
        "SELECT" to InputHandler.KEY_SELECT,
        "UP" to InputHandler.KEY_UP,
        "DOWN" to InputHandler.KEY_DOWN,
        "LEFT" to InputHandler.KEY_LEFT,
        "RIGHT" to InputHandler.KEY_RIGHT,
    )

    val inputQueue = InputQueue()

    private val inputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short {
            val persistent = keyStates[padKey]
            val queued = if (inputQueue.isPressed(padKey)) 0x41.toShort() else 0x40.toShort()
            return if (persistent == 0x41.toShort() || queued == 0x41.toShort()) 0x41 else 0x40
        }
    }

    var frameCount: Int = 0; private set
    var romLoaded: Boolean = false; private set

    @Volatile private var readyBuffer = IntArray(256 * 240)
    private var writeBuffer = IntArray(256 * 240)
    private var watchedAddresses: MutableMap<String, Int> = mutableMapOf()

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
                System.arraycopy(buffer, 0, writeBuffer, 0, buffer.size)
                readyBuffer = writeBuffer.also { writeBuffer = readyBuffer }
                frameCount++
            }
        }
        nes = NES(gui)
    }

    fun loadRom(path: String): Boolean {
        romLoaded = try { nes.loadRom(path) } catch (e: Exception) { false }
        if (romLoaded) frameCount = 0
        return romLoaded
    }

    fun reset() { nes.reset(); frameCount = 0; releaseAll() }

    fun step(buttons: List<String>, frames: Int) {
        setButtons(buttons)
        val target = frameCount + frames
        val maxSteps = frames * 300_000
        var steps = 0
        var lastFrame = frameCount
        while (frameCount < target) {
            nes.cpu.step()
            if (frameCount != lastFrame) {
                inputQueue.advanceFrame()
                lastFrame = frameCount
            }
            if (++steps > maxSteps) throw IllegalStateException("step timed out")
        }
    }

    fun setButtons(buttons: List<String>) {
        releaseAll()
        for (name in buttons) {
            val key = buttonNames[name.uppercase()] ?: throw IllegalArgumentException("Unknown button: $name")
            keyStates[key] = 0x41
        }
    }

    fun pressButton(name: String) {
        val key = buttonNames[name.uppercase()] ?: throw IllegalArgumentException("Unknown button: $name")
        keyStates[key] = 0x41
    }

    fun releaseButton(name: String) {
        val key = buttonNames[name.uppercase()] ?: throw IllegalArgumentException("Unknown button: $name")
        keyStates[key] = 0x40
    }

    fun releaseAll() { keyStates.fill(0x40) }

    fun enqueueSteps(steps: List<StepRequest>): CountDownLatch {
        val frameInputs = steps.flatMap { step ->
            val buttons = step.buttons.map { name ->
                buttonNames[name.uppercase()] ?: throw IllegalArgumentException("Unknown button: $name")
            }.toSet()
            List(step.frames) { FrameInput(buttons) }
        }
        return inputQueue.enqueue(frameInputs)
    }

    fun getHeldButtons(): List<String> = buttonNames.entries.filter { keyStates[it.value] == 0x41.toShort() }.map { it.key }

    fun readMemory(addr: Int): Int = nes.cpuMemory.load(addr).toInt() and 0xFF

    fun applyProfile(id: String): Boolean {
        val profile = GameProfile.get(id) ?: return false
        watchedAddresses.clear()
        watchedAddresses.putAll(profile.toWatchMap())
        return true
    }

    fun getWatchedState(): Map<String, Int> = watchedAddresses.mapValues { readMemory(it.value) }

    fun getScreenBase64(): String {
        val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 256, 240, readyBuffer, 0, 256)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
