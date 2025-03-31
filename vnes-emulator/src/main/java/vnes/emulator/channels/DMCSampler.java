package vnes.emulator.channels;

/**
 * Interface for Delta Modulation Channel sample loading operations.
 * Decouples the DMC channel from direct memory access.
 */
public interface DMCSampler {
    /**
     * Loads a 7-bit sample from the specified memory address
     * @param address CPU memory address (0x0000-0xFFFF)
     * @return Unsigned 7-bit sample value (0-127)
     */
    int loadSample(int address);

    /**
     * @return true if there's a pending memory read operation
     */
    boolean hasPendingRead();

    /**
     * @return Current address pointer for sample loading
     */
    int getCurrentAddress();
}
