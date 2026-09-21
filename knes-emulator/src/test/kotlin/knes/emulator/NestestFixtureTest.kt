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
 * **Automated mode does not currently engage here** — see the last test. Until it does,
 * a zero in those bytes says nothing, because the harness zeroes RAM before the run.
 * Every assertion below is therefore about what the ROM demonstrably does, and the
 * result bytes are only checked alongside evidence that the ROM ran at all.
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

private class Nestest(config: NesConfig = NesConfig.HEADLESS) {
    val nes = NES(host(), config)

    init {
        val rom = File("src/test/resources/nestest.nes")
        check(rom.exists()) { "missing fixture: ${rom.absolutePath}" }
        check(nes.loadRom(rom.absolutePath)) { "nestest.nes failed to load" }
        // Zero RAM so the run is deterministic. Note the cost: a result byte of zero now
        // means "passed" or "never written", and only other evidence separates them.
        for (address in 0 until 0x0800) nes.cpuMemory.write(address, 0x00.toShort())
        nes.cpu.REG_PC_NEW = AUTOMATED_ENTRY - 1
        nes.cpu.status = 0x24
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
            if (nes.cpu.REG_PC_NEW in pc) return true
        }
        return false
    }
}

class NestestFixtureTest : FunSpec({

    test("the ROM actually executes") {
        // Without this, every other assertion in this file is vacuous.
        Nestest().run().ramWritten() shouldBeGreaterThan 0
    }

    test("neither result byte reports a failure") {
        val nestest = Nestest().run()

        // Necessary but not sufficient — see the suite comment. Kept so a run that
        // starts writing a failure code is noticed.
        nestest.result(RESULT_OFFICIAL) shouldBe 0x00
        nestest.result(RESULT_UNOFFICIAL) shouldBe 0x00
    }

    test("an unclocked PPU parks the ROM in the vblank wait, so it runs nothing") {
        // $C008 is `LDA $2002 / BPL -5`, the standard wait for vblank. With
        // steppedExecution = false the CPU loop never clocks the PPU, $2002 never signals, and
        // the ROM spins there forever. That is the configuration this fixture used to
        // run under, which is why its result assertion passed while proving nothing.
        val nestest = Nestest(NesConfig(steppedExecution = false, enableSound = false, timeEmulation = false)).run()

        nestest.ramWritten() shouldBe 0
        nestest.result(RESULT_OFFICIAL) shouldBe 0x00
    }

    test("automated mode is not reached — this test should start failing once it is") {
        // $C000 holds `JMP $C5F5` into the opcode suite. Execution never arrives there,
        // so the suite never runs and the result bytes keep their zero-fill. Making it
        // arrive is the real work of backlog task E1; when that lands, this assertion
        // flips and the checks above can become a genuine accuracy claim.
        Nestest().reaches(AUTOMATED_TARGET - 1..AUTOMATED_TARGET + 5) shouldBe false
    }
})
