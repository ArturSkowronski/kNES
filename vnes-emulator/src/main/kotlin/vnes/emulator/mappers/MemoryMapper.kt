package vnes.emulator.mappers

import vnes.emulator.ByteBuffer
import vnes.emulator.memory.MemoryAccess
import vnes.emulator.rom.ROMData

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