package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.NesConfig

private fun steppedNopCycles(palEmulation: Boolean): Int {
    val harness = CpuTestHarness()
    harness.cpu.init(harness.memory, NesConfig(enableSound = false, palEmulation = palEmulation))
    harness.cpu.setMapper(TestMemoryAccess(harness.memory))
    harness.cpu.reset()

    var total = 0
    repeat(10) {
        harness.execute(0xEA) // NOP, two cycles
        total += harness.cpu.lastStepCycles
    }
    return total
}

/**
 * PAL adds a CPU cycle every fifth instruction. The counter tracking "every fifth" used
 * to be a local in `CPU.emulate`, so it reset on every entry: the correction fired only
 * while one `emulate` call ran many instructions, and never under stepping. Once the
 * emulation loop moved out of the CPU (C3c) every instruction became its own call, and
 * the correction stopped firing anywhere at all.
 *
 * These pin that it now applies the same way however the CPU is driven.
 */
class PalTimingTest : FunSpec({

    test("PAL costs more than NTSC over the same instructions") {
        // Ten NOPs: 20 cycles, plus two extra for the fifth and tenth instruction.
        steppedNopCycles(palEmulation = true) shouldBe 22
        steppedNopCycles(palEmulation = false) shouldBe 20
    }

    test("the extra cycle lands every fifth instruction, not in a clump") {
        val harness = CpuTestHarness()
        harness.cpu.init(harness.memory, NesConfig(enableSound = false, palEmulation = true))
        harness.cpu.setMapper(TestMemoryAccess(harness.memory))
        harness.cpu.reset()

        val perInstruction = (1..10).map {
            harness.execute(0xEA)
            harness.cpu.lastStepCycles
        }

        perInstruction shouldBe listOf(2, 2, 2, 2, 3, 2, 2, 2, 2, 3)
    }
})
