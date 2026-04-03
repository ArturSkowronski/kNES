package knes.emulator.e2e

import knes.emulator.NES
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.io.File

class EmulatorTestHarness(romPath: String) {

    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    private val inputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = keyStates[padKey]
    }

    var frameCount: Int = 0
        private set

    val nes: NES

    init {
        // appletMode = true is required so that PPU cycles execute on each cpu.step(),
        // which causes imageReady() to fire at the end of each VBlank — giving us real frames.
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
                frameCount++
            }
        }

        nes = NES(gui)

        val loaded = nes.loadRom(romPath)
        if (!loaded) {
            throw IllegalArgumentException("Failed to load ROM: $romPath")
        }

        // Clear zero-page RAM for deterministic behavior
        for (i in 0 until 0x0800) {
            nes.cpuMemory.write(i, 0x00.toShort())
        }
    }

    fun advanceFrames(n: Int) {
        val targetFrame = frameCount + n
        // Safety limit: each NES frame is ~29780 CPU cycles, allow 10x headroom
        val maxSteps = n * 300_000
        var steps = 0
        while (frameCount < targetFrame) {
            nes.cpu.step()
            if (++steps > maxSteps) {
                throw IllegalStateException(
                    "advanceFrames($n) timed out after $maxSteps CPU steps (frameCount=$frameCount, target=$targetFrame). CPU crashed=${nes.cpu.crash}"
                )
            }
        }
    }

    fun advanceUntil(maxFrames: Int, condition: () -> Boolean): Boolean {
        val startFrame = frameCount
        while (frameCount - startFrame < maxFrames) {
            nes.cpu.step()
            if (condition()) return true
        }
        return false
    }

    fun pressButton(key: Int) {
        keyStates[key] = 0x41
    }

    fun releaseButton(key: Int) {
        keyStates[key] = 0x40
    }

    fun readMemory(addr: Int): Int {
        return nes.cpuMemory.load(addr).toInt() and 0xFF
    }

    companion object {
        fun findSmb(): String? {
            // 1. System property
            System.getProperty("knes.test.rom.smb")?.let {
                if (File(it).exists()) return it
            }
            // 2. Environment variable
            System.getenv("KNES_TEST_ROM_SMB")?.let {
                if (File(it).exists()) return it
            }
            // 3. Default paths (check both module-relative and project-relative locations)
            for (path in listOf(
                "roms/smb.nes", "roms/knes.nes",
                "../roms/smb.nes", "../roms/knes.nes"
            )) {
                val f = File(path)
                if (f.exists()) return f.absolutePath
            }
            return null
        }
    }
}
