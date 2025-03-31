package vnes.emulator.papu

/**
 * Interface for requesting IRQs (Interrupt Requests).
 * This decouples audio channels from direct CPU access.
 */
interface IIrqRequester {
    /**
     * Request an interrupt of the specified type.
     *
     * @param type The type of interrupt to request
     */
    fun requestIrq(type: Int)

    /**
     * Halt CPU execution for a specified number of cycles.
     *
     * @param cycles The number of cycles to halt
     */
    fun haltCycles(cycles: Int)
}