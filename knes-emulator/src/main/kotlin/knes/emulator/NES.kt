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

package knes.emulator

import knes.emulator.cpu.CPU
import knes.emulator.mappers.MemoryMapper
import knes.emulator.papu.PAPU
import knes.emulator.ppu.PPU
import knes.emulator.producers.ChannelRegistryProducer
import knes.emulator.producers.MapperProducer
import knes.emulator.rom.ROMData
import knes.emulator.ui.GUI
import knes.emulator.ui.GUIAdapter
import knes.emulator.ui.NESUIFactory
import knes.emulator.ui.ScreenView
import knes.emulator.utils.Globals
import knes.emulator.utils.PaletteTable
import java.util.function.Consumer
import kotlin.random.Random

class NES(
    var gui: GUI? = null,
    private val uiFactory: NESUIFactory? = null,
    private val screenView: ScreenView? = null,
) {
    val cpu: CPU
    val ppu: PPU
    val papu: PAPU

    val cpuMemory: Memory = Memory(0x10000) // Main memory (internal to CPU)
    val ppuMemory: Memory = Memory(0x8000) // VRAM memory (internal to PPU)
    val sprMemory: Memory = Memory(0x100) // Sprite RAM  (internal to PPU)

    val palTable: PaletteTable

    var isRunning: Boolean = false
    var isRomLoaded: Boolean = false

    var memoryMapper: MemoryMapper? = null

    init {
        this.gui = gui ?: run {
            requireNotNull(uiFactory) { "Either gui or uiFactory must be provided" }
            requireNotNull(screenView) { "ScreenView must be provided when using uiFactory" }
            GUIAdapter(uiFactory.inputHandler, screenView)
        }

        ppu = PPU()
        papu = PAPU(this)
        palTable = PaletteTable()
        cpu = CPU(papu, ppu)

        cpu.init(cpuMemory)
        ppu.init(
            gui!!,
            ppuMemory,
            sprMemory,
            cpuMemory,
            cpu,
            papu.line,
            palTable
        )

        papu.init(ChannelRegistryProducer())
        papu.irqRequester = cpu
        palTable.init()

        enableSound(true)

        clearCPUMemory()
    }


    fun getScreenView(): ScreenView {
        return gui!!.getScreenView()
    }

    fun stateLoad(buf: ByteBuffer): Boolean {
        var continueEmulation = false
        val success: Boolean

        if (cpu.isRunning) {
            continueEmulation = true
            stopEmulation()
        }

        if (buf.readByte().toInt() == 1) {
            cpuMemory.stateLoad(buf)
            ppuMemory.stateLoad(buf)
            sprMemory.stateLoad(buf)
            cpu.stateLoad(buf)
            memoryMapper?.stateLoad(buf)
            ppu.stateLoad(buf)
            success = true
        } else {
            success = false
        }

        if (continueEmulation) {
            startEmulation()
        }

        return success
    }

    fun stateSave(buf: ByteBuffer) {
        val continueEmulation = this.isRunning
        stopEmulation()

        // Version:
        buf.putByte(1.toShort())

        // Let units save their state:
        cpuMemory.stateSave(buf)
        ppuMemory.stateSave(buf)
        sprMemory.stateSave(buf)
        cpu.stateSave(buf)
        memoryMapper?.stateSave(buf)
        ppu.stateSave(buf)

        // Continue emulation:
        if (continueEmulation) {
            startEmulation()
        }
    }

    fun startEmulation() {
        if (Globals.enableSound && !papu.isRunning) {
            papu.start()
        }

        if (isRomLoaded && !cpu.isRunning) {
            cpu.beginExecution()
            isRunning = true
        }
    }

    fun stopEmulation() {
        if (cpu.isRunning) {
            cpu.endExecution()
            isRunning = false
        }

        if (Globals.enableSound && papu.isRunning) {
            papu.stop()
        }
    }

    fun clearCPUMemory() {
       val random = Random(System.nanoTime())

        for (i in 0..0x1fff) {
            when (random.nextInt(100)) {
                in 0 until 33 -> cpuMemory.mem[i] = 0x00
                in 33 until 66 -> cpuMemory.mem[i] = 0xFF.toShort()
                else -> cpuMemory.mem[i] = random.nextInt(256).toShort()
            }
        }

        for (p in 0..3) {
            val i = p * 0x800
            cpuMemory.mem[i + 0x008] = 0xF7
            cpuMemory.mem[i + 0x009] = 0xEF
            cpuMemory.mem[i + 0x00A] = 0xDF
            cpuMemory.mem[i + 0x00F] = 0xBF
        }
    }

    fun loadRom(file: String): Boolean {
        if (isRunning) {
            stopEmulation()
        }

        val rom = ROM(
            Consumer { percentComplete: Int? -> gui?.showLoadProgress(percentComplete ?: 0) },
            Consumer { message: String? -> gui?.showErrorMsg(message!!) }
        )

        rom.load(file)

        if (rom.isValid()) {
            reset()
            val mapperProducer = MapperProducer(Consumer { message: String? -> gui?.showErrorMsg(message!!) })
            val memoryMapper = mapperProducer.produce(this, rom as ROMData)

            memoryMapper.loadROM(rom)

            cpu.setMapper(memoryMapper)
            ppu.setMapper(memoryMapper)

            ppu.setMirroring(rom.mirroringType)

            this.memoryMapper = memoryMapper
        }

        isRomLoaded = rom.isValid()
        return isRomLoaded
    }

    fun reset() {
        memoryMapper?.reset()
        cpuMemory.reset()
        ppuMemory.reset()
        sprMemory.reset()
        clearCPUMemory()

        cpu.reset()
        cpu.init(cpuMemory)
        ppu.reset()
        palTable.reset()
        papu.reset(this)
        gui!!.getJoy1().reset()

    }

    fun beginExecution() {
        cpu.beginExecution()
    }

    fun enableSound(enable: Boolean) {
        if (isRunning) {
            stopEmulation()
        }

        if (enable) {
            papu.start()
        } else {
            papu.stop()
        }

        Globals.enableSound = enable

        if (isRunning) {
            startEmulation()
        }
    }
}
