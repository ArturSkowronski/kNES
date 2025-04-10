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