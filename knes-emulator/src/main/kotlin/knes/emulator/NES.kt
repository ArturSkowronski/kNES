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
import knes.emulator.utils.PaletteTable
import java.util.function.Consumer

class NES @JvmOverloads constructor(
    private val host: NesHost,
    /** Per-instance runtime flags. Defaults to the legacy [knes.emulator.utils.Globals] singleton. */
    val config: NesConfig = NesConfig.fromGlobals(),
) {

    val ppu: PPU = PPU()
    val papu: PAPU = PAPU(this)
    val cpu: CPU = CPU(papu, ppu)

    val palTable: PaletteTable = PaletteTable()

    val cpuMemory: Memory = Memory(0x10000) // Main memory (internal to CPU)
    val ppuMemory: Memory = Memory(0x8000) // VRAM memory (internal to PPU)
    val sprMemory: Memory = Memory(0x100) // Sprite RAM  (internal to PPU)

    var isRunning: Boolean = false
    var isRomLoaded: Boolean = false

    /**
     * Frames the PPU has completed since construction.
     *
     * Counted here rather than in whatever host happens to be attached, so a frame
     * boundary is observable without a host and without consulting [NesConfig.appletMode].
     */
    var frameCount: Long = 0L
        private set

    /** Called at each frame boundary, after the host has been handed the image. */
    var onFrame: ((Long) -> Unit)? = null

    var memoryMapper: MemoryMapper? = null

    val inputHandler: InputHandler = host.getJoy1()
    val inputHandler2: InputHandler? = host.getJoy2()

    init {
        cpu.init(cpuMemory, config)
        ppu.init(
            ::onImageReady,
            ppuMemory,
            sprMemory,
            cpuMemory,
            cpu,
            papu,
            palTable,
            config
        )

        papu.init(ChannelRegistryProducer())
        papu.irqRequester = cpu
        palTable.init()
        cpu.clearCPUMemory()
    }

    private fun onImageReady(skipFrame: Boolean, buffer: IntArray) {
        frameCount++
        host.imageReady(skipFrame, buffer)
        onFrame?.invoke(frameCount)
    }

    /**
     * Run exactly one CPU instruction.
     *
     * @return CPU cycles it consumed.
     */
    fun stepInstruction(): Int {
        cpu.step()
        return cpu.lastStepCycles
    }

    /**
     * Run whole instructions until at least [cycles] CPU cycles have passed.
     *
     * @return cycles actually consumed, which overshoots when the last instruction
     *   straddles the target — instructions are not divisible.
     */
    fun stepCpuCycles(cycles: Int): Int {
        var consumed = 0
        while (consumed < cycles) {
            consumed += stepInstruction()
        }
        return consumed
    }

    /**
     * Run until the PPU completes the next frame.
     *
     * Requires the CPU loop to be driving the PPU — see [NesConfig.appletMode], which
     * despite its name selects stepped execution rather than anything applet-specific.
     * Without it the PPU is clocked elsewhere, this would never return, and a clear
     * failure beats a hang.
     *
     * @return CPU cycles the frame took.
     */
    fun stepFrame(maxCycles: Int = MAX_CYCLES_PER_FRAME): Int {
        check(config.appletMode) {
            "stepFrame requires NesConfig.appletMode = true, which is what makes the CPU " +
                "loop clock the PPU; otherwise no frame boundary is ever reached here."
        }
        val target = frameCount + 1
        var consumed = 0
        while (frameCount < target) {
            consumed += stepInstruction()
            check(consumed <= maxCycles) {
                "stepFrame ran $consumed cycles without completing a frame (limit $maxCycles)"
            }
        }
        return consumed
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
            Consumer { percentComplete: Int? -> host.sendDebugMessage("Load Progress" + (percentComplete ?: 0)) },
            Consumer { message: String? -> host.sendErrorMsg(message!!) }
        )

        rom.load(file)

        if (rom.isValid()) {
            reset()
            val mapperProducer = MapperProducer(Consumer { message: String? -> host.sendErrorMsg(message!!) })
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
        cpu.init(cpuMemory, config)
        ppu.reset()
        palTable.reset()
        papu.reset(this)
    }

    fun beginExecution() {
        cpu.beginExecution()
    }

    companion object {
        /** An NTSC frame is ~29780 CPU cycles; the ceiling only exists to turn a hang into an error. */
        const val MAX_CYCLES_PER_FRAME: Int = 200_000
    }
}
