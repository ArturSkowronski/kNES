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

package knes.emulator.cpu

/**
 * Interface for requesting IRQs (Interrupt Requests).
 * This decouples audio channels from direct CPU access.
 */
interface CPUIIrqRequester {
    /**
     * Request an interrupt of the specified type.
     *
     * @param type The type of interrupt to request
     */
    fun requestIrq(type: Int)

    /**
     * Halt CPU execution for a specified number of cycles.
     *
     * @param cycles The number of cycles to halt
     */
    fun haltCycles(cycles: Int)
}