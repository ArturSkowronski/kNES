package vnes.emulator.ppu

/**
 * Interface for the Picture Processing Unit (vnes.emulator.PPU) of the NES.
 * This interface defines the contract that any vnes.emulator.PPU implementation must fulfill.
 */
interface PPUCycles {
    fun setCycles(cycles: Int)
    fun emulateCycles()
}