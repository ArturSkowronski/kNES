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

package knes.emulator.mappers

import knes.emulator.Memory
import knes.emulator.NES
import knes.emulator.cpu.CPU
import knes.emulator.input.InputHandler
import knes.emulator.papu.PAPU
import knes.emulator.ppu.PPU
import knes.emulator.rom.ROMData
import kotlin.math.max
import kotlin.math.min

class MapperDefault(nes: NES) : MemoryMapper {
    var cpuMem: Memory
    var ppuMem: Memory
    var cpuMemArray: ShortArray?
    var rom: ROMData? = null
    var cpu: CPU?
    var ppu: PPU?
    var papu: PAPU
    var cpuMemSize: Int
    var joy1StrobeState: Int = 0
    var joy2StrobeState: Int = 0
    var joypadLastWrite: Int
    var mousePressed: Boolean = false

    var mouseX: Int = 0
    var mouseY: Int = 0
    var tmp: Int = 0
    private val inputHandler: InputHandler
    private val inputHandler2: InputHandler?

    init {
        this.cpuMem = nes.cpuMemory
        this.cpuMemArray = cpuMem.mem
        this.ppuMem = nes.ppuMemory
        this.cpu = nes.cpu
        this.ppu = nes.ppu
        this.papu = nes.papu
        this.inputHandler = nes.gui!!.getJoy1()
        this.inputHandler2 = nes.gui!!.getJoy2()

        cpuMemSize = cpuMem.memSize
        joypadLastWrite = -1
    }

    override fun stateLoad(buf: knes.emulator.ByteBuffer?) {
        // Check version:

        if (buf!!.readByte().toInt() == 1) {
            // Joypad stuff:

            joy1StrobeState = buf.readInt()
            joy2StrobeState = buf.readInt()
            joypadLastWrite = buf.readInt()

            // Mapper specific stuff:
            mapperInternalStateLoad(buf)
        }
    }

    override fun stateSave(buf: knes.emulator.ByteBuffer?) {
        // Version:

        buf!!.putByte(1.toShort())

        // Joypad stuff:
        buf.putInt(joy1StrobeState)
        buf.putInt(joy2StrobeState)
        buf.putInt(joypadLastWrite)

        // Mapper specific stuff:
        mapperInternalStateSave(buf)
    }

    fun mapperInternalStateLoad(buf: knes.emulator.ByteBuffer) {
        buf.putByte(joy1StrobeState.toShort())
        buf.putByte(joy2StrobeState.toShort())
        buf.putByte(joypadLastWrite.toShort())
    }

    fun mapperInternalStateSave(buf: knes.emulator.ByteBuffer) {
        joy1StrobeState = buf.readByte().toInt()
        joy2StrobeState = buf.readByte().toInt()
        joypadLastWrite = buf.readByte().toInt()
    }

    override fun write(address: Int, value: Short) {
        if (address < 0x2000) {
            // Mirroring of RAM:

            cpuMem!!.mem[address and 0x7FF] = value
        } else if (address > 0x4017) {
            cpuMem!!.mem[address] = value
            if (address >= 0x6000 && address < 0x8000) {
                // Write to SaveRAM. Store in file:
//                if (rom != null) {
//                    rom.writeBatteryRam(address, value);
//                }
            }
        } else if (address > 0x2007 && address < 0x4000) {
            regWrite(0x2000 + (address and 0x7), value)
        } else {
            regWrite(address, value)
        }
    }

    fun writelow(address: Int, value: Short) {
        if (address < 0x2000) {
            // Mirroring of RAM:
            cpuMem!!.mem!![address and 0x7FF] = value
        } else if (address > 0x4017) {
            cpuMem!!.mem!![address] = value
        } else if (address > 0x2007 && address < 0x4000) {
            regWrite(0x2000 + (address and 0x7), value)
        } else {
            regWrite(address, value)
        }
    }

    override fun load(address: Int): Short {
        // Wrap around:

        var address = address
        address = address and 0xFFFF

        // Check address range:
        if (address > 0x4017) {
            // ROM:

            return cpuMemArray!![address]
        } else if (address >= 0x2000) {
            // I/O Ports.

            return regLoad(address)
        } else {
            // RAM (mirrored)

            return cpuMemArray!![address and 0x7FF]
        }
    }

    fun regLoad(address: Int): Short {
        when (address shr 12) {
            0 -> {}
            1 -> {}
            2 -> {
                run {}
                run {
                    // PPU Registers
                    when (address and 0x7) {
                        0x0 -> {
                            // 0x2000:
                            // PPU Control Register 1.
                            // (the value is stored both
                            // in main memory and in the
                            // PPU as flags):
                            // (not in the real NES)
                            return cpuMem!!.mem!![0x2000]
                        }

                        0x1 -> {
                            // 0x2001:
                            // PPU Control Register 2.
                            // (the value is stored both
                            // in main memory and in the
                            // PPU as flags):
                            // (not in the real NES)
                            return cpuMem!!.mem!![0x2001]
                        }

                        0x2 -> {
                            // 0x2002:
                            // PPU Status Register.
                            // The value is stored in
                            // main memory in addition
                            // to as flags in the PPU.
                            // (not in the real NES)
                            return ppu!!.readStatusRegister()
                        }

                        0x3 -> {
                            return 0
                        }

                        0x4 -> {
                            // 0x2004:
                            // Sprite Memory read.
                            return ppu!!.sramLoad()
                        }

                        0x5 -> {
                            return 0
                        }

                        0x6 -> {
                            return 0
                        }

                        0x7 -> {
                            // 0x2007:
                            // VRAM read:
                            return ppu!!.vramLoad()
                        }

                        else -> return 0
                    }
                }
            }

            3 -> {
                when (address and 0x7) {
                    0x0 -> {
                        return cpuMem!!.mem!![0x2000]
                    }

                    0x1 -> {
                        return cpuMem!!.mem!![0x2001]
                    }

                    0x2 -> {
                        return ppu!!.readStatusRegister()
                    }

                    0x3 -> {
                        return 0
                    }

                    0x4 -> {
                        return ppu!!.sramLoad()
                    }

                    0x5 -> {
                        return 0
                    }

                    0x6 -> {
                        return 0
                    }

                    0x7 -> {
                        return ppu!!.vramLoad()
                    }

                    else -> return 0
                }
            }

            4 -> {
                // Sound+Joypad registers
                when (address - 0x4015) {
                    0 -> {
                        // 0x4015:
                        // Sound channel enable, DMC Status
                        return papu.readReg(address)
                    }

                    1 -> {
                        // 0x4016:
                        // Joystick 1 + Strobe
                        return joy1Read()
                    }

                    2 -> {
                        // 0x4017:
                        // Joystick 2 + Strobe
                        if (mousePressed && ppu != null) {
                            // Check for white pixel nearby:

                            val sx: Int
                            val sy: Int
                            val ex: Int
                            val ey: Int
                            var w: Int
                            sx = max(0.0, (mouseX - 4).toDouble()).toInt()
                            ex = min(256.0, (mouseX + 4).toDouble()).toInt()
                            sy = max(0.0, (mouseY - 4).toDouble()).toInt()
                            ey = min(240.0, (mouseY + 4).toDouble()).toInt()
                            w = 0

                            var y = sy
                            while (y < ey) {
                                var x = sx
                                while (x < ex) {
                                    if ((ppu!!.buffer[(y shl 8) + x] and 0xFFFFFF) == 0xFFFFFF) {
                                        w = 0x1 shl 3
                                        break
                                    }
                                    x++
                                }
                                y++
                            }

                            w = w or (if (mousePressed) (0x1 shl 4) else 0)
                            return (joy2Read().toInt() or w).toShort()
                        } else {
                            return joy2Read()
                        }
                    }

                    else -> return 0
                }
            }

            else -> {}
        }

        return 0
    }

    fun regWrite(address: Int, value: Short) {
        when (address) {
            0x2000 -> {
                // PPU Control register 1
                cpuMem!!.write(address, value)
                ppu!!.updateControlReg1(value.toInt())
            }

            0x2001 -> {
                // PPU Control register 2
                cpuMem!!.write(address, value)
                ppu!!.updateControlReg2(value.toInt())
            }

            0x2003 -> {
                // Set Sprite RAM address:
                ppu!!.writeSRAMAddress(value)
            }

            0x2004 -> {
                // Write to Sprite RAM:
                ppu!!.sramWrite(value)
            }

            0x2005 -> {
                // Screen Scroll offsets:
                ppu!!.scrollWrite(value)
            }

            0x2006 -> {
                // Set VRAM address:
                ppu!!.writeVRAMAddress(value.toInt())
            }

            0x2007 -> {
                // Write to VRAM:
                ppu!!.vramWrite(value)
            }

            0x4014 -> {
                // Sprite Memory DMA Access
                ppu!!.sramDMA(value)
            }

            0x4015 -> {
                // Sound Channel Switch, DMC Status
                papu.writeReg(address, value)
            }

            0x4016 -> {

                // Joystick 1 + Strobe
                if (value.toInt() == 0 && joypadLastWrite == 1) {
                    joy1StrobeState = 0
                    joy2StrobeState = 0
                }
                joypadLastWrite = value.toInt()
            }

            0x4017 -> {
                // Sound channel frame sequencer:
                papu.writeReg(address, value)
            }

            else -> {
                // Sound registers
                if (address >= 0x4000 && address <= 0x4017) {
                    papu.writeReg(address, value)
                }
            }
        }
    }

    override fun joy1Read(): Short {
        val ret: Short

        when (joy1StrobeState) {
            0 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_A)
            1 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_B)
            2 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_SELECT)
            3 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_START)
            4 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_UP)
            5 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_DOWN)
            6 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_LEFT)
            7 -> ret = inputHandler.getKeyState(InputHandler.Companion.KEY_RIGHT)
            8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18 -> ret = 0.toShort()
            19 -> ret = 1.toShort()
            else -> ret = 0
        }

        joy1StrobeState++
        if (joy1StrobeState == 24) {
            joy1StrobeState = 0
        }

        return ret
    }

    override fun joy2Read(): Short {
        val st = joy2StrobeState

        joy2StrobeState++
        if (joy2StrobeState == 24) {
            joy2StrobeState = 0
        }

        // Handle the case where inputHandler2 is null (e.g., when using GUIAdapter)
        if (inputHandler2 == null) {
            // Return default values for all buttons (not pressed)
            if (st >= 0 && st <= 7) {
                return 0 // All buttons not pressed
            } else if (st == 16 || st == 17 || st == 19) {
                return 0.toShort()
            } else if (st == 18) {
                return 1.toShort()
            } else {
                return 0
            }
        }

        if (st == 0) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_A)
        } else if (st == 1) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_B)
        } else if (st == 2) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_SELECT)
        } else if (st == 3) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_START)
        } else if (st == 4) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_UP)
        } else if (st == 5) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_DOWN)
        } else if (st == 6) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_LEFT)
        } else if (st == 7) {
            return inputHandler2.getKeyState(InputHandler.Companion.KEY_RIGHT)
        } else if (st == 16) {
            return 0.toShort()
        } else if (st == 17) {
            return 0.toShort()
        } else if (st == 18) {
            return 1.toShort()
        } else if (st == 19) {
            return 0.toShort()
        } else {
            return 0
        }
    }

    override fun loadROM(romData: ROMData?) {
        this.rom = romData

        if (!rom!!.isValid() || rom!!.getRomBankCount() < 1) {
            //System.out.println("NoMapper: Invalid ROM! Unable to load.");
            return
        }

        // Load ROM into memory:
        loadPRGROM()

        // Load CHR-ROM:
        loadCHRROM()

        // Load Battery RAM (if present):
        loadBatteryRam()

        // Reset IRQ:
        //nes.getCpu().doResetInterrupt();
        cpu!!.requestIrq(CPU.Companion.IRQ_RESET)
    }

    protected fun loadPRGROM() {
        if (rom!!.getRomBankCount() > 1) {
            // Load the two first banks into memory.
            loadRomBank(0, 0x8000)
            loadRomBank(1, 0xC000)
        } else {
            // Load the one bank into both memory locations:
            loadRomBank(0, 0x8000)
            loadRomBank(0, 0xC000)
        }
    }

    protected fun loadCHRROM() {
        if (rom!!.getVromBankCount() > 0) {
            if (rom!!.getVromBankCount() == 1) {
                loadVromBank(0, 0x0000)
                loadVromBank(0, 0x1000)
            } else {
                loadVromBank(0, 0x0000)
                loadVromBank(1, 0x1000)
            }
        } else {
            //System.out.println("There aren't any CHR-ROM banks..");
        }
    }

    override fun loadBatteryRam() {
        if (rom!!.hasBatteryRam()) {
            val ram = rom!!.saveBatteryRam()
            if (ram != null && ram.size == 0x2000) {
                // Load Battery RAM into memory:

                System.arraycopy(ram, 0, cpuMem!!.mem, 0x6000, 0x2000)
            }
        }
    }

    protected fun loadRomBank(bank: Int, address: Int) {
        // Loads a ROM bank into the specified address.

        var bank = bank
        bank %= rom!!.getRomBankCount()
        val data = rom!!.getRomBank(bank)
        //cpuMem.write(address,data,data.length);
        System.arraycopy(rom!!.getRomBank(bank), 0, cpuMem!!.mem, address, 16384)
    }

    protected fun loadVromBank(bank: Int, address: Int) {
        if (rom!!.getVromBankCount() == 0) {
            return
        }
        ppu!!.triggerRendering()

        System.arraycopy(rom!!.getVromBank(bank % rom!!.getVromBankCount()), 0, ppuMem!!.mem, address, 4096)

        val vromTile = rom!!.getVromBankTiles(bank % rom!!.getVromBankCount())
        System.arraycopy(vromTile, 0, ppu!!.ptTile, address shr 4, 256)
    }

    protected fun load32kRomBank(bank: Int, address: Int) {
        loadRomBank((bank * 2) % rom!!.getRomBankCount(), address)
        loadRomBank((bank * 2 + 1) % rom!!.getRomBankCount(), address + 16384)
    }

    protected fun load8kVromBank(bank4kStart: Int, address: Int) {
        if (rom!!.getVromBankCount() == 0) {
            return
        }
        ppu!!.triggerRendering()

        loadVromBank((bank4kStart) % rom!!.getVromBankCount(), address)
        loadVromBank((bank4kStart + 1) % rom!!.getVromBankCount(), address + 4096)
    }

    protected fun load1kVromBank(bank1k: Int, address: Int) {
        if (rom!!.getVromBankCount() == 0) {
            return
        }
        ppu!!.triggerRendering()

        val bank4k = (bank1k / 4) % rom!!.getVromBankCount()
        val bankoffset = (bank1k % 4) * 1024
        System.arraycopy(rom!!.getVromBank(bank4k), 0, ppuMem!!.mem, bankoffset, 1024)

        // Update tiles:
        val vromTile = rom!!.getVromBankTiles(bank4k)
        val baseIndex = address shr 4
        System.arraycopy(vromTile, ((bank1k % 4) shl 6) + 0, ppu!!.ptTile, baseIndex + 0, 64)
    }

    protected fun load2kVromBank(bank2k: Int, address: Int) {
        if (rom!!.getVromBankCount() == 0) {
            return
        }
        ppu!!.triggerRendering()

        val bank4k = (bank2k / 2) % rom!!.getVromBankCount()
        val bankoffset = (bank2k % 2) * 2048
        System.arraycopy(rom!!.getVromBank(bank4k), bankoffset, ppuMem!!.mem, address, 2048)

        // Update tiles:
        val vromTile = rom!!.getVromBankTiles(bank4k)
        val baseIndex = address shr 4
        System.arraycopy(vromTile, ((bank2k % 2) shl 7) + 0, ppu!!.ptTile, baseIndex + 0, 128)
    }

    protected fun load8kRomBank(bank8k: Int, address: Int) {
        val bank16k = (bank8k / 2) % rom!!.getRomBankCount()
        val offset = (bank8k % 2) * 8192

        val bank = rom!!.getRomBank(bank16k)
        cpuMem!!.write(address, bank!!, offset, 8192)
    }

    override fun clockIrqCounter() {
        // Does nothing. This is used by the MMC3 mapper.
    }

    override fun latchAccess(address: Int) {
        // Does nothing. This is used by MMC2.
    }

    fun syncV(): Int {
        return 0
    }

    fun syncH(scanline: Int): Int {
        return 0
    }

    override fun setMouseState(pressed: Boolean, x: Int, y: Int) {
        mousePressed = pressed
        mouseX = x
        mouseY = y
    }

    override fun reset() {
        joy1StrobeState = 0
        joy2StrobeState = 0
        joypadLastWrite = 0
        mousePressed = false
    }

    override fun destroy() {
        rom = null
        cpu = null
        ppu = null
    }
}
