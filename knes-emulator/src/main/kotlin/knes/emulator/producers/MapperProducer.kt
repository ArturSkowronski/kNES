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
import knes.emulator.mappers.MapperMMC1
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
    fun produce(nes: NES, romData: ROMData): MemoryMapper = when (romData.mapperType) {
        NROM -> MapperDefault(nes)
        MMC1 -> MapperMMC1(nes)
        else -> {
            // Substituting NROM lets the ROM "load" and then produce nonsense. The
            // substitution stays for now, because the UIs tolerate it, but callers can
            // ask rather than having to notice — see [NES.isMapperSupported].
            showErrorMsg.accept(
                "Mapper ${romData.mapperType} is not supported; falling back to NROM, " +
                    "which will not run this ROM correctly."
            )
            MapperDefault(nes)
        }
    }

    companion object {
        const val NROM: Int = 0
        const val MMC1: Int = 1

        /**
         * Mappers with a real implementation.
         *
         * One source of truth. There used to be two — the `when` above and a separate
         * membership check — which is how a list like this drifts.
         */
        val SUPPORTED: Set<Int> = setOf(NROM, MMC1)

        fun isSupported(mapperType: Int): Boolean = mapperType in SUPPORTED
    }
}