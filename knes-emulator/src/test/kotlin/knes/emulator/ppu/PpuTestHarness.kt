package knes.emulator.ppu

import knes.emulator.ByteBuffer
import knes.emulator.Memory
import knes.emulator.NES
import knes.emulator.cpu.CPU
import knes.emulator.input.InputHandler
import knes.emulator.mappers.MemoryMapper
import knes.emulator.papu.PAPU
import knes.emulator.producers.ChannelRegistryProducer
import knes.emulator.rom.ROMData
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import knes.emulator.utils.PaletteTable

/**
 * Minimal stub MemoryMapper for test environments.
 */
private class StubMemoryMapper : MemoryMapper {
    override fun loadROM(romData: ROMData?) {}
    override fun write(address: Int, value: Short) {}
    override fun load(address: Int): Short = 0
    override fun joy1Read(): Short = 0
    override fun joy2Read(): Short = 0
    override fun reset() {}
    override fun clockIrqCounter() {}
    override fun loadBatteryRam() {}
    override fun destroy() {}
    override fun stateLoad(buf: ByteBuffer?) {}
    override fun stateSave(buf: ByteBuffer?) {}
    override fun setMouseState(pressed: Boolean, x: Int, y: Int) {}
    override fun latchAccess(address: Int) {}
}

/**
 * Minimal stub GUI for test environments.
 */
private class StubGUI : GUI {
    private val stubInput = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0
    }

    override fun sendErrorMsg(message: String) {}
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = stubInput
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
}

/**
 * Test harness that provides a fully-initialized PPU for unit testing.
 *
 * Because PPU.init() requires PAPU (which requires NES, which requires GUI),
 * this harness constructs a minimal NES with a stub GUI, giving us a PPU
 * with all lateinit fields satisfied and ptTile/nameTable arrays allocated.
 *
 * Globals.enableSound is set to false so that PAPU won't try to open audio
 * hardware.
 */
class PpuTestHarness {
    val nes: NES
    val ppu: PPU
    val cpuMemory: Memory
    val sprMemory: Memory

    init {
        Globals.appletMode = false
        Globals.enableSound = false
        Globals.palEmulation = false

        val gui = StubGUI()
        nes = NES(gui)
        ppu = nes.ppu
        cpuMemory = nes.cpuMemory
        sprMemory = nes.sprMemory

        // Install a stub mapper so writeVRAMAddress (and other methods that call
        // memoryMapper!!.latchAccess) don't throw NullPointerException.
        ppu.setMapper(StubMemoryMapper())
    }

    /** Read a private Int field from the PPU via reflection. */
    fun readIntField(name: String): Int {
        val f = PPU::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.getInt(ppu)
    }

    /** Read a private Boolean field from the PPU via reflection. */
    fun readBoolField(name: String): Boolean {
        val f = PPU::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.getBoolean(ppu)
    }

    /** Read a private IntArray element from the PPU via reflection. */
    fun readIntArrayElement(arrayName: String, index: Int): Int {
        val f = PPU::class.java.getDeclaredField(arrayName)
        f.isAccessible = true
        val arr = f.get(ppu) as IntArray
        return arr[index]
    }

    /** Read a private BooleanArray element from the PPU via reflection. */
    fun readBoolArrayElement(arrayName: String, index: Int): Boolean {
        val f = PPU::class.java.getDeclaredField(arrayName)
        f.isAccessible = true
        val arr = f.get(ppu) as BooleanArray
        return arr[index]
    }
}
