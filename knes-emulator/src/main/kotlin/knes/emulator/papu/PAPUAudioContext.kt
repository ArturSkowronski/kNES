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