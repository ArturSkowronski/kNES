package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BranchTest : FunSpec({

    val programBase = 0x8000
    val pcAfterBranch = programBase + 1

    context("BCC - branch on carry clear") {
        test("branches when carry is clear") {
            val h = CpuTestHarness()
            h.setCarry(false)
            h.execute(0x90, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when carry is set") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.execute(0x90, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BCS - branch on carry set") {
        test("branches when carry is set") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.execute(0xB0, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when carry is clear") {
            val h = CpuTestHarness()
            h.setCarry(false)
            h.execute(0xB0, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BEQ - branch on zero set") {
        test("branches when zero flag is set") {
            val h = CpuTestHarness()
            h.setZero(true)
            h.execute(0xF0, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when zero flag is clear") {
            val h = CpuTestHarness()
            h.setZero(false)
            h.execute(0xF0, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BNE - branch on zero clear") {
        test("branches when zero flag is clear") {
            val h = CpuTestHarness()
            h.setZero(false)
            h.execute(0xD0, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when zero flag is set") {
            val h = CpuTestHarness()
            h.setZero(true)
            h.execute(0xD0, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BPL - branch on positive") {
        test("branches when negative flag is clear") {
            val h = CpuTestHarness()
            h.setNegative(false)
            h.execute(0x10, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when negative flag is set") {
            val h = CpuTestHarness()
            h.setNegative(true)
            h.execute(0x10, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BMI - branch on negative") {
        test("branches when negative flag is set") {
            val h = CpuTestHarness()
            h.setNegative(true)
            h.execute(0x30, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when negative flag is clear") {
            val h = CpuTestHarness()
            h.setNegative(false)
            h.execute(0x30, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BVC - branch on overflow clear") {
        test("branches when overflow is clear") {
            val h = CpuTestHarness()
            h.setOverflow(false)
            h.execute(0x50, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when overflow is set") {
            val h = CpuTestHarness()
            h.setOverflow(true)
            h.execute(0x50, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BVS - branch on overflow set") {
        test("branches when overflow is set") {
            val h = CpuTestHarness()
            h.setOverflow(true)
            h.execute(0x70, 0x05)
            h.pc shouldBe pcAfterBranch + 0x05
        }
        test("does not branch when overflow is clear") {
            val h = CpuTestHarness()
            h.setOverflow(false)
            h.execute(0x70, 0x05)
            h.pc shouldBe pcAfterBranch
        }
    }

    context("backward branch") {
        test("BNE branches backward with negative offset") {
            val h = CpuTestHarness()
            h.setZero(false)
            h.execute(0xD0, 0xFB) // BNE -5
            h.pc shouldBe pcAfterBranch - 5
        }
    }
})
