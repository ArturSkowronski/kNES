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

package knes.emulator.memory

/**
 * Interface for memory access operations.
 * This interface defines the minimal set of methods needed for memory operations
 * and is used to decouple components from the full MemoryMapper implementation.
 */
interface MemoryAccess {
    fun write(address: Int, value: Short)
    fun load(address: Int): Short
}