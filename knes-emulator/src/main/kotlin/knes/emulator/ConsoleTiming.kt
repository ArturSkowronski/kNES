package knes.emulator

/**
 * How fast the parts of one console run relative to each other.
 *
 * These numbers were spread through `CPU.emulate` as a bare `* 3` and a counter that
 * added a cycle every fifth instruction, which is why nobody could see that the PPU
 * ratio was NTSC-only and the PAL correction was being applied on the CPU's side of the
 * relationship rather than the PPU's.
 *
 * Naming them does not make the scheduling right — see task C3 — but it makes it
 * visible, which has to come first.
 */
enum class ConsoleTiming(
    /** CPU cycles per second. */
    val cpuFrequency: Double,
    /** PPU dots per CPU cycle, as the CPU loop applies it. */
    val ppuDotsPerCpuCycle: Int,
    /**
     * Instructions between the extra CPU cycle PAL emulation adds, or 0 for none.
     *
     * A rough stand-in for PAL's 3.2 dots per CPU cycle, applied by lengthening the CPU
     * instead of quickening the PPU. Kept exactly as it was so this change cannot alter
     * timing; correcting it belongs with the scheduler work.
     */
    val extraCycleEvery: Int,
) {
    NTSC(cpuFrequency = 1_789_772.5, ppuDotsPerCpuCycle = 3, extraCycleEvery = 0),
    PAL(cpuFrequency = 1_773_447.4, ppuDotsPerCpuCycle = 3, extraCycleEvery = 5);

    val addsExtraCycles: Boolean get() = extraCycleEvery > 0

    companion object {
        fun of(config: NesConfig): ConsoleTiming = if (config.palEmulation) PAL else NTSC
    }
}
