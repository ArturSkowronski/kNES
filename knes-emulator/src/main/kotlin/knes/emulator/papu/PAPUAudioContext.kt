package knes.emulator.papu

import knes.emulator.cpu.CPUIIrqRequester

interface PAPUAudioContext {
    /**
     * Get the IRQ requester for interrupt handling.
     * @return The IRQ requester
     */
    val irqRequester: CPUIIrqRequester

    /**
     * Get the DMC sampler for sample loading operations.
     * @return The DMC sampler
     */
    val PAPUDMCSampler: PAPUDMCSampler
    val sampleRate: Int
    fun clockFrameCounter(cycles: Int)
    fun updateChannelEnable(value: Int)

    // Method needed by channels to get length counter values
    fun getLengthMax(value: Int): Int
}