package knes.emulator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import knes.emulator.input.InputHandler
import knes.emulator.utils.HiResTimer
import java.io.File

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

private fun loadedNes(): NES {
    val nes = NES(host(), NesConfig.HEADLESS)
    check(nes.loadRom(File("src/test/resources/nestest.nes").absolutePath))
    nes.jumpTo(0xC000)
    return nes
}

class InstructionTraceTest : FunSpec({

    test("a ring keeps the most recent instructions and forgets the rest") {
        val trace = InstructionTrace(capacity = 3)

        repeat(5) { trace.record(pc = 0x8000 + it, opcode = it, cycles = 2) }

        trace.size shouldBe 3
        trace.executed shouldBe 5L
        trace.entries().map { it.pc } shouldBe listOf(0x8002, 0x8003, 0x8004)
    }

    test("a ring that has not wrapped reports what it has, oldest first") {
        val trace = InstructionTrace(capacity = 8)
        repeat(3) { trace.record(0x8000 + it, it, 2) }

        trace.entries().map { it.pc } shouldBe listOf(0x8000, 0x8001, 0x8002)
        trace.tail(2).map { it.pc } shouldBe listOf(0x8001, 0x8002)
    }

    test("clearing forgets the count as well as the entries") {
        val trace = InstructionTrace(capacity = 4)
        repeat(9) { trace.record(0x8000, 0xEA, 2) }

        trace.clear()

        trace.size shouldBe 0
        trace.executed shouldBe 0L
        trace.entries() shouldBe emptyList()
    }

    test("a capacity of zero is rejected rather than silently recording nothing") {
        shouldThrow<IllegalArgumentException> { InstructionTrace(capacity = 0) }
    }

    test("tracing is off by default and costs nothing") {
        val nes = loadedNes()
        nes.trace shouldBe null

        nes.stepInstruction()

        nes.trace shouldBe null
    }

    test("a trace records the instructions the CPU really fetched") {
        val nes = loadedNes()
        nes.trace = InstructionTrace(16)

        nes.stepInstruction()

        // $C000 holds JMP $C5F5: three cycles, and the next fetch is the target.
        val first = nes.trace!!.entries().single()
        first.pc shouldBe 0xC000
        first.opcode shouldBe 0x4C
        first.cycles shouldBe 3
        nes.programCounter shouldBe 0xC5F5
    }

    test("opcodes are read through the mapper, the way the CPU reads them") {
        // Above $2000 the mapper's view and raw CPU memory are different things, and a
        // debugger reading the wrong one describes instructions that never ran.
        val nes = loadedNes()

        nes.opcodeAt(0xC000) shouldBe 0x4C
    }

    test("runUntilStop stops at a breakpoint and says where") {
        val nes = loadedNes()
        nes.breakpoints += 0xC5F5

        nes.runUntilStop(maxInstructions = 100) shouldBe DebugStop.Breakpoint(0xC5F5)
        nes.programCounter shouldBe 0xC5F5
    }

    test("runUntilStop gives up rather than running forever") {
        val nes = loadedNes()
        nes.breakpoints += 0x0001 // never executed

        nes.runUntilStop(maxInstructions = 200) shouldBe null
    }

    test("nothing set means the budget simply runs out") {
        val nes = loadedNes()

        nes.runUntilStop(maxInstructions = 50) shouldBe null
        nes.programCounter shouldNotBe 0xC000
    }

    test("a watchpoint reports the address, both values and where execution had got to") {
        // nestest's opcode suite writes its scratch state into zero page almost
        // immediately, so something here changes within a few instructions.
        val nes = loadedNes()
        nes.watch(0x0000)

        val stop = nes.runUntilStop(maxInstructions = 5_000)

        (stop is DebugStop.ValueChanged) shouldBe true
        val changed = stop as DebugStop.ValueChanged
        changed.address shouldBe 0x0000
        (changed.from != changed.to) shouldBe true
        changed.to shouldBe nes.readByte(0x0000)
    }

    test("an unwatched address stops nothing") {
        val nes = loadedNes()
        nes.watch(0x0000)
        nes.unwatch(0x0000)

        nes.runUntilStop(maxInstructions = 5_000) shouldBe null
    }

    test("only RAM can be watched, because reading a register has side effects") {
        val nes = loadedNes()

        // $2002 is the PPU status register: reading it clears the vblank flag, so
        // sampling it every instruction would change the run being observed.
        val error = shouldThrow<IllegalArgumentException> { nes.watch(0x2002) }
        error.message!!.contains("side effects") shouldBe true

        nes.watchpoints shouldBe emptySet()
    }

    test("readByte sees RAM without going through the mapper") {
        val nes = loadedNes()
        nes.cpuMemory.write(0x0042, 0x7B.toShort())

        nes.readByte(0x0042) shouldBe 0x7B
        // Mirrored every 0x800 through the whole RAM window.
        nes.readByte(0x0842) shouldBe 0x7B
    }
})
