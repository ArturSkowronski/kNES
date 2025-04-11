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
import java.util.Random
import java.util.function.Consumer

class NES {
    var gui: GUI?
        private set
    var cpu: CPU? = null
        private set
    var ppu: PPU? = null
        private set
    var papu: PAPU? = null
        private set

    var cpuMemory: Memory? = null
        private set
    var ppuMemory: Memory? = null
        private set
    var sprMemory: Memory? = null
        private set

    var memoryMapper: MemoryMapper? = null
        private set

    var palTable: PaletteTable? = null
        private set

    var rom: ROM? = null
        private set
    private var romFile: String? = null

    var isRunning: Boolean = false
        private set

    /**
     * Constructor that takes a GUI directly.
     *
     * @param gui The GUI implementation to use
     */
    constructor(gui: GUI?) {
        this.gui = gui
        initializeConstructor()
    }

    /**
     * Constructor that creates a GUI using a factory and screen view.
     *
     * @param uiFactory The factory to create UI components
     * @param screenView The screen view to use
     */
    constructor(uiFactory: NESUIFactory, screenView: ScreenView) {
        val inputHandler = uiFactory.createInputHandler()
        this.gui = GUIAdapter(inputHandler!!, screenView)
        initializeConstructor()
    }

    /**
     * Initialize common components used by all constructors.
     */
    private fun initializeConstructor() {
        this.cpuMemory = Memory(0x10000) // Main memory (internal to CPU)
        this.ppuMemory = Memory(0x8000) // VRAM memory (internal to PPU)
        this.sprMemory = Memory(0x100) // Sprite RAM  (internal to PPU)

        ppu = PPU()
        papu = PAPU(this)
        palTable = PaletteTable()
        cpu = CPU(papu!!, ppu!!)

        cpu!!.init(this.memoryAccess, this.cpuMemory!!)
        ppu!!.init(
            this.gui!!,
            this.ppuMemory,
            this.sprMemory,
            this.cpuMemory!!,
            this.cpu!!,
            this.memoryMapper,
            this.papu!!.line,
            this.palTable!!
        )

        papu!!.init(ChannelRegistryProducer())
        papu!!.irqRequester = cpu!!
        palTable!!.init()

        enableSound(true)

        clearCPUMemory()
    }

    val screenView: ScreenView
        get() = gui!!.getScreenView()

    fun stateLoad(buf: ByteBuffer): Boolean {
        var continueEmulation = false
        val success: Boolean

        if (cpu!!.isRunning) {
            continueEmulation = true
            stopEmulation()
        }

        if (buf.readByte().toInt() == 1) {
            cpuMemory!!.stateLoad(buf)
            ppuMemory!!.stateLoad(buf)
            sprMemory!!.stateLoad(buf)
            cpu!!.stateLoad(buf)
            memoryMapper!!.stateLoad(buf)
            ppu!!.stateLoad(buf)
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
        cpuMemory!!.stateSave(buf)
        ppuMemory!!.stateSave(buf)
        sprMemory!!.stateSave(buf)
        cpu!!.stateSave(buf)
        memoryMapper!!.stateSave(buf)
        ppu!!.stateSave(buf)

        // Continue emulation:
        if (continueEmulation) {
            startEmulation()
        }
    }

    fun startEmulation() {
        if (Globals.enableSound && !papu!!.isRunning) {
            papu!!.start()
        }

        if (rom != null && rom!!.isValid() && !cpu!!.isRunning) {
            cpu!!.beginExecution()
            isRunning = true
        }
    }

    fun stopEmulation() {
        if (cpu!!.isRunning) {
            cpu!!.endExecution()
            isRunning = false
        }

        if (Globals.enableSound && papu!!.isRunning) {
            papu!!.stop()
        }
    }

    fun reloadRom() {
        if (romFile != null) {
            loadRom(romFile!!)
        }
    }

    fun clearCPUMemory() {
        // Initialize RAM with a mix of values (0x00, 0xFF, and random bytes)
        // This is more accurate to real NES behavior and fixes issues with games like SMB
        val random = Random()

        for (i in 0..0x1fff) {
            // Use a mix of values: 0x00, 0xFF, and random bytes
            val r = random.nextInt(100)
            if (r < 33) {
                cpuMemory!!.mem!![i] = 0x00
            } else if (r < 66) {
                cpuMemory!!.mem!![i] = 0xFF.toShort()
            } else {
                cpuMemory!!.mem!![i] = (random.nextInt(256)).toShort()
            }
        }

        // Set specific values that are important for proper operation
        for (p in 0..3) {
            val i = p * 0x800
            cpuMemory!!.mem!![i + 0x008] = 0xF7
            cpuMemory!!.mem!![i + 0x009] = 0xEF
            cpuMemory!!.mem!![i + 0x00A] = 0xDF
            cpuMemory!!.mem!![i + 0x00F] = 0xBF
        }
    }

    val memoryAccess: MemoryAccess?
        get() = this.memoryMapper

    fun loadRom(file: String): Boolean {
        if (isRunning) {
            stopEmulation()
        }

        rom = ROM(
            Consumer { percentComplete: Int? -> gui!!.showLoadProgress(percentComplete!!) },
            Consumer { message: String? ->
                gui!!.showErrorMsg(
                    message!!
                )
            })
        rom!!.load(file)
        if (rom!!.isValid()) {
            // The CPU will load
            // the ROM into the CPU
            // and PPU memory.

            reset()

            val mapperProducer = MapperProducer(Consumer { message: String? -> gui!!.showErrorMsg(message!!) })
            this.memoryMapper = mapperProducer.produce(this, rom as ROMData)

            cpu!!.setMapper(this.memoryMapper)
            ppu!!.setMapper(this.memoryMapper!!)
            memoryMapper!!.loadROM(rom)
            ppu!!.setMirroring(rom!!.mirroringType)

            this.romFile = file
        }
        return rom!!.isValid()
    }

    fun reset() {
        if (this.memoryMapper != null) {
            memoryMapper!!.reset()
        }

        cpuMemory!!.reset()
        ppuMemory!!.reset()
        sprMemory!!.reset()

        clearCPUMemory()

        cpu!!.reset()
        cpu!!.init(
            this.memoryAccess,
            this.cpuMemory!!
        )
        ppu!!.reset()
        palTable!!.reset()
        papu!!.reset(this)

        val joy1 = gui!!.getJoy1()
        if (joy1 != null) {
            joy1.reset()
        }
    }

    fun beginExecution() {
        cpu!!.beginExecution()
    }

    fun enableSound(enable: Boolean) {
        val wasRunning = this.isRunning
        if (wasRunning) {
            stopEmulation()
        }

        if (enable) {
            papu!!.start()
        } else {
            papu!!.stop()
        }

        Globals.enableSound = enable

        if (wasRunning) {
            startEmulation()
        }
    }

    fun menuListener() {
        if (this.isRunning) {
            stopEmulation()
            reset()
            reloadRom()
            startEmulation()
        }
    }

    fun destroy() {
        if (cpu != null) {
            cpu!!.destroy()
        }
        if (ppu != null) {
            ppu!!.destroy()
        }
        if (papu != null) {
            papu!!.destroy()
        }
        if (this.cpuMemory != null) {
            cpuMemory!!.destroy()
        }
        if (this.ppuMemory != null) {
            ppuMemory!!.destroy()
        }
        if (this.sprMemory != null) {
            sprMemory!!.destroy()
        }
        if (this.memoryMapper != null) {
            memoryMapper!!.destroy()
        }
        if (rom != null) {
            rom!!.destroy()
        }
        if (gui != null) {
            gui!!.destroy()
        }

        if (this.cpu!!.isRunning) {
            stopEmulation()
        }

        gui = null
        cpu = null
        ppu = null
        papu = null
        this.cpuMemory = null
        this.ppuMemory = null
        this.sprMemory = null
        this.memoryMapper = null
        rom = null
        palTable = null
    }
}