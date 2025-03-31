package vnes.emulator.papu

/**
 * Interface for Delta Modulation Channel sample loading operations.
 * Decouples the DMC channel from direct memory access.
 */
interface DMCSampler {
    /**
     * Loads a 7-bit sample from the specified memory address
     * @param address CPU memory address (0x0000-0xFFFF)
     * @return Unsigned 7-bit sample value (0-127)
     */
    fun loadSample(address: Int): Int

    /**
     * @return true if there's a pending memory read operation
     */
    fun hasPendingRead(): Boolean

    /**
     * @return Current address pointer for sample loading
     */
    val currentAddress: Int
}