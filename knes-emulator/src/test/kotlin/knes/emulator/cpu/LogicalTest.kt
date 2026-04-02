package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class LogicalCase(
    val desc: String,
    val a: Int, val value: Int,
    val expected: Int, val zero: Boolean, val negative: Boolean
)

class LogicalTest : FunSpec({

    context("AND immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                LogicalCase("basic AND", 0xFF, 0x0F, 0x0F, false, false),
                LogicalCase("result zero", 0xF0, 0x0F, 0x00, true, false),
                LogicalCase("result negative", 0xFF, 0x80, 0x80, false, true),
                LogicalCase("identity (AND with 0xFF)", 0x42, 0xFF, 0x42, false, false),
                LogicalCase("clear all (AND with 0x00)", 0xFF, 0x00, 0x00, true, false),
                LogicalCase("single bit", 0xAA, 0x55, 0x00, true, false),
                LogicalCase("high nibble", 0xAB, 0xF0, 0xA0, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.execute(0x29, case.value)
            h.a shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("AND zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x42, 0x0F)
            h.execute(0x25, 0x42)
            h.a shouldBe 0x0F
        }
    }

    context("AND zero page,X") {
        test("reads from (zp + X)") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.x = 0x02
            h.writeMem(0x44, 0x0F)
            h.execute(0x35, 0x42)
            h.a shouldBe 0x0F
        }
    }

    context("AND absolute") {
        test("reads from absolute address") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x0300, 0x0F)
            h.execute(0x2D, 0x00, 0x03)
            h.a shouldBe 0x0F
        }
    }

    context("ORA immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                LogicalCase("basic ORA", 0xF0, 0x0F, 0xFF, false, true),
                LogicalCase("result zero", 0x00, 0x00, 0x00, true, false),
                LogicalCase("identity (ORA with 0x00)", 0x42, 0x00, 0x42, false, false),
                LogicalCase("set all (ORA with 0xFF)", 0x00, 0xFF, 0xFF, false, true),
                LogicalCase("negative bit", 0x00, 0x80, 0x80, false, true),
                LogicalCase("no overlap", 0xAA, 0x55, 0xFF, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.execute(0x09, case.value)
            h.a shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ORA zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0xF0
            h.writeMem(0x42, 0x0F)
            h.execute(0x05, 0x42)
            h.a shouldBe 0xFF
        }
    }

    context("EOR immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                LogicalCase("basic XOR", 0xFF, 0x0F, 0xF0, false, true),
                LogicalCase("result zero (same values)", 0xAA, 0xAA, 0x00, true, false),
                LogicalCase("identity (XOR with 0x00)", 0x42, 0x00, 0x42, false, false),
                LogicalCase("invert all (XOR with 0xFF)", 0xAA, 0xFF, 0x55, false, false),
                LogicalCase("single bit flip", 0x01, 0x01, 0x00, true, false),
                LogicalCase("high bit flip", 0x00, 0x80, 0x80, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.execute(0x49, case.value)
            h.a shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("EOR zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x42, 0x0F)
            h.execute(0x45, 0x42)
            h.a shouldBe 0xF0
        }
    }

    context("BIT zero page") {
        test("sets zero flag when AND is zero") {
            val h = CpuTestHarness()
            h.a = 0x0F
            h.writeMem(0x42, 0xF0)
            h.execute(0x24, 0x42)
            h.zero shouldBe true
            h.negative shouldBe true
            h.overflow shouldBe true
        }

        test("clears zero flag when AND is non-zero") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x42, 0x3F)
            h.execute(0x24, 0x42)
            h.zero shouldBe false
            h.negative shouldBe false
            h.overflow shouldBe false
        }

        test("does not modify accumulator") {
            val h = CpuTestHarness()
            h.a = 0xAB
            h.writeMem(0x42, 0x00)
            h.execute(0x24, 0x42)
            h.a shouldBe 0xAB
        }
    }

    context("BIT absolute") {
        test("reads from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x0F
            h.writeMem(0x0300, 0xC0)
            h.execute(0x2C, 0x00, 0x03)
            h.zero shouldBe true
            h.negative shouldBe true
            h.overflow shouldBe true
        }
    }
})
