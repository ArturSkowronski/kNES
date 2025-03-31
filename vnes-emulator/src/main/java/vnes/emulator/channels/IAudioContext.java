package vnes.emulator.channels;

import vnes.emulator.MemoryMapper;

public interface IAudioContext {
    /**
     * Get the IRQ requester for interrupt handling.
     * @return The IRQ requester
     */
    IIrqRequester getIrqRequester();
    
    /**
     * Get the DMC sampler for sample loading operations.
     * @return The DMC sampler
     */
    DMCSampler getDmcSampler();

    int getSampleRate();
    void clockFrameCounter(int cycles);
    void updateChannelEnable(int value);
    
    // Method needed by channels to get length counter values
    int getLengthMax(int value);
}
