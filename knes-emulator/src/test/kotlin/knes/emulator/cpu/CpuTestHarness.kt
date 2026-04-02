package knes.emulator.cpu

import knes.emulator.Memory
import knes.emulator.papu.PAPUClockFrame
import knes.emulator.ppu.PPUCycles
import knes.emulator.utils.Globals

class CpuTestHarness {
    val memory = Memory(0x10000)
    val cpu: CPU

    private val programBase = 0x8000

    init {
        Globals.appletMode = false
        Globals.enableSound = false
        Globals.palEmulation = false

        val noopPapu = object : PAPUClockFrame {
            override fun clockFrameCounter(cycleCount: Int) {}
        }
        val noopPpu = object : PPUCycles {
            override fun setCycles(cycles: Int) {}
            override fun emulateCycles() {}
        }

        cpu = CPU(noopPapu, noopPpu)
        cpu.init(memory)
        cpu.setMapper(TestMemoryAccess(memory))
        cpu.reset()
        cpu.REG_PC_NEW = programBase - 1
    }

    fun execute(vararg bytes: Int) {
        for (i in bytes.indices) {
            memory.write(programBase + i, bytes[i].toShort())
        }
        cpu.REG_PC_NEW = programBase - 1
        cpu.step()
    }

    fun executeN(n: Int, vararg bytes: Int) {
        for (i in bytes.indices) {
            memory.write(programBase + i, bytes[i].toShort())
        }
        cpu.REG_PC_NEW = programBase - 1
        repeat(n) { cpu.step() }
    }

    fun writeMem(address: Int, value: Int) {
        memory.write(address, value.toShort())
    }

    fun readMem(address: Int): Int = memory.load(address).toInt() and 0xFF

    var a: Int
        get() = cpu.REG_ACC_NEW
        set(v) { cpu.REG_ACC_NEW = v }

    var x: Int
        get() = cpu.REG_X_NEW
        set(v) { cpu.REG_X_NEW = v }

    var y: Int
        get() = cpu.REG_Y_NEW
        set(v) { cpu.REG_Y_NEW = v }

    var sp: Int
        get() = cpu.REG_SP
        set(v) { cpu.REG_SP = v }

    var pc: Int
        get() = cpu.REG_PC_NEW
        set(v) { cpu.REG_PC_NEW = v }

    val carry: Boolean get() = (cpu.status and 0x01) != 0
    val zero: Boolean get() = (cpu.status and 0x02) != 0
    val interruptDisable: Boolean get() = (cpu.status and 0x04) != 0
    val decimal: Boolean get() = (cpu.status and 0x08) != 0
    val overflow: Boolean get() = (cpu.status and 0x40) != 0
    val negative: Boolean get() = (cpu.status and 0x80) != 0

    fun setCarry(v: Boolean) { cpu.status = if (v) cpu.status or 0x01 else cpu.status and 0x01.inv() }
    fun setZero(v: Boolean) { cpu.status = if (v) cpu.status or 0x02 else cpu.status and 0x02.inv() }
    fun setOverflow(v: Boolean) { cpu.status = if (v) cpu.status or 0x40 else cpu.status and 0x40.inv() }
    fun setNegative(v: Boolean) { cpu.status = if (v) cpu.status or 0x80 else cpu.status and 0x80.inv() }
    fun setInterruptDisable(v: Boolean) { cpu.status = if (v) cpu.status or 0x04 else cpu.status and 0x04.inv() }
    fun setDecimal(v: Boolean) { cpu.status = if (v) cpu.status or 0x08 else cpu.status and 0x08.inv() }
}
