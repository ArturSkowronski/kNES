package knes.emulator.cpu

import knes.emulator.Memory
import knes.emulator.memory.MemoryAccess

class TestMemoryAccess(private val memory: Memory) : MemoryAccess {
    override fun load(address: Int): Short = memory.load(address and 0xFFFF)
    override fun write(address: Int, value: Short) { memory.write(address and 0xFFFF, value) }
}
