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

package knes.emulator.rom

import knes.emulator.Tile

/**
 * Interface that provides read-only access to ROM data.
 * This interface decouples mappers from the ROM implementation.
 */
interface ROMData {
    /**
     * Checks if the ROM is valid.
     * @return true if the ROM is valid, false otherwise
     */
    fun isValid(): Boolean
    fun saveBatteryRam(): ShortArray
    fun getRomBankCount(): Int
    fun getVromBankCount(): Int

    /**
     * Gets the ROM header.
     * @return the ROM header
     */
    val header: ShortArray?

    /**
     * Gets a specific ROM bank.
     * @param bank the bank number
     * @return the ROM bank data
     */
    fun getRomBank(bank: Int): ShortArray?

    /**
     * Gets a specific VROM bank.
     * @param bank the bank number
     * @return the VROM bank data
     */
    fun getVromBank(bank: Int): ShortArray?

    /**
     * Gets the tiles for a specific VROM bank.
     * @param bank the bank number
     * @return the VROM bank tiles
     */
    fun getVromBankTiles(bank: Int): Array<Tile?>?

    /**
     * Gets the mirroring type.
     * @return the mirroring type
     */
    val mirroringType: Int

    /**
     * Checks if the ROM has battery RAM.
     * @return true if the ROM has battery RAM, false otherwise
     */
    fun hasBatteryRam(): Boolean


    /**
     * Gets the mapper type.
     * @return the mapper type
     */
    val mapperType: Int
}