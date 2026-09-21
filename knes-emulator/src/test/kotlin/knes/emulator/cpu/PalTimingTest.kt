package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.NesConfig

private fun steppedNopCycles(palEmulation: Boolean): Int {
    val harness = CpuTestHarness()
    harness.cpu.init(
        harness.memory,
        NesConfig(steppedExecution = false, enableSound = false, palEmulation = palEmulation),
    )
    harness.cpu.setMapper(TestMemoryAccess(harness.memory))
    harness.cpu.reset()

    var total = 0
    repeat(10) {
        harness.execute(0xEA) // NOP, two cycles
        total += harness.cpu.lastStepCycles
    }
    return total
}

class PalTimingTest : FunSpec({

    test("PAL's extra cycle never fires under stepping — this should change with C3e") {
        // PAL adds a CPU cycle every fifth instruction, so ten stepped NOPs ought to
        // cost 22. They cost 20, because the counter that tracks "every fifth" is a
        // local in CPU.emulate and step() re-enters emulate() per instruction, resetting
        // it before it can ever reach five.
        //
        // Harmless today: every stepped configuration in this repo is NTSC. Pinned so
        // that whoever moves the schedule out of the CPU loop (C3c) and fixes PAL
        // properly (C3e) sees this assertion flip.
        steppedNopCycles(palEmulation = true) shouldBe 20
        steppedNopCycles(palEmulation = false) shouldBe 20
    }
})
