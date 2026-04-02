package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class LoadCase(val desc: String, val value: Int, val zero: Boolean, val negative: Boolean)

class LoadStoreTest : FunSpec({
    val loadCases = listOf(
        LoadCase("positive value", 0x42, false, false),
        LoadCase("zero", 0x00, true, false),
        LoadCase("negative value", 0x80, false, true),
        LoadCase("max positive", 0x7F, false, false),
        LoadCase("max value", 0xFF, false, true),
    )

    context("LDA immediate") {
        withData(nameFn = { it.desc }, loadCases) { case ->
            val h = CpuTestHarness()
            h.execute(0xA9, case.value)
            h.a shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }
    context("LDA zero page") { test("loads from zero page") { val h = CpuTestHarness(); h.writeMem(0x42, 0xAB); h.execute(0xA5, 0x42); h.a shouldBe 0xAB } }
    context("LDA zero page,X") { test("loads from (zp + X)") { val h = CpuTestHarness(); h.x = 0x05; h.writeMem(0x47, 0xAB); h.execute(0xB5, 0x42); h.a shouldBe 0xAB } }
    context("LDA absolute") { test("loads from absolute address") { val h = CpuTestHarness(); h.writeMem(0x0300, 0xAB); h.execute(0xAD, 0x00, 0x03); h.a shouldBe 0xAB } }
    context("LDA absolute,X") { test("loads from (abs + X)") { val h = CpuTestHarness(); h.x = 0x05; h.writeMem(0x0305, 0xAB); h.execute(0xBD, 0x00, 0x03); h.a shouldBe 0xAB } }
    context("LDA absolute,Y") { test("loads from (abs + Y)") { val h = CpuTestHarness(); h.y = 0x05; h.writeMem(0x0305, 0xAB); h.execute(0xB9, 0x00, 0x03); h.a shouldBe 0xAB } }
    context("LDA (indirect,X)") { test("loads from address at (zp+X)") { val h = CpuTestHarness(); h.x = 0x02; h.writeMem(0x44, 0x00); h.writeMem(0x45, 0x03); h.writeMem(0x0300, 0xAB); h.execute(0xA1, 0x42); h.a shouldBe 0xAB } }
    context("LDA (indirect),Y") { test("loads from (address at zp) + Y") { val h = CpuTestHarness(); h.y = 0x05; h.writeMem(0x42, 0x00); h.writeMem(0x43, 0x03); h.writeMem(0x0305, 0xAB); h.execute(0xB1, 0x42); h.a shouldBe 0xAB } }

    context("LDX immediate") { withData(nameFn = { it.desc }, loadCases) { case -> val h = CpuTestHarness(); h.execute(0xA2, case.value); h.x shouldBe case.value; h.zero shouldBe case.zero; h.negative shouldBe case.negative } }
    context("LDX zero page") { test("loads from zero page") { val h = CpuTestHarness(); h.writeMem(0x42, 0xAB); h.execute(0xA6, 0x42); h.x shouldBe 0xAB } }
    context("LDX zero page,Y") { test("loads from (zp + Y)") { val h = CpuTestHarness(); h.y = 0x05; h.writeMem(0x47, 0xAB); h.execute(0xB6, 0x42); h.x shouldBe 0xAB } }

    context("LDY immediate") { withData(nameFn = { it.desc }, loadCases) { case -> val h = CpuTestHarness(); h.execute(0xA0, case.value); h.y shouldBe case.value; h.zero shouldBe case.zero; h.negative shouldBe case.negative } }
    context("LDY zero page") { test("loads from zero page") { val h = CpuTestHarness(); h.writeMem(0x42, 0xAB); h.execute(0xA4, 0x42); h.y shouldBe 0xAB } }
    context("LDY zero page,X") { test("loads from (zp + X)") { val h = CpuTestHarness(); h.x = 0x05; h.writeMem(0x47, 0xAB); h.execute(0xB4, 0x42); h.y shouldBe 0xAB } }

    context("STA zero page") { test("stores A") { val h = CpuTestHarness(); h.a = 0xAB; h.execute(0x85, 0x42); h.readMem(0x42) shouldBe 0xAB } }
    context("STA absolute") { test("stores A") { val h = CpuTestHarness(); h.a = 0xAB; h.execute(0x8D, 0x00, 0x03); h.readMem(0x0300) shouldBe 0xAB } }
    context("STA zero page,X") { test("stores to (zp + X)") { val h = CpuTestHarness(); h.a = 0xAB; h.x = 0x05; h.execute(0x95, 0x42); h.readMem(0x47) shouldBe 0xAB } }
    context("STX zero page") { test("stores X") { val h = CpuTestHarness(); h.x = 0xAB; h.execute(0x86, 0x42); h.readMem(0x42) shouldBe 0xAB } }
    context("STX absolute") { test("stores X") { val h = CpuTestHarness(); h.x = 0xAB; h.execute(0x8E, 0x00, 0x03); h.readMem(0x0300) shouldBe 0xAB } }
    context("STY zero page") { test("stores Y") { val h = CpuTestHarness(); h.y = 0xAB; h.execute(0x84, 0x42); h.readMem(0x42) shouldBe 0xAB } }
    context("STY absolute") { test("stores Y") { val h = CpuTestHarness(); h.y = 0xAB; h.execute(0x8C, 0x00, 0x03); h.readMem(0x0300) shouldBe 0xAB } }
})
