package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.papu.PAPUClockFrame
import knes.emulator.ppu.PPUCycles

private class RecordingPpu : PPUCycles {
    val dots = mutableListOf<Int>()
    var emulated = 0
    override fun setCycles(cycles: Int) { dots += cycles }
    override fun emulateCycles() { emulated++ }
}

private class RecordingApu : PAPUClockFrame {
    val cycles = mutableListOf<Int>()
    override fun clockFrameCounter(cycleCount: Int) { cycles += cycleCount }
}

private fun clock(
    ppu: RecordingPpu = RecordingPpu(),
    apu: RecordingApu = RecordingApu(),
    timing: ConsoleTiming = ConsoleTiming.NTSC,
    clocksApu: Boolean = true,
) = ConsoleClock(timing, ppu, apu, clocksApu)

class ConsoleClockTest : FunSpec({

    test("the PPU is advanced by dots, the APU by CPU cycles") {
        val ppu = RecordingPpu()
        val apu = RecordingApu()

        clock(ppu, apu).advance(4)

        ppu.dots shouldBe listOf(12)
        ppu.emulated shouldBe 1
        apu.cycles shouldBe listOf(4)
    }

    test("a silent console does not clock the APU") {
        val apu = RecordingApu()

        clock(apu = apu, clocksApu = false).advance(4)

        apu.cycles shouldBe emptyList()
    }

    test("the dot ratio comes from the timing, not from the clock") {
        val ppu = RecordingPpu()

        clock(ppu, timing = ConsoleTiming.PAL).advance(10)

        ppu.dots shouldBe listOf(10 * ConsoleTiming.PAL.ppuDotsPerCpuCycle)
    }
})
