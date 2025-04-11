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

package knes.emulator.mappers

import knes.emulator.ByteBuffer
import knes.emulator.memory.MemoryAccess
import knes.emulator.rom.ROMData

interface MemoryMapper : MemoryAccess {
    fun loadROM(romData: ROMData?)
    override fun write(address: Int, value: Short)
    override fun load(address: Int): Short
    fun joy1Read(): Short
    fun joy2Read(): Short
    fun reset()
    fun clockIrqCounter()
    fun loadBatteryRam()
    fun destroy()
    fun stateLoad(buf: ByteBuffer?)
    fun stateSave(buf: ByteBuffer?)
    fun setMouseState(pressed: Boolean, x: Int, y: Int)
    fun latchAccess(address: Int)
}