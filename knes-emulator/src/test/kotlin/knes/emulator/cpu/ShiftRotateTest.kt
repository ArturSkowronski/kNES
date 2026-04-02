package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class ShiftCase(
    val desc: String,
    val input: Int, val carryIn: Boolean,
    val expected: Int, val carry: Boolean, val zero: Boolean, val negative: Boolean
)

class ShiftRotateTest : FunSpec({

    context("ASL accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("basic shift left", 0x01, false, 0x02, false, false, false),
                ShiftCase("shift into carry", 0x80, false, 0x00, true, true, false),
                ShiftCase("shift 0xFF", 0xFF, false, 0xFE, true, false, true),
                ShiftCase("zero stays zero", 0x00, false, 0x00, false, true, false),
                ShiftCase("0x40 becomes negative", 0x40, false, 0x80, false, false, true),
                ShiftCase("ignores carry in", 0x01, true, 0x02, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x0A)
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ASL zero page") {
        test("shifts memory value") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0x40)
            h.execute(0x06, 0x42)
            h.readMem(0x42) shouldBe 0x80
            h.carry shouldBe false
            h.negative shouldBe true
        }

        test("carry out from memory") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0x80)
            h.execute(0x06, 0x42)
            h.readMem(0x42) shouldBe 0x00
            h.carry shouldBe true
            h.zero shouldBe true
        }
    }

    context("LSR accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("basic shift right", 0x02, false, 0x01, false, false, false),
                ShiftCase("shift into carry", 0x01, false, 0x00, true, true, false),
                ShiftCase("shift 0xFF", 0xFF, false, 0x7F, true, false, false),
                ShiftCase("zero stays zero", 0x00, false, 0x00, false, true, false),
                ShiftCase("always clears negative", 0x80, false, 0x40, false, false, false),
                ShiftCase("ignores carry in", 0x02, true, 0x01, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x4A)
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("LSR zero page") {
        test("shifts memory value") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0x04)
            h.execute(0x46, 0x42)
            h.readMem(0x42) shouldBe 0x02
            h.carry shouldBe false
        }
    }

    context("ROL accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("rotate with carry=0", 0x01, false, 0x02, false, false, false),
                ShiftCase("rotate with carry=1", 0x01, true, 0x03, false, false, false),
                ShiftCase("rotate bit 7 into carry", 0x80, false, 0x00, true, true, false),
                ShiftCase("rotate bit 7 into carry, carry into bit 0", 0x80, true, 0x01, true, false, false),
                ShiftCase("0xFF with carry=0", 0xFF, false, 0xFE, true, false, true),
                ShiftCase("0xFF with carry=1", 0xFF, true, 0xFF, true, false, true),
                ShiftCase("zero with carry=0", 0x00, false, 0x00, false, true, false),
                ShiftCase("zero with carry=1", 0x00, true, 0x01, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x2A)
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ROL zero page") {
        test("rotates memory value") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.writeMem(0x42, 0x40)
            h.execute(0x26, 0x42)
            h.readMem(0x42) shouldBe 0x81
            h.carry shouldBe false
        }
    }

    context("ROR accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("rotate with carry=0", 0x02, false, 0x01, false, false, false),
                ShiftCase("rotate with carry=1", 0x02, true, 0x81, false, false, true),
                ShiftCase("rotate bit 0 into carry", 0x01, false, 0x00, true, true, false),
                ShiftCase("rotate bit 0 into carry, carry into bit 7", 0x01, true, 0x80, true, false, true),
                ShiftCase("0xFF with carry=0", 0xFF, false, 0x7F, true, false, false),
                ShiftCase("0xFF with carry=1", 0xFF, true, 0xFF, true, false, true),
                ShiftCase("zero with carry=0", 0x00, false, 0x00, false, true, false),
                ShiftCase("zero with carry=1", 0x00, true, 0x80, false, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x6A)
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ROR zero page") {
        test("rotates memory value") {
            val h = CpuTestHarness()
            h.setCarry(false)
            h.writeMem(0x42, 0x01)
            h.execute(0x66, 0x42)
            h.readMem(0x42) shouldBe 0x00
            h.carry shouldBe true
            h.zero shouldBe true
        }
    }
})
