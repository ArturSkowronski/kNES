package knes.emulator

import knes.emulator.papu.PAPUClockFrame
import knes.emulator.ppu.PPUCycles

/**
 * Advances the rest of the console by however much the CPU just ran.
 *
 * The dispatch used to sit inline in `CPU.emulate`, which meant the CPU knew the PPU's
 * dot ratio and decided for itself whether the APU should hear about a cycle. One place
 * now owns that, which is the point of the wider scheduler work (task C3) — the CPU asks
 * to be caught up with rather than doing the catching up.
 *
 * Still called *from* the CPU's loop. Inverting that is C3c.
 */
class ConsoleClock(
    private val timing: ConsoleTiming,
    private val ppu: PPUCycles,
    private val apu: PAPUClockFrame,
    private val clocksApu: Boolean,
) {
    fun advance(cpuCycles: Int) {
        ppu.setCycles(cpuCycles * timing.ppuDotsPerCpuCycle)
        ppu.emulateCycles()
        if (clocksApu) {
            apu.clockFrameCounter(cpuCycles)
        }
    }
}
