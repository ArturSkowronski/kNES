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
import knes.emulator.input.InputHandler
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
import knes.emulator.utils.PaletteTable
import java.util.function.Consumer

class NES(var gui: GUI) {
    constructor(uiFactory: NESUIFactory, screenView: ScreenView) :
            this(GUIAdapter(uiFactory.inputHandler, screenView))

    val ppu: PPU = PPU()
    val papu: PAPU = PAPU(this)
    val cpu: CPU = CPU(papu, ppu)

    val palTable: PaletteTable = PaletteTable()

    val cpuMemory: Memory = Memory(0x10000) // Main memory (internal to CPU)
    val ppuMemory: Memory = Memory(0x8000) // VRAM memory (internal to PPU)
    val sprMemory: Memory = Memory(0x100) // Sprite RAM  (internal to PPU)

    var isRunning: Boolean = false
    var isRomLoaded: Boolean = false

    var memoryMapper: MemoryMapper? = null

    val inputHandler: InputHandler = gui.getJoy1()
    val inputHandler2: InputHandler? = gui.getJoy2()

    init {
        cpu.init(cpuMemory)
        ppu.init(
            gui.getScreenView(),
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
        cpu.clearCPUMemory()
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
        if (!papu.isRunning) {
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

        if (papu.isRunning) {
            papu.stop()
        }
    }

    fun loadRom(file: String): Boolean {
        if (isRunning) {
            stopEmulation()
        }

        val rom = ROM(
            Consumer { percentComplete: Int? -> gui.sendDebugMessage("Load Progress" + (percentComplete ?: 0)) },
            Consumer { message: String? -> gui.sendErrorMsg(message!!) }
        )

        rom.load(file)

        if (rom.isValid()) {
            reset()
            val mapperProducer = MapperProducer(Consumer { message: String? -> gui.sendErrorMsg(message!!) })
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
        cpu.clearCPUMemory()

        cpu.reset()
        cpu.init(cpuMemory)
        ppu.reset()
        palTable.reset()
        papu.reset(this)
        gui.getJoy1().reset()

    }

    fun beginExecution() {
        cpu.beginExecution()
    }
}
