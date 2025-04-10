package knes.emulator.ppu

/**
 * Interface for the Picture Processing Unit (PPU) of the knes.emulator.NES.
 * This interface defines the contract that any PPU implementation must fulfill.
 */
interface PPUCycles {
    fun setCycles(cycles: Int)
    fun emulateCycles()
}