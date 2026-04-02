package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class TransferCase(val desc: String, val value: Int, val zero: Boolean, val negative: Boolean)

class TransferTest : FunSpec({
    val transferCases = listOf(
        TransferCase("positive value", 0x42, false, false),
        TransferCase("zero", 0x00, true, false),
        TransferCase("negative value", 0x80, false, true),
        TransferCase("max value", 0xFF, false, true),
    )

    context("TAX") { withData(nameFn = { it.desc }, transferCases) { case -> val h = CpuTestHarness(); h.a = case.value; h.execute(0xAA); h.x shouldBe case.value; h.zero shouldBe case.zero; h.negative shouldBe case.negative } }
    context("TAY") { withData(nameFn = { it.desc }, transferCases) { case -> val h = CpuTestHarness(); h.a = case.value; h.execute(0xA8); h.y shouldBe case.value; h.zero shouldBe case.zero; h.negative shouldBe case.negative } }
    context("TXA") { withData(nameFn = { it.desc }, transferCases) { case -> val h = CpuTestHarness(); h.x = case.value; h.execute(0x8A); h.a shouldBe case.value; h.zero shouldBe case.zero; h.negative shouldBe case.negative } }
    context("TYA") { withData(nameFn = { it.desc }, transferCases) { case -> val h = CpuTestHarness(); h.y = case.value; h.execute(0x98); h.a shouldBe case.value; h.zero shouldBe case.zero; h.negative shouldBe case.negative } }

    context("TSX") {
        test("transfers low byte of SP to X") {
            val h = CpuTestHarness()
            h.execute(0xBA)
            h.x shouldBe 0xFF
            h.negative shouldBe true
        }
        test("transfers SP after push") {
            val h = CpuTestHarness()
            h.a = 0x00
            h.executeN(2, 0x48, 0xBA)
            h.x shouldBe 0xFE
        }
    }

    context("TXS") {
        test("transfers X to SP without affecting flags") {
            val h = CpuTestHarness()
            h.x = 0x80
            h.setZero(false)
            h.setNegative(false)
            h.execute(0x9A)
            h.sp shouldBe 0x0180
            h.zero shouldBe false
            h.negative shouldBe false
        }
    }
})
