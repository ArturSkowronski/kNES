package vnes.emulator.papu

/**
 * Interface for the Picture Processing Unit (PPU) of the NES.
 * This interface defines the contract that any PPU implementation must fulfill.
 */
interface PAPUClockFrame {
    fun clockFrameCounter(cycleCount: Int)
}