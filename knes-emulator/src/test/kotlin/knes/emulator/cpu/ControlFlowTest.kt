package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ControlFlowTest : FunSpec({
    val programBase = 0x8000

    context("JMP absolute") {
        test("jumps to absolute address") {
            val h = CpuTestHarness()
            h.execute(0x4C, 0x00, 0x90)
            h.pc shouldBe 0x9000 - 1
        }
    }

    context("JMP indirect") {
        test("jumps to address stored at pointer") {
            val h = CpuTestHarness()
            h.writeMem(0x0300, 0x00)
            h.writeMem(0x0301, 0x90)
            h.execute(0x6C, 0x00, 0x03)
            h.pc shouldBe 0x9000 - 1
        }
    }

    context("JSR") {
        test("pushes return address and jumps") {
            val h = CpuTestHarness()
            val spBefore = h.sp
            h.execute(0x20, 0x00, 0x90)
            h.pc shouldBe 0x9000 - 1
            val pushedHi = h.readMem(spBefore)
            val pushedLo = h.readMem(0x0100 or ((spBefore - 1) and 0xFF))
            val returnAddr = (pushedHi shl 8) or pushedLo
            returnAddr shouldBe 0x8002
        }
    }

    context("RTS") {
        test("pulls return address and jumps back") {
            val h = CpuTestHarness()
            h.writeMem(0x9000, 0x60) // RTS at target
            h.execute(0x20, 0x00, 0x90) // JSR $9000
            h.cpu.step() // execute RTS
            h.pc shouldBe 0x8002
        }
    }

    context("NOP") {
        test("does nothing") {
            val h = CpuTestHarness()
            h.a = 0x42; h.x = 0x10; h.y = 0x20
            val statusBefore = h.cpu.status
            h.execute(0xEA)
            h.a shouldBe 0x42; h.x shouldBe 0x10; h.y shouldBe 0x20
            h.cpu.status shouldBe statusBefore
        }
    }

    context("Flag instructions") {
        test("SEC sets carry") { val h = CpuTestHarness(); h.setCarry(false); h.execute(0x38); h.carry shouldBe true }
        test("CLC clears carry") { val h = CpuTestHarness(); h.setCarry(true); h.execute(0x18); h.carry shouldBe false }
        test("SED sets decimal") { val h = CpuTestHarness(); h.setDecimal(false); h.execute(0xF8); h.decimal shouldBe true }
        test("CLD clears decimal") { val h = CpuTestHarness(); h.setDecimal(true); h.execute(0xD8); h.decimal shouldBe false }
        test("SEI sets interrupt disable") { val h = CpuTestHarness(); h.setInterruptDisable(false); h.execute(0x78); h.interruptDisable shouldBe true }
        test("CLI clears interrupt disable") { val h = CpuTestHarness(); h.setInterruptDisable(true); h.execute(0x58); h.interruptDisable shouldBe false }
        test("CLV clears overflow") { val h = CpuTestHarness(); h.setOverflow(true); h.execute(0xB8); h.overflow shouldBe false }
    }
})
