package vnes.emulator.papu

interface IAudioContext {
    /**
     * Get the IRQ requester for interrupt handling.
     * @return The IRQ requester
     */
    val irqRequester: IIrqRequester

    /**
     * Get the DMC sampler for sample loading operations.
     * @return The DMC sampler
     */
    val dmcSampler: DMCSampler
    val sampleRate: Int
    fun clockFrameCounter(cycles: Int)
    fun updateChannelEnable(value: Int)

    // Method needed by channels to get length counter values
    fun getLengthMax(value: Int): Int
}