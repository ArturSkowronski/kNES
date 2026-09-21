package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import knes.emulator.utils.HiResTimer
import java.io.File

/**
 * The nestest fixture.
 *
 * nestest has two entry points: the normal reset vector, and `$C000` for "automated
 * mode", where it runs its opcode suite with no PPU and writes a result code to `$0002`
 * (official opcodes) and `$0003` (unofficial ones), zero meaning pass.
 *
 * Automated mode only engages if the reset interrupt queued by `loadRom` is drained
 * before the program counter is aimed at `$C000` — otherwise it is serviced first and
 * the CPU goes to the reset vector instead. [NES.jumpTo] does that. Getting it wrong is
 * why this fixture spent a long time asserting a result code that was never written.
 */
private const val RESULT_OFFICIAL = 0x0002
private const val RESULT_UNOFFICIAL = 0x0003
private const val AUTOMATED_ENTRY = 0xC000
private const val AUTOMATED_TARGET = 0xC5F5

private fun host() = object : NesHost {
    override fun sendErrorMsg(message: String) {}
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0x40
    }
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
}

private class Nestest(aimAtAutomatedMode: Boolean = true) {
    val nes = NES(host(), NesConfig.HEADLESS)

    init {
        val rom = File("src/test/resources/nestest.nes")
        check(rom.exists()) { "missing fixture: ${rom.absolutePath}" }
        check(nes.loadRom(rom.absolutePath)) { "nestest.nes failed to load" }
        for (address in 0 until 0x0800) nes.cpuMemory.write(address, 0x00.toShort())
        if (aimAtAutomatedMode) nes.jumpTo(AUTOMATED_ENTRY)
    }

    fun run(instructions: Int = 30_000): Nestest {
        repeat(instructions) { nes.stepInstruction() }
        return this
    }

    fun ramWritten() = (0 until 0x0800).count { (nes.cpuMemory.load(it).toInt() and 0xFF) != 0 }

    fun result(address: Int) = nes.cpuMemory.load(address).toInt() and 0xFF

    fun reaches(pc: IntRange, instructions: Int = 30_000): Boolean {
        repeat(instructions) {
            nes.stepInstruction()
            if (nes.programCounter in pc) return true
        }
        return false
    }
}

class NestestFixtureTest : FunSpec({

    test("automated mode is entered") {
        Nestest().reaches(AUTOMATED_TARGET..AUTOMATED_TARGET + 4, instructions = 16) shouldBe true
    }

    test("the opcode suite actually runs") {
        // Without this, a zero result code cannot be told from a run that never started:
        // the harness zeroes RAM itself.
        Nestest().run().ramWritten() shouldBeGreaterThan 0
    }

    test("all official opcode tests pass") {
        Nestest().run().result(RESULT_OFFICIAL) shouldBe 0x00
    }

    test("all unofficial opcode tests pass") {
        // Read into a local and never asserted, before this fixture was repaired.
        Nestest().run().result(RESULT_UNOFFICIAL) shouldBe 0x00
    }

    test("skipping the interrupt drain misses automated mode entirely") {
        // Assigning the program counter by hand without draining sends the CPU to the
        // reset vector: the ROM runs its normal boot and parks in the vblank wait.
        // This is what the fixture used to do.
        val nestest = Nestest(aimAtAutomatedMode = false)
        nestest.nes.cpu.REG_PC_NEW = AUTOMATED_ENTRY - 1

        nestest.reaches(AUTOMATED_TARGET..AUTOMATED_TARGET + 4) shouldBe false
    }
})
