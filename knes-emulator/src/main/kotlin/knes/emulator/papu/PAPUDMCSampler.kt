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

package knes.emulator.papu

/**
 * Interface for Delta Modulation Channel sample loading operations.
 * Decouples the DMC channel from direct memory access.
 */
interface PAPUDMCSampler {
    /**
     * Loads a 7-bit sample from the specified memory address
     * @param address CPU memory address (0x0000-0xFFFF)
     * @return Unsigned 7-bit sample value (0-127)
     */
    fun loadSample(address: Int): Int

    /**
     * @return true if there's a pending memory read operation
     */
    fun hasPendingRead(): Boolean

    /**
     * @return Current address pointer for sample loading
     */
    val currentAddress: Int
}