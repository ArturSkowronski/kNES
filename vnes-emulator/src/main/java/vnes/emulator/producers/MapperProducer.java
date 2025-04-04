package vnes.emulator.producers;

import vnes.emulator.mappers.MemoryMapper;
import vnes.emulator.NES;
import vnes.emulator.rom.ROMData;
import vnes.emulator.mappers.MapperDefault;

import java.util.function.Consumer;

/**
 * Factory class for creating mappers based on the mapper type.
 * This decouples ROM from specific mapper implementations.
 */
public class MapperProducer {
    
    private final Consumer<String> showErrorMsg;
    
    /**
     * Creates a new MapperFactory.
     * 
     * @param showErrorMsg Consumer for displaying error messages
     */
    public MapperProducer(Consumer<String> showErrorMsg) {
        this.showErrorMsg = showErrorMsg;
    }
    
    /**
     * Creates a mapper based on the mapper type in the ROM data.
     * 
     * @param romData The ROM data
     * @return The appropriate mapper for the ROM
     */
    public MemoryMapper produce(NES nes, ROMData romData) {
        if (isMapperSupported(romData.getMapperType())) {
            switch (romData.getMapperType()) {
                case 0: {
                    return new MapperDefault(nes);
                }
            }
        }
        
        // If the mapper wasn't supported, create the standard one:
        showErrorMsg.accept("Warning: Mapper not supported yet.");
        return new MapperDefault(nes);
    }
    
    /**
     * Checks if a mapper type is supported.
     * 
     * @param mapperType The mapper type to check
     * @return true if the mapper is supported, false otherwise
     */
    private boolean isMapperSupported(int mapperType) {
        // For now, only mapper 0 is supported
        return mapperType == 0;
    }
}