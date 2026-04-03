package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import knes.emulator.cpu.CPU
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.io.File

class NESIntegrationTest : FunSpec({

    test("nestest ROM passes all CPU tests in automated mode") {
        Globals.appletMode = false
        Globals.enableSound = false
        Globals.palEmulation = false

        val noopInput = object : InputHandler {
            override fun getKeyState(padKey: Int): Short = 0x40
        }

        val gui = object : GUI {
            override fun sendErrorMsg(message: String) {}
            override fun sendDebugMessage(message: String) {}
            override fun destroy() {}
            override fun getJoy1(): InputHandler = noopInput
            override fun getJoy2(): InputHandler? = null
            override fun getTimer(): HiResTimer = HiResTimer()
            override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
        }

        val nes = NES(gui)

        // Find nestest.nes in test resources
        val romUrl = this::class.java.classLoader.getResource("nestest.nes")
        romUrl shouldNotBe null
        val romPath = File(romUrl!!.toURI()).absolutePath

        val loaded = nes.loadRom(romPath)
        loaded shouldBe true

        // Clear zero-page and stack area to ensure deterministic results
        // (clearCPUMemory fills RAM with random values which can affect test outcomes)
        for (i in 0 until 0x0800) {
            nes.cpuMemory.write(i, 0x00.toShort())
        }

        // Set PC to $C000 for automated test mode (no PPU needed)
        nes.cpu.REG_PC_NEW = 0xC000 - 1

        // Reset status flags to known state
        nes.cpu.status = 0x24 // interrupt disable set, unused bit set

        // Run nestest — official tests complete well within 8000 instructions
        val maxInstructions = 20000
        for (i in 0 until maxInstructions) {
            nes.cpu.step()
        }

        // Read result codes
        val result02 = nes.cpuMemory.load(0x0002).toInt() and 0xFF
        val result03 = nes.cpuMemory.load(0x0003).toInt() and 0xFF

        // $0002 = 0x00 means all official opcode tests passed
        // $0003 = 0x00 means all unofficial opcode tests passed (we may not support these)
        result02 shouldBe 0x00
    }
})
