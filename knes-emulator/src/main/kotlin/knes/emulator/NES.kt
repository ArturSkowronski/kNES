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

import knes.controllers.ControllerProvider
import knes.emulator.cpu.CPU
import knes.emulator.mappers.MemoryMapper
import knes.emulator.memory.MemoryAccess
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
import java.util.*
import java.util.function.Consumer

class NES(
    var gui: GUI? = null,
    private val uiFactory: NESUIFactory? = null,
    private val screenView: ScreenView? = null,
    private val controller: ControllerProvider? = null
) {
    val cpu: CPU
    val ppu: PPU
    val papu: PAPU
    val cpuMemory: Memory = Memory(0x10000) // Main memory (internal to CPU)
    val ppuMemory: Memory = Memory(0x8000) // VRAM memory (internal to PPU)
    val sprMemory: Memory = Memory(0x100) // Sprite RAM  (internal to PPU)
    var memoryMapper: MemoryMapper? = null
    val palTable: PaletteTable

    var isRunning: Boolean = false
    var loadedRom: ROM? = null
    private var romFile: String? = null

    init {
        this.gui = gui ?: run {
            requireNotNull(uiFactory) { "Either gui or uiFactory must be provided" }
            requireNotNull(screenView) { "ScreenView must be provided when using uiFactory" }
            requireNotNull(controller) { "Controller must be provided when using uiFactory" }
            GUIAdapter(uiFactory.createInputHandler(controller), screenView)
        }

        ppu = PPU()
        papu = PAPU(this)
        palTable = PaletteTable()
        cpu = CPU(papu, ppu)

        cpu.init(this.memoryAccess, this.cpuMemory)
        ppu.init(
            this.gui!!,
            this.ppuMemory,
            this.sprMemory,
            this.cpuMemory,
            this.cpu,
            this.memoryMapper,
            this.papu.line,
            this.palTable
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

        if (loadedRom != null && loadedRom!!.isValid() && !cpu.isRunning) {
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

    fun reloadRom() {
        if (romFile != null) {
            loadRom(romFile!!)
        }
    }

    fun clearCPUMemory() {
       val random = Random()

        for (i in 0..0x1fff) {
            val r = random.nextInt(100)
            if (r < 33) {
                cpuMemory.mem!![i] = 0x00
            } else if (r < 66) {
                cpuMemory.mem!![i] = 0xFF.toShort()
            } else {
                cpuMemory.mem!![i] = (random.nextInt(256)).toShort()
            }
        }

        for (p in 0..3) {
            val i = p * 0x800
            cpuMemory.mem!![i + 0x008] = 0xF7
            cpuMemory.mem!![i + 0x009] = 0xEF
            cpuMemory.mem!![i + 0x00A] = 0xDF
            cpuMemory.mem!![i + 0x00F] = 0xBF
        }
    }

    val memoryAccess: MemoryAccess?
        get() = this.memoryMapper

    fun loadRom(file: String): Boolean {
        if (isRunning) {
            stopEmulation()
        }

        val rom = ROM(
            Consumer { percentComplete: Int? -> gui!!.showLoadProgress(percentComplete!!) },
            Consumer { message: String? -> gui!!.showErrorMsg(message!!) }
        )

        rom.load(file)

        if (rom.isValid()) {
            reset()
            val mapperProducer = MapperProducer(Consumer { message: String? -> gui!!.showErrorMsg(message!!) })
            this.memoryMapper = mapperProducer.produce(this, rom as ROMData)

            cpu.setMapper(this.memoryMapper!!)
            ppu.setMapper(this.memoryMapper!!)
            memoryMapper!!.loadROM(rom)

            ppu.setMirroring(rom.mirroringType)

            this.romFile = file
        }
        return rom.isValid()
    }

    fun reset() {
        if (this.memoryMapper != null) {
            memoryMapper!!.reset()
        }

        cpuMemory.reset()
        ppuMemory.reset()
        sprMemory.reset()

        clearCPUMemory()

        cpu.reset()
        cpu.init(
            this.memoryAccess,
            this.cpuMemory
        )
        ppu.reset()
        palTable.reset()
        papu.reset(this)
        gui!!.getJoy1().reset()
    }

    fun beginExecution() {
        cpu.beginExecution()
    }

    fun enableSound(enable: Boolean) {
        val wasRunning = this.isRunning
        if (wasRunning) {
            stopEmulation()
        }

        if (enable) {
            papu.start()
        } else {
            papu.stop()
        }

        Globals.enableSound = enable

        if (wasRunning) {
            startEmulation()
        }
    }
}
