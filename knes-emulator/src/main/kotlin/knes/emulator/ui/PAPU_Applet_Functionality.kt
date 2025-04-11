/*
 *
 *  * Copyright (C) 2025 Artur Skowroński
 *  * This file is part of kNES, a fork of vNES (GPLv3) rewritten in Kotlin.
 *  *
 *  * vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license.
 *  * This project is a reimplementation and extension of that work.
 *  *
 *  * kNES is licensed under the GNU General Public License v3.0.
 *  * See the LICENSE file for more details.
 *
 */

package knes.emulator.ui

import javax.sound.sampled.SourceDataLine

/**
 * Interface for providing access to the PAPU (Programmable Audio Processing Unit) of the NES.
 * This interface abstracts the PAPU-related functionality from the knes.emulator.NES class.
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