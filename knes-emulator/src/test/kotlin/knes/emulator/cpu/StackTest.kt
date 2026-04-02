package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StackTest : FunSpec({

    context("PHA - push accumulator") {
        test("pushes A to stack and decrements SP") {
            val h = CpuTestHarness()
            h.a = 0x42
            val spBefore = h.sp
            h.execute(0x48)
            h.readMem(spBefore) shouldBe 0x42
            h.sp shouldBe (0x0100 or ((spBefore - 1) and 0xFF))
        }
    }

    context("PLA - pull accumulator") {
        test("pulls value from stack into A") {
            val h = CpuTestHarness()
            h.a = 0x42
            h.executeN(2, 0x48, 0x68)
            h.a shouldBe 0x42
            h.zero shouldBe false
            h.negative shouldBe false
        }
        test("sets zero flag when pulling zero") {
            val h = CpuTestHarness()
            h.a = 0x00
            h.executeN(2, 0x48, 0x68)
            h.a shouldBe 0x00
            h.zero shouldBe true
        }
        test("sets negative flag when pulling negative") {
            val h = CpuTestHarness()
            h.a = 0x80
            h.executeN(2, 0x48, 0x68)
            h.a shouldBe 0x80
            h.negative shouldBe true
        }
    }

    context("PHP - push processor status") {
        test("pushes status flags to stack") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.setZero(true)
            h.setOverflow(true)
            val spBefore = h.sp
            h.execute(0x08)
            val pushed = h.readMem(spBefore)
            (pushed and 0x01) shouldBe 1
            (pushed and 0x02) shouldBe 2
            (pushed and 0x10) shouldBe 0x10
            (pushed and 0x20) shouldBe 0x20
            (pushed and 0x40) shouldBe 0x40
        }
    }

    context("PLP - pull processor status") {
        test("restores flags from stack") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.setOverflow(true)
            h.setNegative(false)
            h.executeN(2, 0x08, 0x28)
            h.carry shouldBe true
            h.overflow shouldBe true
        }
    }

    context("PHA/PLA round-trip") {
        test("multiple push/pull values are LIFO") {
            val h = CpuTestHarness()
            h.a = 0x11
            h.executeN(4, 0x48, 0xA9, 0x22, 0x48, 0x68)
            h.a shouldBe 0x22
            h.execute(0x68)
            h.a shouldBe 0x11
        }
    }
})
