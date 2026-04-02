package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class AdcCase(
    val desc: String,
    val a: Int, val value: Int, val carryIn: Boolean,
    val expected: Int, val carry: Boolean, val overflow: Boolean, val zero: Boolean, val negative: Boolean
)

data class SbcCase(
    val desc: String,
    val a: Int, val value: Int, val carryIn: Boolean,
    val expected: Int, val carry: Boolean, val overflow: Boolean, val zero: Boolean, val negative: Boolean
)

class ArithmeticTest : FunSpec({

    context("ADC immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                AdcCase("basic add", 0x10, 0x20, false, 0x30, false, false, false, false),
                AdcCase("add with carry in", 0x10, 0x20, true, 0x31, false, false, false, false),
                AdcCase("result zero", 0x00, 0x00, false, 0x00, false, false, true, false),
                AdcCase("carry out (0xFF + 0x01)", 0xFF, 0x01, false, 0x00, true, false, true, false),
                AdcCase("carry out (0x80 + 0x80)", 0x80, 0x80, false, 0x00, true, true, true, false),
                AdcCase("positive overflow (0x7F + 0x01)", 0x7F, 0x01, false, 0x80, false, true, false, true),
                AdcCase("negative result", 0x00, 0x80, false, 0x80, false, false, false, true),
                AdcCase("no overflow on different signs", 0x80, 0x01, false, 0x81, false, false, false, true),
                AdcCase("carry in causes carry out", 0xFF, 0x00, true, 0x00, true, false, true, false),
                AdcCase("carry in causes overflow", 0x7F, 0x00, true, 0x80, false, true, false, true),
                AdcCase("negative overflow (0x80 + 0xFF = -128 + -1)", 0x80, 0xFF, false, 0x7F, true, true, false, false),
                AdcCase("max no overflow", 0x3F, 0x40, false, 0x7F, false, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.setCarry(case.carryIn)
            h.execute(0x69, case.value) // ADC #imm
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.overflow shouldBe case.overflow
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ADC zero page") {
        test("reads value from zero page address") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.setCarry(false)
            h.writeMem(0x42, 0x20)
            h.execute(0x65, 0x42)
            h.a shouldBe 0x30
        }
    }

    context("ADC absolute") {
        test("reads value from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.setCarry(false)
            h.writeMem(0x0300, 0x20)
            h.execute(0x6D, 0x00, 0x03)
            h.a shouldBe 0x30
        }
    }

    context("ADC zero page,X") {
        test("reads from (zp + X) with wrapping") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x05
            h.setCarry(false)
            h.writeMem(0x47, 0x20)
            h.execute(0x75, 0x42)
            h.a shouldBe 0x30
        }

        test("wraps around zero page") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x10
            h.setCarry(false)
            h.writeMem(0x0F, 0x20)
            h.execute(0x75, 0xFF)
            h.a shouldBe 0x30
        }
    }

    context("ADC absolute,X") {
        test("reads from (abs + X)") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x05
            h.setCarry(false)
            h.writeMem(0x0305, 0x20)
            h.execute(0x7D, 0x00, 0x03)
            h.a shouldBe 0x30
        }
    }

    context("ADC absolute,Y") {
        test("reads from (abs + Y)") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.y = 0x05
            h.setCarry(false)
            h.writeMem(0x0305, 0x20)
            h.execute(0x79, 0x00, 0x03)
            h.a shouldBe 0x30
        }
    }

    context("ADC (indirect,X)") {
        test("reads from address pointed to by (zp+X)") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x02
            h.setCarry(false)
            h.writeMem(0x44, 0x00)
            h.writeMem(0x45, 0x03)
            h.writeMem(0x0300, 0x20)
            h.execute(0x61, 0x42)
            h.a shouldBe 0x30
        }
    }

    context("ADC (indirect),Y") {
        test("reads from address pointed to by (zp)+Y") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.y = 0x05
            h.setCarry(false)
            h.writeMem(0x42, 0x00)
            h.writeMem(0x43, 0x03)
            h.writeMem(0x0305, 0x20)
            h.execute(0x71, 0x42)
            h.a shouldBe 0x30
        }
    }

    context("SBC immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                SbcCase("basic subtract", 0x30, 0x10, true, 0x20, true, false, false, false),
                SbcCase("subtract with borrow (carry=0)", 0x30, 0x10, false, 0x1F, true, false, false, false),
                SbcCase("result zero", 0x10, 0x10, true, 0x00, true, false, true, false),
                SbcCase("borrow (result negative unsigned)", 0x10, 0x30, true, 0xE0, false, false, false, true),
                SbcCase("positive overflow (0x80 - 0x01)", 0x80, 0x01, true, 0x7F, true, true, false, false),
                SbcCase("negative overflow (0x7F - 0xFF)", 0x7F, 0xFF, true, 0x80, false, true, false, true),
                SbcCase("0x00 - 0x01 with carry", 0x00, 0x01, true, 0xFF, false, false, false, true),
                SbcCase("0xFF - 0xFF with carry", 0xFF, 0xFF, true, 0x00, true, false, true, false),
                SbcCase("0x00 - 0x00 no carry (borrow)", 0x00, 0x00, false, 0xFF, false, false, false, true),
                SbcCase("subtract zero", 0x42, 0x00, true, 0x42, true, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.setCarry(case.carryIn)
            h.execute(0xE9, case.value)
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.overflow shouldBe case.overflow
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("SBC zero page") {
        test("reads value from zero page address") {
            val h = CpuTestHarness()
            h.a = 0x30
            h.setCarry(true)
            h.writeMem(0x42, 0x10)
            h.execute(0xE5, 0x42)
            h.a shouldBe 0x20
        }
    }

    context("SBC absolute") {
        test("reads value from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x30
            h.setCarry(true)
            h.writeMem(0x0300, 0x10)
            h.execute(0xED, 0x00, 0x03)
            h.a shouldBe 0x20
        }
    }
})
