package vnes.emulator.ui

import javax.sound.sampled.SourceDataLine

/**
 * Interface for providing access to the PAPU (Programmable Audio Processing Unit) of the NES.
 * This interface abstracts the PAPU-related functionality from the NES class.
 */
interface PAPU_Applet_Functionality {
    /**
     * Gets the PAPU instance.
     *
     * @return The PAPU instance
     */
    val bufferIndex: Int
    val line: SourceDataLine?
    fun getMillisToAvailableAbove(target_avail: Int): Int
    fun writeBuffer()
}