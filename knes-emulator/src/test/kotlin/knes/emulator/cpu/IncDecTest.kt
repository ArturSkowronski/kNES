package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class IncDecCase(
    val desc: String,
    val input: Int,
    val expected: Int, val zero: Boolean, val negative: Boolean
)

class IncDecTest : FunSpec({

    val incCases = listOf(
        IncDecCase("basic increment", 0x10, 0x11, false, false),
        IncDecCase("increment to zero (wraparound)", 0xFF, 0x00, true, false),
        IncDecCase("increment to negative", 0x7F, 0x80, false, true),
        IncDecCase("increment zero", 0x00, 0x01, false, false),
        IncDecCase("increment 0xFE", 0xFE, 0xFF, false, true),
    )

    val decCases = listOf(
        IncDecCase("basic decrement", 0x10, 0x0F, false, false),
        IncDecCase("decrement to zero", 0x01, 0x00, true, false),
        IncDecCase("decrement zero (wraparound)", 0x00, 0xFF, false, true),
        IncDecCase("decrement negative to positive", 0x80, 0x7F, false, false),
        IncDecCase("decrement 0xFF", 0xFF, 0xFE, false, true),
    )

    context("INX") {
        withData(nameFn = { it.desc }, incCases) { case ->
            val h = CpuTestHarness()
            h.x = case.input
            h.execute(0xE8)
            h.x shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("INY") {
        withData(nameFn = { it.desc }, incCases) { case ->
            val h = CpuTestHarness()
            h.y = case.input
            h.execute(0xC8)
            h.y shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("DEX") {
        withData(nameFn = { it.desc }, decCases) { case ->
            val h = CpuTestHarness()
            h.x = case.input
            h.execute(0xCA)
            h.x shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("DEY") {
        withData(nameFn = { it.desc }, decCases) { case ->
            val h = CpuTestHarness()
            h.y = case.input
            h.execute(0x88)
            h.y shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("INC zero page") {
        withData(nameFn = { it.desc }, incCases) { case ->
            val h = CpuTestHarness()
            h.writeMem(0x42, case.input)
            h.execute(0xE6, 0x42)
            h.readMem(0x42) shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("INC absolute") {
        test("increments memory at absolute address") {
            val h = CpuTestHarness()
            h.writeMem(0x0300, 0x42)
            h.execute(0xEE, 0x00, 0x03)
            h.readMem(0x0300) shouldBe 0x43
        }
    }

    context("DEC zero page") {
        withData(nameFn = { it.desc }, decCases) { case ->
            val h = CpuTestHarness()
            h.writeMem(0x42, case.input)
            h.execute(0xC6, 0x42)
            h.readMem(0x42) shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("DEC absolute") {
        test("decrements memory at absolute address") {
            val h = CpuTestHarness()
            h.writeMem(0x0300, 0x42)
            h.execute(0xCE, 0x00, 0x03)
            h.readMem(0x0300) shouldBe 0x41
        }
    }
})
