package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import knes.emulator.utils.HiResTimer
import java.io.File

private fun host() = object : NesHost {
    override fun sendErrorMsg(message: String) {}
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0x40
    }
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
}

private fun loadedNes(config: NesConfig = NesConfig.HEADLESS): NES {
    val nes = NES(host(), config)
    val rom = File("src/test/resources/nestest.nes")
    check(rom.exists()) { "missing fixture: ${rom.absolutePath}" }
    check(nes.loadRom(rom.path)) { "failed to load ${rom.path}" }
    return nes
}

class SteppingApiTest : FunSpec({

    test("stepInstruction runs one instruction and reports its cycles") {
        val nes = loadedNes()
        val pcBefore = nes.cpu.REG_PC_NEW

        val cycles = nes.stepInstruction()

        cycles shouldBeGreaterThan 0
        // Every 6502 instruction is at least two cycles and at most seven.
        cycles shouldBeGreaterThanOrEqual 2
        (nes.cpu.REG_PC_NEW != pcBefore) shouldBe true
    }

    test("stepCpuCycles runs at least what was asked, overshooting by one instruction at most") {
        val nes = loadedNes()

        val consumed = nes.stepCpuCycles(100)

        consumed shouldBeGreaterThanOrEqual 100
        // Instructions are not divisible, so the overshoot is bounded by the longest one.
        (consumed < 100 + 8) shouldBe true
    }

    test("stepFrame advances exactly one frame") {
        val nes = loadedNes()

        nes.frameCount shouldBe 0L
        nes.stepFrame()
        nes.frameCount shouldBe 1L
        nes.stepFrame()
        nes.frameCount shouldBe 2L
    }

    test("a frame costs roughly an NTSC frame's worth of cycles") {
        val nes = loadedNes()
        nes.stepFrame() // first frame includes reset work

        val cycles = nes.stepFrame()

        // NTSC is ~29780 CPU cycles per frame; this is a sanity band, not an accuracy claim.
        (cycles in 20_000..40_000) shouldBe true
    }

    test("the frame boundary is observable without a host callback") {
        val nes = loadedNes()
        val seen = mutableListOf<Long>()
        nes.onFrame = { seen += it }

        nes.stepFrame()
        nes.stepFrame()

        seen shouldBe listOf(1L, 2L)
    }

    test("frameCount belongs to the instance, not the process") {
        val a = loadedNes()
        val b = loadedNes()

        a.stepFrame()
        a.stepFrame()
        b.stepFrame()

        a.frameCount shouldBe 2L
        b.frameCount shouldBe 1L
    }

    test("stepFrame refuses to hang when the CPU loop is not clocking the PPU") {
        val nes = loadedNes(NesConfig.HEADLESS.copy(steppedExecution = false))

        val error = runCatching { nes.stepFrame() }.exceptionOrNull()

        (error is IllegalStateException) shouldBe true
        error!!.message!!.contains("steppedExecution") shouldBe true
    }

    test("stepping many frames keeps the counter honest") {
        val nes = loadedNes()
        repeat(10) { nes.stepFrame() }
        nes.frameCount shouldBe 10L
        nes.frameCount shouldBeGreaterThan 0L
    }
})
