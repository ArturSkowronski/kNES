/*
 *
 *  * Copyright (C) 2025 Artur Skowroński
 *  * This file is part of kNES, a fork of vNES (GPLv3) rewritten in Kotlin.
 *  *
 *  * vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license.
 *  * This project is a reimplementation and extension of that work.
 *  *
 *  * kNES is licensed under the GNU General Public License v3.0.
 *  * See the LICENSE file for more details.
 *
 */

package knes.emulator.cpu

import knes.emulator.ByteBuffer
import knes.emulator.CpuInfo
import knes.emulator.Memory
import knes.emulator.memory.MemoryAccess
import knes.emulator.papu.PAPUClockFrame
import knes.emulator.ppu.PPUCycles
import knes.emulator.utils.Globals

class CPU // Constructor:
    (private val papuClockFrame: PAPUClockFrame, private val ppucycles: PPUCycles) : Runnable, CPUIIrqRequester {
    var myThread: Thread? = null

    private var mmap: MemoryAccess? = null
    private var mem: ShortArray? = null

    var REG_ACC_NEW: Int = 0
    var REG_X_NEW: Int = 0
    var REG_Y_NEW: Int = 0
    var REG_STATUS_NEW: Int = 0
    var REG_PC_NEW: Int = 0
    var REG_SP: Int = 0

    private var F_CARRY_NEW = 0
    private var F_ZERO_NEW = 0
    private var F_INTERRUPT_NEW = 0
    private var F_DECIMAL_NEW = 0
    private var F_BRK_NEW = 0
    private var F_NOTUSED_NEW = 0
    private var F_OVERFLOW_NEW = 0
    private var F_SIGN_NEW = 0

    // Interrupt notification:
    var irqRequested: Boolean = false
    private var irqType = 0

    // Op/Inst Data:
    private var opdata: IntArray? = null

    // Misc vars:
    var cyclesToHalt: Int = 0
    var stopRunning: Boolean = false
    var crash: Boolean = false


    // Initialize:
    fun init(
        cpuMemoryAccess: Memory
    ) {
        // Get Op data:

        opdata = CpuInfo.opData

        // Get Memory Access:
        this.mem = cpuMemoryAccess.mem
        // Reset crash flag:
        crash = false

        // Set flags:
        F_BRK_NEW = 1
        F_NOTUSED_NEW = 1
        F_INTERRUPT_NEW = 1
        irqRequested = false
    }

    fun stateLoad(buf: ByteBuffer) {
        if (buf.readByte().toInt() == 1) {
            // Version 1

            // Registers:

            this.status = buf.readInt()
            REG_ACC_NEW = buf.readInt()
            REG_PC_NEW = buf.readInt()
            REG_SP = buf.readInt()
            REG_X_NEW = buf.readInt()
            REG_Y_NEW = buf.readInt()

            // Cycles to halt:
            cyclesToHalt = buf.readInt()
        }
    }

    fun stateSave(buf: ByteBuffer) {
        // Save info version:

        buf.putByte(1.toShort())

        // Save registers:
        buf.putInt(this.status)
        buf.putInt(REG_ACC_NEW)
        buf.putInt(REG_PC_NEW)
        buf.putInt(REG_SP)
        buf.putInt(REG_X_NEW)
        buf.putInt(REG_Y_NEW)

        // Cycles to halt:
        buf.putInt(cyclesToHalt)
    }

    fun reset() {
        REG_ACC_NEW = 0
        REG_X_NEW = 0
        REG_Y_NEW = 0

        irqRequested = false
        irqType = 0

        // Reset Stack pointer:
        REG_SP = 0x01FF

        // Reset Program counter:
        REG_PC_NEW = 0x8000 - 1

        // Reset Status register:
        REG_STATUS_NEW = 0x28
        this.status = 0x28

        // Reset crash flag:
        crash = false

        // Set flags:
        F_CARRY_NEW = 0
        F_DECIMAL_NEW = 0
        F_INTERRUPT_NEW = 1
        F_OVERFLOW_NEW = 0
        F_SIGN_NEW = 0
        F_ZERO_NEW = 0

        F_NOTUSED_NEW = 1
        F_BRK_NEW = 1

        cyclesToHalt = 0
    }

    @Synchronized
    fun beginExecution() {
        if (myThread != null && myThread!!.isAlive()) {
            endExecution()
        }

        myThread = Thread(this)
        myThread!!.start()
        myThread!!.setPriority(Thread.MIN_PRIORITY)
    }

    @Synchronized
    fun endExecution() {
        //System.out.println("* Attempting to stop CPU thread.");
        if (myThread != null && myThread!!.isAlive()) {
            try {
                stopRunning = true
                myThread!!.join()
            } catch (ie: InterruptedException) {
                //System.out.println("** Unable to stop CPU thread!");
                ie.printStackTrace()
            }
        } else {
            //System.out.println("* CPU Thread was not alive.");
        }
    }

    val isRunning: Boolean
        get() = (myThread != null && myThread!!.isAlive())

    override fun run() {
        initRun()
        emulate()
    }

    @Synchronized
    fun initRun() {
        stopRunning = false
    }

    // Emulates cpu instructions until stopped.
    fun emulate() {
        // knes.emulator.NES Memory
        // (when memory mappers switch ROM banks
        // this will be written to, no need to
        // update reference):

        // Registers:

        var REG_ACC = REG_ACC_NEW
        var REG_X = REG_X_NEW
        var REG_Y = REG_Y_NEW
        val REG_STATUS = REG_STATUS_NEW
        var REG_PC = REG_PC_NEW

        // Status flags:
        var F_CARRY = F_CARRY_NEW
        var F_ZERO = (if (F_ZERO_NEW == 0) 1 else 0)
        var F_INTERRUPT = F_INTERRUPT_NEW
        var F_DECIMAL = F_DECIMAL_NEW
        var F_NOTUSED = F_NOTUSED_NEW
        var F_BRK = F_BRK_NEW
        var F_OVERFLOW = F_OVERFLOW_NEW
        var F_SIGN = F_SIGN_NEW


        // Misc. variables
        var opinf = 0
        var opaddr = 0
        var addrMode = 0
        var addr = 0
        var palCnt = 0
        var cycleCount: Int
        var cycleAdd: Int
        var temp: Int
        var add: Int

        val palEmu = Globals.palEmulation
        val emulateSound = Globals.enableSound
        val asApplet = Globals.appletMode
        stopRunning = false

        while (true) {
            if (stopRunning) break

            // Check interrupts:
            if (irqRequested) {
                temp =
                    (F_CARRY) or
                            ((if (F_ZERO == 0) 1 else 0) shl 1) or
                            (F_INTERRUPT shl 2) or
                            (F_DECIMAL shl 3) or
                            (F_BRK shl 4) or
                            (F_NOTUSED shl 5) or
                            (F_OVERFLOW shl 6) or
                            (F_SIGN shl 7)

                REG_PC_NEW = REG_PC
                F_INTERRUPT_NEW = F_INTERRUPT
                when (irqType) {
                    0 -> {
                        // Normal IRQ:
                        if (F_INTERRUPT != 0) {
                            System.out.println("Interrupt was masked.");
                            break
                        }
                        doIrq(temp)
                    }

                    1 -> {
                        // NMI:
                        doNonMaskableInterrupt(temp)
                    }

                    2 -> {
                        // Reset:
                        doResetInterrupt()
                    }
                }

                REG_PC = REG_PC_NEW
                F_INTERRUPT = F_INTERRUPT_NEW
                F_BRK = F_BRK_NEW
                irqRequested = false
            }

            opinf = opdata!![mmap!!.load(REG_PC + 1).toInt()]
            cycleCount = (opinf shr 24)
            cycleAdd = 0

            // Find address mode:
            addrMode = (opinf shr 8) and 0xFF

            // Increment PC by number of op bytes:
            opaddr = REG_PC
            REG_PC += ((opinf shr 16) and 0xFF)


            when (addrMode) {
                0 -> {
                    // Zero Page mode. Use the address given after the opcode, but without high byte.
                    addr = load(opaddr + 2)
                }

                1 -> {
                    // Relative mode.
                    addr = load(opaddr + 2)
                    if (addr < 0x80) {
                        addr += REG_PC
                    } else {
                        addr += REG_PC - 256
                    }
                }

                2 -> {}
                3 -> {
                    // Absolute mode. Use the two bytes following the opcode as an address.
                    addr = load16bit(opaddr + 2)
                }

                4 -> {
                    // Accumulator mode. The address is in the accumulator register.
                    addr = REG_ACC
                }

                5 -> {
                    // Immediate mode. The value is given after the opcode.
                    addr = REG_PC
                }

                6 -> {
                    // Zero Page Indexed mode, X as index. Use the address given after the opcode, then add the
                    // X register to it to get the final address.
                    addr = (load(opaddr + 2) + REG_X) and 0xFF
                }

                7 -> {
                    // Zero Page Indexed mode, Y as index. Use the address given after the opcode, then add the
                    // Y register to it to get the final address.
                    addr = (load(opaddr + 2) + REG_Y) and 0xFF
                }

                8 -> {
                    // Absolute Indexed Mode, X as index. Same as zero page indexed, but with the high byte.
                    addr = load16bit(opaddr + 2)
                    if ((addr and 0xFF00) != ((addr + REG_X) and 0xFF00)) {
                        cycleAdd = 1
                    }
                    addr += REG_X
                }

                9 -> {
                    // Absolute Indexed Mode, Y as index. Same as zero page indexed, but with the high byte.
                    addr = load16bit(opaddr + 2)
                    if ((addr and 0xFF00) != ((addr + REG_Y) and 0xFF00)) {
                        cycleAdd = 1
                    }
                    addr += REG_Y
                }

                10 -> {
                    // Pre-indexed Indirect mode. Find the 16-bit address starting at the given location plus
                    // the current X register. The value is the contents of that address.
                    addr = load(opaddr + 2)
                    if ((addr and 0xFF00) != ((addr + REG_X) and 0xFF00)) {
                        cycleAdd = 1
                    }
                    addr += REG_X
                    addr = addr and 0xFF
                    addr = load16bit(addr)
                }

                11 -> {
                    // Post-indexed Indirect mode. Find the 16-bit address contained in the given location
                    // (and the one following). Add to that address the contents of the Y register. Fetch the value
                    // stored at that adress.
                    addr = load16bit(load(opaddr + 2))
                    if ((addr and 0xFF00) != ((addr + REG_Y) and 0xFF00)) {
                        cycleAdd = 1
                    }
                    addr += REG_Y
                }

                12 -> {
                    // Indirect Absolute mode. Find the 16-bit address contained at the given location.
                    addr = load16bit(opaddr + 2) // Find op
                    if (addr < 0x1FFF) {
                        addr =
                            mem!![addr] + (mem!![(addr and 0xFF00) or (((addr and 0xFF) + 1) and 0xFF)].toInt() shl 8) // Read from address given in op
                    } else {
                        addr = mmap!!.load(addr) + (mmap!!.load((addr and 0xFF00) or (((addr and 0xFF) + 1) and 0xFF))
                            .toInt() shl 8)
                    }
                }

            }

            // Wrap around for addresses above 0xFFFF:
            addr = addr and 0xFFFF

            // ----------------------------------------------------------------------------------------------------
            // Decode & execute instruction:
            // ----------------------------------------------------------------------------------------------------

            // This should be compiled to a jump table.
            when (opinf and 0xFF) {
                0 -> {
                    // *******
                    // * ADC *
                    // *******

                    // Add with carry.
                    temp = REG_ACC + load(addr) + F_CARRY
                    F_OVERFLOW =
                        (if (((REG_ACC xor load(addr)) and 0x80) == 0 && (((REG_ACC xor temp) and 0x80)) != 0) 1 else 0)
                    F_CARRY = (if (temp > 255) 1 else 0)
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp and 0xFF
                    REG_ACC = (temp and 255)
                    cycleCount += cycleAdd
                }

                1 -> {
                    // *******
                    // * AND *
                    // *******

                    // AND memory with accumulator.
                    REG_ACC = REG_ACC and load(addr)
                    F_SIGN = (REG_ACC shr 7) and 1
                    F_ZERO = REG_ACC
                    //REG_ACC = temp;
                    if (addrMode != 11) cycleCount += cycleAdd // PostIdxInd = 11
                }

                2 -> {
                    // *******
                    // * ASL *
                    // *******

                    // Shift left one bit
                    if (addrMode == 4) { // ADDR_ACC = 4

                        F_CARRY = (REG_ACC shr 7) and 1
                        REG_ACC = (REG_ACC shl 1) and 255
                        F_SIGN = (REG_ACC shr 7) and 1
                        F_ZERO = REG_ACC
                    } else {
                        temp = load(addr)
                        F_CARRY = (temp shr 7) and 1
                        temp = (temp shl 1) and 255
                        F_SIGN = (temp shr 7) and 1
                        F_ZERO = temp
                        write(addr, temp.toShort())
                    }
                }

                3 -> {
                    // *******
                    // * BCC *
                    // *******

                    // Branch on carry clear
                    if (F_CARRY == 0) {
                        cycleCount += (if ((opaddr and 0xFF00) != (addr and 0xFF00)) 2 else 1)
                        REG_PC = addr
                    }
                }

                4 -> {
                    // *******
                    // * BCS *
                    // *******

                    // Branch on carry set
                    if (F_CARRY == 1) {
                        cycleCount += (if ((opaddr and 0xFF00) != (addr and 0xFF00)) 2 else 1)
                        REG_PC = addr
                    }
                }

                5 -> {
                    // *******
                    // * BEQ *
                    // *******

                    // Branch on zero
                    if (F_ZERO == 0) {
                        cycleCount += (if ((opaddr and 0xFF00) != (addr and 0xFF00)) 2 else 1)
                        REG_PC = addr
                    }
                }

                6 -> {
                    // *******
                    // * BIT *
                    // *******
                    temp = load(addr)
                    F_SIGN = (temp shr 7) and 1
                    F_OVERFLOW = (temp shr 6) and 1
                    temp = temp and REG_ACC
                    F_ZERO = temp
                }

                7 -> {
                    // *******
                    // * BMI *
                    // *******

                    // Branch on negative result
                    if (F_SIGN == 1) {
                        cycleCount++
                        REG_PC = addr
                    }
                }

                8 -> {
                    // *******
                    // * BNE *
                    // *******

                    // Branch on not zero
                    if (F_ZERO != 0) {
                        cycleCount += (if ((opaddr and 0xFF00) != (addr and 0xFF00)) 2 else 1)
                        REG_PC = addr
                    }
                }

                9 -> {
                    // *******
                    // * BPL *
                    // *******

                    // Branch on positive result
                    if (F_SIGN == 0) {
                        cycleCount += (if ((opaddr and 0xFF00) != (addr and 0xFF00)) 2 else 1)
                        REG_PC = addr
                    }
                }

                10 -> {
                    // *******
                    // * BRK *
                    // *******
                    REG_PC += 2
                    push((REG_PC shr 8) and 255)
                    push(REG_PC and 255)
                    F_BRK = 1

                    push(
                        (F_CARRY) or
                                ((if (F_ZERO == 0) 1 else 0) shl 1) or
                                (F_INTERRUPT shl 2) or
                                (F_DECIMAL shl 3) or
                                (F_BRK shl 4) or
                                (F_NOTUSED shl 5) or
                                (F_OVERFLOW shl 6) or
                                (F_SIGN shl 7)
                    )

                    F_INTERRUPT = 1
                    //REG_PC = load(0xFFFE) | (load(0xFFFF) << 8);
                    REG_PC = load16bit(0xFFFE)
                    REG_PC--
                }

                11 -> {
                    // *******
                    // * BVC *
                    // *******

                    // Branch on overflow clear
                    if (F_OVERFLOW == 0) {
                        cycleCount += (if ((opaddr and 0xFF00) != (addr and 0xFF00)) 2 else 1)
                        REG_PC = addr
                    }
                }

                12 -> {
                    // *******
                    // * BVS *
                    // *******

                    // Branch on overflow set
                    if (F_OVERFLOW == 1) {
                        cycleCount += (if ((opaddr and 0xFF00) != (addr and 0xFF00)) 2 else 1)
                        REG_PC = addr
                    }
                }

                13 -> {
                    // *******
                    // * CLC *
                    // *******

                    // Clear carry flag
                    F_CARRY = 0
                }

                14 -> {
                    // *******
                    // * CLD *
                    // *******

                    // Clear decimal flag
                    F_DECIMAL = 0
                }

                15 -> {
                    // *******
                    // * CLI *
                    // *******

                    // Clear interrupt flag
                    F_INTERRUPT = 0
                }

                16 -> {
                    // *******
                    // * CLV *
                    // *******

                    // Clear overflow flag
                    F_OVERFLOW = 0
                }

                17 -> {
                    // *******
                    // * CMP *
                    // *******

                    // Compare memory and accumulator:
                    temp = REG_ACC - load(addr)
                    F_CARRY = (if (temp >= 0) 1 else 0)
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp and 0xFF
                    cycleCount += cycleAdd
                }

                18 -> {
                    // *******
                    // * CPX *
                    // *******

                    // Compare memory and index X:
                    temp = REG_X - load(addr)
                    F_CARRY = (if (temp >= 0) 1 else 0)
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp and 0xFF
                }

                19 -> {
                    // *******
                    // * CPY *
                    // *******

                    // Compare memory and index Y:
                    temp = REG_Y - load(addr)
                    F_CARRY = (if (temp >= 0) 1 else 0)
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp and 0xFF
                }

                20 -> {
                    // *******
                    // * DEC *
                    // *******

                    // Decrement memory by one:
                    temp = (load(addr) - 1) and 0xFF
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp
                    write(addr, temp.toShort())
                }

                21 -> {
                    // *******
                    // * DEX *
                    // *******

                    // Decrement index X by one:
                    REG_X = (REG_X - 1) and 0xFF
                    F_SIGN = (REG_X shr 7) and 1
                    F_ZERO = REG_X
                }

                22 -> {
                    // *******
                    // * DEY *
                    // *******

                    // Decrement index Y by one:
                    REG_Y = (REG_Y - 1) and 0xFF
                    F_SIGN = (REG_Y shr 7) and 1
                    F_ZERO = REG_Y
                }

                23 -> {
                    // *******
                    // * EOR *
                    // *******

                    // XOR Memory with accumulator, store in accumulator:
                    REG_ACC = (load(addr) xor REG_ACC) and 0xFF
                    F_SIGN = (REG_ACC shr 7) and 1
                    F_ZERO = REG_ACC
                    cycleCount += cycleAdd
                }

                24 -> {
                    // *******
                    // * INC *
                    // *******

                    // Increment memory by one:
                    temp = (load(addr) + 1) and 0xFF
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp
                    write(addr, (temp and 0xFF).toShort())
                }

                25 -> {
                    // *******
                    // * INX *
                    // *******

                    // Increment index X by one:
                    REG_X = (REG_X + 1) and 0xFF
                    F_SIGN = (REG_X shr 7) and 1
                    F_ZERO = REG_X
                }

                26 -> {
                    // *******
                    // * INY *
                    // *******

                    // Increment index Y by one:
                    REG_Y++
                    REG_Y = REG_Y and 0xFF
                    F_SIGN = (REG_Y shr 7) and 1
                    F_ZERO = REG_Y
                }

                27 -> {
                    // *******
                    // * JMP *
                    // *******

                    // Jump to new location:
                    REG_PC = addr - 1
                }

                28 -> {
                    // *******
                    // * JSR *
                    // *******

                    // Jump to new location, saving return address.
                    // Push return address on stack:
                    push((REG_PC shr 8) and 255)
                    push(REG_PC and 255)
                    REG_PC = addr - 1
                }

                29 -> {
                    // *******
                    // * LDA *
                    // *******

                    // Load accumulator with memory:
                    REG_ACC = load(addr)
                    F_SIGN = (REG_ACC shr 7) and 1
                    F_ZERO = REG_ACC
                    cycleCount += cycleAdd
                }

                30 -> {
                    // *******
                    // * LDX *
                    // *******

                    // Load index X with memory:
                    REG_X = load(addr)
                    F_SIGN = (REG_X shr 7) and 1
                    F_ZERO = REG_X
                    cycleCount += cycleAdd
                }

                31 -> {
                    // *******
                    // * LDY *
                    // *******

                    // Load index Y with memory:
                    REG_Y = load(addr)
                    F_SIGN = (REG_Y shr 7) and 1
                    F_ZERO = REG_Y
                    cycleCount += cycleAdd
                }

                32 -> {
                    // *******
                    // * LSR *
                    // *******

                    // Shift right one bit:
                    if (addrMode == 4) { // ADDR_ACC

                        temp = (REG_ACC and 0xFF)
                        F_CARRY = temp and 1
                        temp = temp shr 1
                        REG_ACC = temp
                    } else {
                        temp = load(addr) and 0xFF
                        F_CARRY = temp and 1
                        temp = temp shr 1
                        write(addr, temp.toShort())
                    }
                    F_SIGN = 0
                    F_ZERO = temp
                }

                33 -> {}
                34 -> {
                    // *******
                    // * ORA *
                    // *******

                    // OR memory with accumulator, store in accumulator.
                    temp = (load(addr) or REG_ACC) and 255
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp
                    REG_ACC = temp
                    if (addrMode != 11) cycleCount += cycleAdd // PostIdxInd = 11
                }

                35 -> {
                    // *******
                    // * PHA *
                    // *******

                    // Push accumulator on stack
                    push(REG_ACC)
                }

                36 -> {
                    // *******
                    // * PHP *
                    // *******

                    // Push processor status on stack
                    F_BRK = 1
                    push(
                        (F_CARRY) or
                                ((if (F_ZERO == 0) 1 else 0) shl 1) or
                                (F_INTERRUPT shl 2) or
                                (F_DECIMAL shl 3) or
                                (F_BRK shl 4) or
                                (F_NOTUSED shl 5) or
                                (F_OVERFLOW shl 6) or
                                (F_SIGN shl 7)
                    )
                }

                37 -> {
                    // *******
                    // * PLA *
                    // *******

                    // Pull accumulator from stack
                    REG_ACC = pull().toInt()
                    F_SIGN = (REG_ACC shr 7) and 1
                    F_ZERO = REG_ACC
                }

                38 -> {
                    // *******
                    // * PLP *
                    // *******

                    // Pull processor status from stack
                    temp = pull().toInt()
                    F_CARRY = (temp) and 1
                    F_ZERO = if (((temp shr 1) and 1) == 1) 0 else 1
                    F_INTERRUPT = (temp shr 2) and 1
                    F_DECIMAL = (temp shr 3) and 1
                    F_BRK = (temp shr 4) and 1
                    F_NOTUSED = (temp shr 5) and 1
                    F_OVERFLOW = (temp shr 6) and 1
                    F_SIGN = (temp shr 7) and 1

                    F_NOTUSED = 1
                }

                39 -> {
                    // *******
                    // * ROL *
                    // *******

                    // Rotate one bit left
                    if (addrMode == 4) { // ADDR_ACC = 4

                        temp = REG_ACC
                        add = F_CARRY
                        F_CARRY = (temp shr 7) and 1
                        temp = ((temp shl 1) and 0xFF) + add
                        REG_ACC = temp
                    } else {
                        temp = load(addr)
                        add = F_CARRY
                        F_CARRY = (temp shr 7) and 1
                        temp = ((temp shl 1) and 0xFF) + add
                        write(addr, temp.toShort())
                    }
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp
                }

                40 -> {
                    // *******
                    // * ROR *
                    // *******

                    // Rotate one bit right
                    if (addrMode == 4) { // ADDR_ACC = 4

                        add = F_CARRY shl 7
                        F_CARRY = REG_ACC and 1
                        temp = (REG_ACC shr 1) + add
                        REG_ACC = temp
                    } else {
                        temp = load(addr)
                        add = F_CARRY shl 7
                        F_CARRY = temp and 1
                        temp = (temp shr 1) + add
                        write(addr, temp.toShort())
                    }
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp
                }

                41 -> {
                    // *******
                    // * RTI *
                    // *******

                    // Return from interrupt. Pull status and PC from stack.
                    temp = pull().toInt()
                    F_CARRY = (temp) and 1
                    F_ZERO = if (((temp shr 1) and 1) == 0) 1 else 0
                    F_INTERRUPT = (temp shr 2) and 1
                    F_DECIMAL = (temp shr 3) and 1
                    F_BRK = (temp shr 4) and 1
                    F_NOTUSED = (temp shr 5) and 1
                    F_OVERFLOW = (temp shr 6) and 1
                    F_SIGN = (temp shr 7) and 1

                    REG_PC = pull().toInt()
                    REG_PC += (pull().toInt() shl 8)
                    if (REG_PC == 0xFFFF) {
                        return
                    }
                    REG_PC--
                    F_NOTUSED = 1
                }

                42 -> {
                    // *******
                    // * RTS *
                    // *******

                    // Return from subroutine. Pull PC from stack.
                    REG_PC = pull().toInt()
                    REG_PC += (pull().toInt() shl 8)

                    if (REG_PC == 0xFFFF) {
                        return
                    }
                }

                43 -> {
                    // *******
                    // * SBC *
                    // *******
                    temp = REG_ACC - load(addr) - (1 - F_CARRY)
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp and 0xFF
                    F_OVERFLOW =
                        (if (((REG_ACC xor temp) and 0x80) != 0 && ((REG_ACC xor load(addr)) and 0x80) != 0) 1 else 0)
                    F_CARRY = (if (temp < 0) 0 else 1)
                    REG_ACC = (temp and 0xFF)
                    if (addrMode != 11) cycleCount += cycleAdd // PostIdxInd = 11
                }

                44 -> {
                    // *******
                    // * SEC *
                    // *******

                    // Set carry flag
                    F_CARRY = 1
                }

                45 -> {
                    // *******
                    // * SED *
                    // *******

                    // Set decimal mode
                    F_DECIMAL = 1
                }

                46 -> {
                    // *******
                    // * SEI *
                    // *******

                    // Set interrupt disable status
                    F_INTERRUPT = 1
                }

                47 -> {
                    // *******
                    // * STA *
                    // *******

                    // Store accumulator in memory
                    write(addr, REG_ACC.toShort())
                }

                48 -> {
                    // *******
                    // * STX *
                    // *******

                    // Store index X in memory
                    write(addr, REG_X.toShort())
                }

                49 -> {
                    // *******
                    // * STY *
                    // *******

                    // Store index Y in memory:
                    write(addr, REG_Y.toShort())
                }

                50 -> {
                    // *******
                    // * TAX *
                    // *******

                    // Transfer accumulator to index X:
                    REG_X = REG_ACC
                    F_SIGN = (REG_ACC shr 7) and 1
                    F_ZERO = REG_ACC
                }

                51 -> {
                    // *******
                    // * TAY *
                    // *******

                    // Transfer accumulator to index Y:
                    REG_Y = REG_ACC
                    F_SIGN = (REG_ACC shr 7) and 1
                    F_ZERO = REG_ACC
                }

                52 -> {
                    // *******
                    // * TSX *
                    // *******

                    // Transfer stack pointer to index X:
                    REG_X = (REG_SP - 0x0100)
                    F_SIGN = (REG_SP shr 7) and 1
                    F_ZERO = REG_X
                }

                53 -> {
                    // *******
                    // * TXA *
                    // *******

                    // Transfer index X to accumulator:
                    REG_ACC = REG_X
                    F_SIGN = (REG_X shr 7) and 1
                    F_ZERO = REG_X
                }

                54 -> {
                    // *******
                    // * TXS *
                    // *******

                    // Transfer index X to stack pointer:
                    REG_SP = (REG_X + 0x0100)
                    stackWrap()
                }

                55 -> {
                    // *******
                    // * TYA *
                    // *******

                    // Transfer index Y to accumulator:
                    REG_ACC = REG_Y
                    F_SIGN = (REG_Y shr 7) and 1
                    F_ZERO = REG_Y
                }

                else -> {
                    // *******
                    // * ??? *
                    // *******

                    // Illegal opcode!
                    if (!crash) {
                        crash = true
                        stopRunning = true
                        println("Game crashed, invalid opcode at address $" + knes.emulator.utils.Misc.hex16(opaddr))
                    }
                }

            } // end of switch

            // ----------------------------------------------------------------------------------------------------
            if (palEmu) {
                palCnt++
                if (palCnt == 5) {
                    palCnt = 0
                    cycleCount++
                }
            }

            if (asApplet) {
                ppucycles.setCycles(cycleCount * 3)
                ppucycles.emulateCycles()
            }

            if (emulateSound) {
                papuClockFrame.clockFrameCounter(cycleCount)
            }
        } // End of run loop.


        // Save registers:
        REG_ACC_NEW = REG_ACC
        REG_X_NEW = REG_X
        REG_Y_NEW = REG_Y
        REG_STATUS_NEW = REG_STATUS
        REG_PC_NEW = REG_PC

        // Save Status flags:
        F_CARRY_NEW = F_CARRY
        F_ZERO_NEW = (if (F_ZERO == 0) 1 else 0)
        F_INTERRUPT_NEW = F_INTERRUPT
        F_DECIMAL_NEW = F_DECIMAL
        F_BRK_NEW = F_BRK
        F_NOTUSED_NEW = F_NOTUSED
        F_OVERFLOW_NEW = F_OVERFLOW
        F_SIGN_NEW = F_SIGN
    }

    private fun load(addr: Int): Int {
        return (if (addr < 0x2000) mem!![addr and 0x7FF] else mmap!!.load(addr)).toInt()
    }

    private fun load16bit(addr: Int): Int {
        return if (addr < 0x1FFF)
            mem!![addr and 0x7FF].toInt() or (mem!![(addr + 1) and 0x7FF].toInt() shl 8)
        else
            mmap!!.load(addr).toInt() or (mmap!!.load(addr + 1).toInt() shl 8)
    }

    private fun write(addr: Int, `val`: Short) {
        if (addr < 0x2000) {
            mem!![addr and 0x7FF] = `val`
        } else {
            mmap!!.write(addr, `val`)
        }
    }

    override fun requestIrq(type: Int) {
        if (irqRequested) {
            if (type == IRQ_NORMAL) {
                return
            }
            System.out.println("too fast irqs. type=" + type);
        }
        irqRequested = true
        irqType = type
    }

    fun push(value: Int) {
        mmap!!.write(REG_SP, value.toShort())
        REG_SP--
        REG_SP = 0x0100 or (REG_SP and 0xFF)
    }

    fun stackWrap() {
        REG_SP = 0x0100 or (REG_SP and 0xFF)
    }

    fun pull(): Short {
        REG_SP++
        REG_SP = 0x0100 or (REG_SP and 0xFF)
        return mmap!!.load(REG_SP)
    }

    fun pageCrossed(addr1: Int, addr2: Int): Boolean {
        return ((addr1 and 0xFF00) != (addr2 and 0xFF00))
    }

    override fun haltCycles(cycles: Int) {
        cyclesToHalt += cycles
    }

    private fun doNonMaskableInterrupt(status: Int) {
        val temp = mmap!!.load(0x2000).toInt() // Read PPU status.
        if ((temp and 128) != 0) { // Check whether VBlank Interrupts are enabled

            REG_PC_NEW++
            push((REG_PC_NEW shr 8) and 0xFF)
            push(REG_PC_NEW and 0xFF)
            //F_INTERRUPT_NEW = 1;
            push(status)

            REG_PC_NEW = mmap!!.load(0xFFFA).toInt() or (mmap!!.load(0xFFFB).toInt() shl 8)
            REG_PC_NEW--
        }
    }

    private fun doResetInterrupt() {
        REG_PC_NEW = mmap!!.load(0xFFFC).toInt() or (mmap!!.load(0xFFFD).toInt() shl 8)
        REG_PC_NEW--
    }

    private fun doIrq(status: Int) {
        REG_PC_NEW++
        push((REG_PC_NEW shr 8) and 0xFF)
        push(REG_PC_NEW and 0xFF)
        push(status)
        F_INTERRUPT_NEW = 1
        F_BRK_NEW = 0

        REG_PC_NEW = mmap!!.load(0xFFFE).toInt() or (mmap!!.load(0xFFFF).toInt() shl 8)
        REG_PC_NEW--
    }

    private var status: Int
        get() = (F_CARRY_NEW) or (F_ZERO_NEW shl 1) or (F_INTERRUPT_NEW shl 2) or (F_DECIMAL_NEW shl 3) or (F_BRK_NEW shl 4) or (F_NOTUSED_NEW shl 5) or (F_OVERFLOW_NEW shl 6) or (F_SIGN_NEW shl 7)
        private set(st) {
            F_CARRY_NEW = (st) and 1
            F_ZERO_NEW = (st shr 1) and 1
            F_INTERRUPT_NEW = (st shr 2) and 1
            F_DECIMAL_NEW = (st shr 3) and 1
            F_BRK_NEW = (st shr 4) and 1
            F_NOTUSED_NEW = (st shr 5) and 1
            F_OVERFLOW_NEW = (st shr 6) and 1
            F_SIGN_NEW = (st shr 7) and 1
        }

    fun setCrashed(value: Boolean) {
        this.crash = value
    }

    /**
     * Sets the memory access component for the CPU.
     *
     * @param memoryAccess the memory access component to use
     */
    fun setMapper(memoryAccess: knes.emulator.memory.MemoryAccess?) {
        mmap = memoryAccess
    }

    fun destroy() {
        mmap = null
    }

    companion object {
        // IRQ Types:
        const val IRQ_NORMAL: Int = 0
        const val IRQ_NMI: Int = 1
        const val IRQ_RESET: Int = 2
    }
}