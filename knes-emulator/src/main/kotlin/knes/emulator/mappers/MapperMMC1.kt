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

import knes.emulator.NES
import knes.emulator.cpu.CPU
import knes.emulator.rom.ROMData

/**
 * MMC1 (Mapper 1) - Nintendo's SxROM board.
 *
 * Used by ~680 games including Final Fantasy, Zelda, Metroid, Mega Man 2.
 *
 * Features:
 * - PRG-ROM: switchable in 16KB or 32KB modes
 * - CHR-ROM/RAM: switchable in 4KB or 8KB modes
 * - Mirroring: software-controlled
 * - Optional 8KB PRG-RAM at $6000-$7FFF
 *
 * Registers are written via a 5-bit shift register (serial interface).
 * Writing with bit 7 set resets the shift register.
 *
 * Reference: https://www.nesdev.org/wiki/MMC1
 */
class MapperMMC1(nes: NES) : MapperDefault(nes) {

    // Shift register (5-bit serial write)
    private var shiftRegister: Int = 0
    private var shiftCount: Int = 0

    // Internal registers
    private var regControl: Int = 0x0C  // Power-on default: PRG mode 3 (fix last bank)
    private var regCHR0: Int = 0
    private var regCHR1: Int = 0
    private var regPRG: Int = 0

    override fun loadROM(romData: ROMData?) {
        this.rom = romData

        if (!rom!!.isValid() || rom!!.getRomBankCount() < 1) {
            return
        }

        // Initialize with default MMC1 state (mode 3: fix last bank at $C000)
        regControl = 0x0C
        shiftRegister = 0
        shiftCount = 0
        regCHR0 = 0
        regCHR1 = 0
        regPRG = 0

        // Load initial PRG banks: first bank at $8000, last bank at $C000
        loadRomBank(0, 0x8000)
        loadRomBank(rom!!.getRomBankCount() - 1, 0xC000)

        // Load CHR-ROM if present
        loadCHRROM()

        // Load Battery RAM
        loadBatteryRam()

        // Trigger reset
        cpu!!.requestIrq(CPU.IRQ_RESET)
    }

    override fun write(address: Int, value: Short) {
        if (address < 0x8000) {
            // RAM and registers — use default handling
            super.write(address, value)
            return
        }

        // $8000-$FFFF: MMC1 shift register writes
        val data = value.toInt() and 0xFF

        if (data and 0x80 != 0) {
            // Bit 7 set: reset shift register and set PRG mode to 3
            shiftRegister = 0
            shiftCount = 0
            regControl = regControl or 0x0C
            updatePRGBanks()
            return
        }

        // Shift in bit 0 (LSB first)
        shiftRegister = shiftRegister or ((data and 1) shl shiftCount)
        shiftCount++

        if (shiftCount == 5) {
            // 5 bits collected — write to target register based on address
            val targetReg = (address shr 13) and 0x03 // bits 14-13

            when (targetReg) {
                0 -> { // $8000-$9FFF → Control
                    regControl = shiftRegister
                    updateMirroring()
                    updatePRGBanks()
                    updateCHRBanks()
                }
                1 -> { // $A000-$BFFF → CHR bank 0
                    regCHR0 = shiftRegister
                    updateCHRBanks()
                }
                2 -> { // $C000-$DFFF → CHR bank 1
                    regCHR1 = shiftRegister
                    updateCHRBanks()
                }
                3 -> { // $E000-$FFFF → PRG bank
                    regPRG = shiftRegister
                    updatePRGBanks()
                }
            }

            // Reset shift register after transfer
            shiftRegister = 0
            shiftCount = 0
        }
    }

    private fun updateMirroring() {
        if (ppu == null) return
        when (regControl and 0x03) {
            0 -> ppu!!.setMirroring(knes.emulator.ROM.SINGLESCREEN_MIRRORING)
            1 -> ppu!!.setMirroring(knes.emulator.ROM.SINGLESCREEN_MIRRORING2)
            2 -> ppu!!.setMirroring(knes.emulator.ROM.VERTICAL_MIRRORING)
            3 -> ppu!!.setMirroring(knes.emulator.ROM.HORIZONTAL_MIRRORING)
        }
    }

    private fun updatePRGBanks() {
        if (rom == null) return
        val prgMode = (regControl shr 2) and 0x03
        val prgBank = regPRG and 0x0F
        val bankCount = rom!!.getRomBankCount()

        when (prgMode) {
            0, 1 -> {
                // 32KB mode: switch both banks together (ignore low bit of bank number)
                val bank = (prgBank and 0x0E) % bankCount
                loadRomBank(bank, 0x8000)
                loadRomBank((bank + 1) % bankCount, 0xC000)
            }
            2 -> {
                // Fix first bank ($8000) to bank 0, switch $C000
                loadRomBank(0, 0x8000)
                loadRomBank(prgBank % bankCount, 0xC000)
            }
            3 -> {
                // Fix last bank ($C000), switch $8000
                loadRomBank(prgBank % bankCount, 0x8000)
                loadRomBank(bankCount - 1, 0xC000)
            }
        }
    }

    private fun updateCHRBanks() {
        if (rom == null) return
        if (rom!!.getVromBankCount() == 0) {
            // CHR-RAM, no bank switching needed
            return
        }

        val chrMode = (regControl shr 4) and 0x01

        if (chrMode == 0) {
            // 8KB mode: use CHR bank 0 register (ignore low bit)
            val bank = regCHR0 and 0x1E
            load8kVromBank(bank, 0x0000)
        } else {
            // 4KB mode: two independent banks
            loadVromBank(regCHR0 % rom!!.getVromBankCount(), 0x0000)
            loadVromBank(regCHR1 % rom!!.getVromBankCount(), 0x1000)
        }
    }

    override fun reset() {
        super.reset()
        shiftRegister = 0
        shiftCount = 0
        regControl = 0x0C
        regCHR0 = 0
        regCHR1 = 0
        regPRG = 0
    }
}
