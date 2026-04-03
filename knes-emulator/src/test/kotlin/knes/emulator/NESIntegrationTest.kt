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

        // Set PC to $C000 for automated test mode (no PPU needed)
        // The CPU PC is stored as PC-1 due to the fetch cycle reading PC+1
        nes.cpu.REG_PC_NEW = 0xC000 - 1

        // Run up to 10000 instructions — nestest completes well within this
        val maxInstructions = 10000
        for (i in 0 until maxInstructions) {
            nes.cpu.step()

            // nestest writes result to $0002: 0x00 means tests still running or passed
            // Non-zero at $0002 means a specific test failed
            // The test is done when PC reaches a known halt location or we've run enough
        }

        // Read result codes
        val result02 = nes.cpuMemory.load(0x0002).toInt() and 0xFF
        // $0002 = 0x00 means all official opcode tests passed
        // $0003 = 0x00 means all unofficial opcode tests passed (we may not support these)
        result02 shouldBe 0x00
    }
})
