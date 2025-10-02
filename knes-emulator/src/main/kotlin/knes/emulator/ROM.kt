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

package knes.emulator

import knes.emulator.rom.ROMData
import knes.emulator.utils.FileLoader
import java.io.RandomAccessFile
import java.util.function.Consumer

class ROM(private val showLoadProgress: Consumer<Int>, private val showErrorMsg: Consumer<String?>) : ROMData {
    var failedSaveFile: Boolean = false
    var saveRamUpToDate: Boolean = true
    override lateinit var header: ShortArray
    lateinit var rom: Array<ShortArray>
    lateinit var vrom: Array<ShortArray>
    lateinit var saveRam: ShortArray
    lateinit var vromTile: Array<Array<Tile>>
    var romCount: Int = 0
    var vromCount: Int = 0
    var mirroring: Int = 0
    lateinit var batteryRam: ShortArray
    var trainer: Boolean = false
    var fourScreen: Boolean = false
    override var mapperType: Int = 0
    var fileName: String? = null
    var raFile: RandomAccessFile? = null
    var enableSave: Boolean = true
    var valid: Boolean = false

    fun load(fileName: String) {
        this.fileName = fileName
        println("ROM: Loading file: $fileName")
        val loader = FileLoader()
        val b = loader.loadFile(fileName, showLoadProgress)

        if (b == null || b.size == 0) {
            println("ROM: Failed to load file: $fileName")
            showErrorMsg.accept("Unable to load ROM file.")
            valid = false
            return
        }

        header = b.copyOfRange(0, 16)

        val fcode = String(byteArrayOf(b[0].toByte(), b[1].toByte(), b[2].toByte(), b[3].toByte()))
        if (fcode != "NES" + String(byteArrayOf(0x1A))) {
            println("Header is incorrect.")
            valid = false
            return
        }

        romCount = header[4].toInt()
        vromCount = header[5] * 2 // Get the number of 4kB banks, not 8kB
        mirroring = if ((header[6].toInt() and 1) != 0) 1 else 0
        saveRam = ShortArray(0)
        trainer = (header[6].toInt() and 4) != 0
        fourScreen = (header[6].toInt() and 8) != 0
        mapperType = (header[6].toInt() shr 4) or (header[7].toInt() and 0xF0)

        // Check whether byte 8-15 are zero's:
        val foundError = (8..15).any { header[it].toInt() != 0 }
        if (foundError) {
            mapperType = mapperType and 0xF
        }

        rom = Array(romCount) { ShortArray(16384) }
        vrom = Array(vromCount) { ShortArray(4096) }
        vromTile = Array(vromCount) { Array(256) { Tile() } }

        // Load PRG-ROM banks:
        var offset = 16
        for (i in 0 until romCount) {
            val end = minOf(offset + 16384, b.size)
            b.copyInto(rom[i], 0, offset, end)
            offset += 16384
        }

        // Load CHR-ROM banks:
        for (i in 0 until vromCount) {
            val end = minOf(offset + 4096, b.size)
            b.copyInto(vrom[i], 0, offset, end)
            offset += 4096
        }

        // Convert CHR-ROM banks to tiles:
        for (v in 0 until vromCount) {
            for (i in 0..4095) {
                val tileIndex = i shr 4
                val leftOver = i % 16
                if (leftOver < 8) {
                    vromTile[v][tileIndex].setScanline(leftOver, vrom[v][i], vrom[v][i + 8])
                } else {
                    vromTile[v][tileIndex].setScanline(leftOver - 8, vrom[v][i - 8], vrom[v][i])
                }
            }
        }

        valid = true
    }

    override fun isValid(): Boolean = valid

    override fun getRomBankCount(): Int = romCount

    // Returns number of 4kB VROM banks.
    override fun getVromBankCount(): Int = vromCount

    override fun getRomBank(bank: Int): ShortArray = rom[bank]

    override fun getVromBank(bank: Int): ShortArray = vrom[bank]

    override fun getVromBankTiles(bank: Int): Array<Tile> = vromTile[bank]

    override val mirroringType: Int
        get() = when {
            fourScreen -> FOURSCREEN_MIRRORING
            mirroring == 0 -> HORIZONTAL_MIRRORING
            else -> VERTICAL_MIRRORING
        }

    override fun hasBatteryRam(): Boolean = saveBatteryRam().isNotEmpty()

    fun setSaveState(enableSave: Boolean) {
        if (enableSave && hasBatteryRam()) {
            // loadBatteryRam()
        }
    }

    override fun saveBatteryRam(): ShortArray = saveRam

    fun destroy() {}

    companion object {
        // Mirroring types:
        const val VERTICAL_MIRRORING: Int = 0
        const val HORIZONTAL_MIRRORING: Int = 1
        const val FOURSCREEN_MIRRORING: Int = 2
        const val SINGLESCREEN_MIRRORING: Int = 3
        const val SINGLESCREEN_MIRRORING2: Int = 4
        const val SINGLESCREEN_MIRRORING3: Int = 5
        const val SINGLESCREEN_MIRRORING4: Int = 6
        const val CHRROM_MIRRORING: Int = 7

        val mapperName: Array<String> = Array(255) { "Unknown Mapper" }.apply { this[0] = "NROM" }
        val mapperSupported: BooleanArray = BooleanArray(255).apply { this[0] = true }
    }
}
