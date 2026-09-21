package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConsoleTimingTest : FunSpec({

    test("the region follows the config") {
        ConsoleTiming.of(NesConfig(palEmulation = false)) shouldBe ConsoleTiming.NTSC
        ConsoleTiming.of(NesConfig(palEmulation = true)) shouldBe ConsoleTiming.PAL
    }

    test("NTSC runs three PPU dots per CPU cycle and needs no correction") {
        ConsoleTiming.NTSC.ppuDotsPerCpuCycle shouldBe 3
        ConsoleTiming.NTSC.addsExtraCycles shouldBe false
        ConsoleTiming.NTSC.extraCycleEvery shouldBe 0
    }

    test("PAL lengthens the CPU every fifth instruction, exactly as before") {
        // PAL is really 3.2 dots per CPU cycle. The emulator approximates it by adding a
        // CPU cycle instead of quickening the PPU — preserved verbatim so naming these
        // numbers cannot change timing. Correcting it belongs with the scheduler (C3).
        ConsoleTiming.PAL.ppuDotsPerCpuCycle shouldBe 3
        ConsoleTiming.PAL.extraCycleEvery shouldBe 5
        ConsoleTiming.PAL.addsExtraCycles shouldBe true
    }

    test("the CPU frequencies are the ones the APU samples against") {
        ConsoleTiming.NTSC.cpuFrequency shouldBe 1_789_772.5
        ConsoleTiming.PAL.cpuFrequency shouldBe 1_773_447.4
    }
})
