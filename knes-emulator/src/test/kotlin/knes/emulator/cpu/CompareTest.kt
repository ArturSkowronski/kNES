package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class CmpCase(
    val desc: String,
    val reg: Int, val value: Int,
    val carry: Boolean, val zero: Boolean, val negative: Boolean
)

class CompareTest : FunSpec({

    val cmpCases = listOf(
        CmpCase("equal values", 0x42, 0x42, true, true, false),
        CmpCase("reg > value", 0x50, 0x30, true, false, false),
        CmpCase("reg < value", 0x30, 0x50, false, false, true),
        CmpCase("reg=0, value=0", 0x00, 0x00, true, true, false),
        CmpCase("reg=0xFF, value=0xFF", 0xFF, 0xFF, true, true, false),
        CmpCase("reg=0x80, value=0x7F", 0x80, 0x7F, true, false, false),
        CmpCase("reg=0x00, value=0x01", 0x00, 0x01, false, false, true),
        CmpCase("reg=0x01, value=0x00", 0x01, 0x00, true, false, false),
    )

    context("CMP immediate") {
        withData(nameFn = { it.desc }, cmpCases) { case ->
            val h = CpuTestHarness()
            h.a = case.reg
            h.execute(0xC9, case.value)
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
            h.a shouldBe case.reg
        }
    }

    context("CMP zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0x42
            h.writeMem(0x10, 0x42)
            h.execute(0xC5, 0x10)
            h.carry shouldBe true
            h.zero shouldBe true
        }
    }

    context("CMP absolute") {
        test("reads from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x50
            h.writeMem(0x0300, 0x30)
            h.execute(0xCD, 0x00, 0x03)
            h.carry shouldBe true
            h.zero shouldBe false
        }
    }

    context("CPX immediate") {
        withData(nameFn = { it.desc }, cmpCases) { case ->
            val h = CpuTestHarness()
            h.x = case.reg
            h.execute(0xE0, case.value)
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
            h.x shouldBe case.reg
        }
    }

    context("CPX zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.x = 0x42
            h.writeMem(0x10, 0x42)
            h.execute(0xE4, 0x10)
            h.zero shouldBe true
        }
    }

    context("CPY immediate") {
        withData(nameFn = { it.desc }, cmpCases) { case ->
            val h = CpuTestHarness()
            h.y = case.reg
            h.execute(0xC0, case.value)
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
            h.y shouldBe case.reg
        }
    }

    context("CPY zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.y = 0x42
            h.writeMem(0x10, 0x42)
            h.execute(0xC4, 0x10)
            h.zero shouldBe true
        }
    }
})
