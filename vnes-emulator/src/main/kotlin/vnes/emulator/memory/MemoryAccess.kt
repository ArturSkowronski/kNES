package vnes.emulator.memory

/**
 * Interface for memory access operations.
 * This interface defines the minimal set of methods needed for memory operations
 * and is used to decouple components from the full MemoryMapper implementation.
 */
interface MemoryAccess {
    fun write(address: Int, value: Short)
    fun load(address: Int): Short
}