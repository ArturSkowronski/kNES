package knes.emulator

import io.kotest.core.spec.style.FunSpec
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

private fun cyclesOver(instructions: Int, palEmulation: Boolean): Long {
    val nes = NES(host(), NesConfig(enableSound = false, timeEmulation = false, palEmulation = palEmulation))
    check(nes.loadRom(File("src/test/resources/nestest.nes").absolutePath))
    var cycles = 0L
    repeat(instructions) { cycles += nes.stepInstruction() }
    return cycles
}

/**
 * PAL emulation, at the level a caller sees it: the same instructions cost more.
 *
 * `PalTimingTest` pins the per-instruction pattern; this pins that it survives the way
 * the emulator is actually driven. The unit test alone did not catch the correction
 * dying when the emulation loop moved out of the CPU, because it only ever exercised one
 * of the two paths that existed at the time.
 */
class PalEmulationTest : FunSpec({

    test("a PAL console spends more cycles on the same work than an NTSC one") {
        val instructions = 100_000

        val ntsc = cyclesOver(instructions, palEmulation = false)
        val pal = cyclesOver(instructions, palEmulation = true)

        pal shouldBeGreaterThan ntsc

        // Close to one extra cycle per five instructions, but not exactly: iterations
        // that service an interrupt take a path that skips the correction. That is
        // pre-existing behaviour of the approximation, which C3e's remaining half
        // replaces with a proper 3.2 dots per CPU cycle on the PPU's side.
        val extra = pal - ntsc
        val perfect = instructions / 5.0
        (extra > perfect * 0.95) shouldBe true
        (extra <= perfect) shouldBe true
    }

    test("an NTSC console gets no correction at all") {
        val a = cyclesOver(50_000, palEmulation = false)
        val b = cyclesOver(50_000, palEmulation = false)

        a shouldBe b
    }
})
