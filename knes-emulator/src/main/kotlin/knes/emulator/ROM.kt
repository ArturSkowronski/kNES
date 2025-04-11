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
    lateinit var rom: Array<ShortArray?>
    lateinit var vrom: Array<ShortArray?>
    lateinit var saveRam: ShortArray
    lateinit var vromTile: Array<Array<Tile?>?>
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
            // Unable to load file.
            println("ROM: Failed to load file: $fileName")
            showErrorMsg.accept("Unable to load ROM file.")
            valid = false
            return
        }

        // Read header:
        header = ShortArray(16)
        System.arraycopy(b, 0, header, 0, 16)

        // Check first four bytes:
        val fcode = String(byteArrayOf(b!![0].toByte(), b[1].toByte(), b[2].toByte(), b[3].toByte()))
        if (fcode != "NES" + String(byteArrayOf(0x1A))) {
            System.out.println("Header is incorrect.");
            valid = false
            return
        }

        // Read header:
        romCount = header[4].toInt()
        vromCount = header[5] * 2 // Get the number of 4kB banks, not 8kB
        mirroring = (if ((header[6].toInt() and 1) != 0) 1 else 0)
        saveRam = ShortArray(0)
        trainer = (header[6].toInt() and 4) != 0
        fourScreen = (header[6].toInt() and 8) != 0
        mapperType = (header[6].toInt() shr 4) or (header[7].toInt() and 0xF0)

        // Battery RAM?
//        if (batteryRam) {
//            loadBatteryRam();
//        }

        // Check whether byte 8-15 are zero's:
        var foundError = false
        for (i in 8..15) {
            if (header!![i].toInt() != 0) {
                foundError = true
                break
            }
        }
        if (foundError) {
            // Ignore byte 7.
            mapperType = mapperType and 0xF
        }

        rom = Array<ShortArray?>(romCount) { ShortArray(16384) }
        vrom = Array<ShortArray?>(vromCount) { ShortArray(4096) }
        vromTile = Array<Array<Tile?>?>(vromCount) { arrayOfNulls<Tile>(256) }

        //try{

        // Load PRG-ROM banks:
        var offset = 16
        for (i in 0 until romCount) {
            for (j in 0..16383) {
                if (offset + j >= b.size) {
                    break
                }
                rom[i]!![j] = b[offset + j]
            }
            offset += 16384
        }

        // Load CHR-ROM banks:
        for (i in 0 until vromCount) {
            for (j in 0..4095) {
                if (offset + j >= b.size) {
                    break
                }
                vrom[i]!![j] = b[offset + j]
            }
            offset += 4096
        }

        // Create VROM tiles:
        for (i in 0 until vromCount) {
            for (j in 0..255) {
                vromTile[i]!![j] = Tile()
            }
        }

        // Convert CHR-ROM banks to tiles:
        //System.out.println("Converting CHR-ROM image data..");
        //System.out.println("VROM bank count: "+vromCount);
        var tileIndex: Int
        var leftOver: Int
        for (v in 0 until vromCount) {
            for (i in 0..4095) {
                tileIndex = i shr 4
                leftOver = i % 16
                if (leftOver < 8) {
                    vromTile[v]!![tileIndex]!!.setScanline(leftOver, vrom[v]!![i], vrom[v]!![i + 8])
                } else {
                    vromTile[v]!![tileIndex]!!.setScanline(leftOver - 8, vrom[v]!![i - 8], vrom[v]!![i])
                }
            }
        }

        valid = true
    }

    override fun isValid(): Boolean {
        return valid
    }

    override fun getRomBankCount(): Int {
        return romCount
    }

    // Returns number of 4kB VROM banks.
    override fun getVromBankCount(): Int {
        return vromCount
    }

    override fun getRomBank(bank: Int): ShortArray? {
        return rom[bank]
    }

    override fun getVromBank(bank: Int): ShortArray? {
        return vrom[bank]
    }

    override fun getVromBankTiles(bank: Int): Array<Tile?>? {
        return vromTile[bank]
    }

    override val mirroringType: Int
        get() {
            if (fourScreen) {
                return FOURSCREEN_MIRRORING
            }

            if (mirroring == 0) {
                return HORIZONTAL_MIRRORING
            }

            // default:
            return VERTICAL_MIRRORING
        }

    override fun hasBatteryRam(): Boolean {
        return saveBatteryRam().isNotEmpty()
    }

    fun hasTrainer(): Boolean {
        return trainer
    }

    fun setSaveState(enableSave: Boolean) {
        //this.enableSave = enableSave;
        if (enableSave && hasBatteryRam()) {
//          loadBatteryRam();
        }
    }

    override fun saveBatteryRam(): ShortArray {
        return saveRam
    }

    fun destroy() {
    }

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
        var mapperName: Array<String?>
        var mapperSupported: BooleanArray

        init {
            mapperName = arrayOfNulls<String>(255)
            mapperSupported = BooleanArray(255)
            for (i in 0..254) {
                mapperName[i] = "Unknown Mapper"
            }

            mapperName[0] = "NROM"

            // The mappers supported:
            mapperSupported[0] = true // No Mapper
        }
    }
}
