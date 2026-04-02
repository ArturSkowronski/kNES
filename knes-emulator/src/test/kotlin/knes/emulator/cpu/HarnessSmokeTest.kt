package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HarnessSmokeTest : FunSpec({
    test("LDA immediate loads value into accumulator") {
        val h = CpuTestHarness()
        h.execute(0xA9, 0x42) // LDA #$42
        h.a shouldBe 0x42
        h.zero shouldBe false
        h.negative shouldBe false
    }

    test("LDA immediate zero sets zero flag") {
        val h = CpuTestHarness()
        h.execute(0xA9, 0x00) // LDA #$00
        h.a shouldBe 0x00
        h.zero shouldBe true
    }

    test("LDA immediate negative sets negative flag") {
        val h = CpuTestHarness()
        h.execute(0xA9, 0x80) // LDA #$80
        h.a shouldBe 0x80
        h.negative shouldBe true
    }
})
