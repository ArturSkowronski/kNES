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

package knes.emulator.producers

import knes.emulator.NES
import knes.emulator.mappers.MapperDefault
import knes.emulator.mappers.MemoryMapper
import knes.emulator.rom.ROMData
import java.util.function.Consumer

/**
 * Factory class for creating mappers based on the mapper type.
 * This decouples ROM from specific mapper implementations.
 */
class MapperProducer
/**
 * Creates a new MapperFactory.
 *
 * @param showErrorMsg Consumer for displaying error messages
 */(private val showErrorMsg: Consumer<String?>) {
    /**
     * Creates a mapper based on the mapper type in the ROM data.
     *
     * @param romData The ROM data
     * @return The appropriate mapper for the ROM
     */
    fun produce(nes: NES, romData: ROMData): MemoryMapper {
        if (isMapperSupported(romData.mapperType)) {
            when (romData.mapperType) {
                0 -> {
                    return MapperDefault(nes)
                }
            }
        }


        // If the mapper wasn't supported, create the standard one:
        showErrorMsg.accept("Warning: Mapper not supported yet.")
        return MapperDefault(nes)
    }

    /**
     * Checks if a mapper type is supported.
     *
     * @param mapperType The mapper type to check
     * @return true if the mapper is supported, false otherwise
     */
    private fun isMapperSupported(mapperType: Int): Boolean {
        // For now, only mapper 0 is supported
        return mapperType == 0
    }
}