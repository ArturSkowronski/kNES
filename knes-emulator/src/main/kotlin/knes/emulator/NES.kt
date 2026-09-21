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

    @Volatile
    private var emulationThread: Thread? = null

    @Volatile
    private var stopRequested = false

    /**
     * Whether the emulation loop is running.
     *
     * One source of truth. There used to be two — a flag on `NES` and "is the CPU's
     * thread alive" — and `stateSave` consulted one while `stateLoad` consulted the
     * other.
     */
    val isRunning: Boolean get() = emulationThread?.isAlive == true

    var isRomLoaded: Boolean = false

    /**
     * Frames the PPU has completed since construction.
     *
     * Counted here rather than in whatever host happens to be attached, so a frame
     * boundary is observable without a host and without consulting the execution mode.
     */
    var frameCount: Long = 0L
        private set

    /** Called at each frame boundary, after the host has been handed the image. */
    var onFrame: ((Long) -> Unit)? = null

    /**
     * Records executed instructions when set. Null — the default — costs nothing.
     *
     * Only [stepInstruction] and what builds on it feed this; free-running emulation
     * does not, because the check does not belong in the CPU's hot loop.
     */
    var trace: InstructionTrace? = null

    /** Program counters [runUntilStop] stops at. */
    val breakpoints: MutableSet<Int> = mutableSetOf()

    /**
     * RAM addresses whose value changing stops [runUntilStop].
     *
     * Two deliberate limits. They fire on a **change**, not on a write, because they are
     * sampled between instructions rather than hooked into the CPU's write path — a
     * store of the value already there goes unnoticed. And they are restricted to RAM:
     * reading a register like `$2002` has side effects, so sampling one every
     * instruction would change the behaviour being observed.
     *
     * Add through [watch], which enforces the range.
     */
    val watchpoints: MutableSet<Int> = mutableSetOf()

    private val watched = mutableMapOf<Int, Int>()

    /** Start watching [address] for changes. Only RAM below `$2000` can be watched. */
    fun watch(address: Int) {
        require(address in 0 until RAM_END) {
            "only RAM below ${"$%04X".format(RAM_END)} can be watched; reading " +
                "${"$%04X".format(address)} would have side effects"
        }
        watchpoints += address
        watched[address] = readByte(address)
    }

    fun unwatch(address: Int) {
        watchpoints -= address
        watched -= address
    }

    /** A byte as the CPU sees it. Side-effect free for RAM; above that it goes through the mapper. */
    fun readByte(address: Int): Int {
        val wrapped = address and 0xFFFF
        return if (wrapped < RAM_END) {
            cpuMemory.mem[wrapped and 0x7FF].toInt() and 0xFF
        } else {
            (memoryMapper?.load(wrapped)?.toInt() ?: cpuMemory.load(wrapped).toInt()) and 0xFF
        }
    }

    /**
     * The address the next instruction will be fetched from.
     *
     * The CPU stores this internally as `REG_PC_NEW`, trailing the real counter by one;
     * every caller that got that wrong read the wrong instruction.
     */
    val programCounter: Int get() = (cpu.REG_PC_NEW + 1) and 0xFFFF

    var memoryMapper: MemoryMapper? = null

    /** Identity of the ROM currently loaded, for checking a savestate belongs here. */
    var romIdentity: RomIdentity? = null
        private set

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
        val recorder = trace
        val pc = if (recorder != null) programCounter else 0
        val opcode = if (recorder != null) opcodeAt(pc) else 0
        cpu.step()
        val cycles = cpu.lastStepCycles
        recorder?.record(pc, opcode, cycles)
        return cycles
    }

    /**
     * Point execution at [address].
     *
     * Drains a pending interrupt first. Straight after a reset one is queued, and it is
     * serviced *before* the next instruction — so assigning the program counter by hand
     * and stepping sends the CPU to the reset vector instead, silently discarding the
     * address. A test harness that did exactly that is why nestest's automated mode
     * appeared unreachable for months.
     */
    fun jumpTo(address: Int) {
        if (cpu.irqRequested) stepInstruction()
        cpu.REG_PC_NEW = (address - 1) and 0xFFFF
    }

    /**
     * The opcode byte at [address] as the CPU would fetch it — through the mapper, not
     * out of raw CPU memory. Above `$2000` those are different views, and reading the
     * wrong one is how a debugger ends up describing an instruction that never ran.
     */
    fun opcodeAt(address: Int): Int =
        (memoryMapper?.load(address and 0xFFFF)?.toInt() ?: cpuMemory.load(address and 0xFFFF).toInt()) and 0xFF

    /**
     * Run instructions until a breakpoint or watchpoint fires, or until
     * [maxInstructions] have run.
     *
     * @return what stopped it, or null if the budget ran out first.
     */
    fun runUntilStop(maxInstructions: Int = DEFAULT_BREAKPOINT_BUDGET): DebugStop? {
        repeat(maxInstructions) {
            stepInstruction()
            for (address in watchpoints) {
                val now = readByte(address)
                val before = watched.put(address, now)
                if (before != null && before != now) {
                    return DebugStop.ValueChanged(address, before, now, programCounter)
                }
            }
            val pc = programCounter
            if (pc in breakpoints) return DebugStop.Breakpoint(pc)
        }
        return null
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
     * Requires [NesConfig.steppedExecution]: without it the PPU is clocked elsewhere,
     * this would never return, and a clear failure beats a hang.
     *
     * @return CPU cycles the frame took.
     */
    fun stepFrame(maxCycles: Int = MAX_CYCLES_PER_FRAME): Int {
        check(config.steppedExecution) {
            "stepFrame requires NesConfig.steppedExecution = true, which is what makes the " +
                "CPU loop clock the PPU; otherwise no frame boundary is ever reached here."
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

    /**
     * Restore a savestate.
     *
     * Reads both the current format and the original positional one, told apart by the
     * magic. A state from a different ROM raises [SavestateMismatchException] rather
     * than quietly restoring a machine it does not describe; malformed data returns
     * false, as before.
     */
    fun stateLoad(buf: ByteBuffer): Boolean {
        var continueEmulation = false
        if (isRunning) {
            continueEmulation = true
            stopEmulation()
        }

        val start = buf.getPos()
        val magic = runCatching { buf.readStringAscii(Savestate.MAGIC.length) }.getOrNull()
        val success = if (magic == Savestate.MAGIC) {
            loadVersioned(buf)
        } else {
            buf.goTo(start)
            loadLegacy(buf)
        }

        if (continueEmulation) {
            startEmulation()
        }
        return success
    }

    private fun loadVersioned(buf: ByteBuffer): Boolean {
        if (buf.readInt() != Savestate.VERSION) return false

        val stored = RomIdentity(
            mapperId = buf.readInt(),
            prgBanks = buf.readInt(),
            chrBanks = buf.readInt(),
            mirroring = buf.readInt(),
            contentHash = buf.readInt(),
        )
        val current = romIdentity
        if (current != null && stored != current) {
            throw SavestateMismatchException(
                "savestate belongs to a different ROM: state=$stored, loaded=$current"
            )
        }

        val chunks = buf.readInt()
        repeat(chunks) {
            val name = buf.readStringAscii(buf.readInt())
            val length = buf.readInt()
            val end = buf.getPos() + length
            when (name) {
                Savestate.CHUNK_CPU_RAM -> cpuMemory.stateLoad(buf)
                Savestate.CHUNK_PPU_RAM -> ppuMemory.stateLoad(buf)
                Savestate.CHUNK_SPR_RAM -> sprMemory.stateLoad(buf)
                Savestate.CHUNK_CPU -> cpu.stateLoad(buf)
                Savestate.CHUNK_MAPPER -> memoryMapper?.stateLoad(buf)
                Savestate.CHUNK_PPU -> ppu.stateLoad(buf)
                // Unknown chunk from a newer writer: its length lets us step over it.
            }
            buf.goTo(end)
        }
        return true
    }

    private fun loadLegacy(buf: ByteBuffer): Boolean {
        if (buf.readByte().toInt() != Savestate.LEGACY_VERSION) return false
        cpuMemory.stateLoad(buf)
        ppuMemory.stateLoad(buf)
        sprMemory.stateLoad(buf)
        cpu.stateLoad(buf)
        memoryMapper?.stateLoad(buf)
        ppu.stateLoad(buf)
        return true
    }

    /**
     * Write a savestate in the current format: magic, version, ROM identity, then
     * length-prefixed named chunks so a reader can skip what it does not recognise.
     */
    fun stateSave(buf: ByteBuffer) {
        val continueEmulation = this.isRunning
        stopEmulation()

        buf.putStringAscii(Savestate.MAGIC)
        buf.putInt(Savestate.VERSION)

        val identity = romIdentity ?: RomIdentity(0, 0, 0, 0, 0)
        buf.putInt(identity.mapperId)
        buf.putInt(identity.prgBanks)
        buf.putInt(identity.chrBanks)
        buf.putInt(identity.mirroring)
        buf.putInt(identity.contentHash)

        val chunks = listOf<Pair<String, () -> Unit>>(
            Savestate.CHUNK_CPU_RAM to { cpuMemory.stateSave(buf) },
            Savestate.CHUNK_PPU_RAM to { ppuMemory.stateSave(buf) },
            Savestate.CHUNK_SPR_RAM to { sprMemory.stateSave(buf) },
            Savestate.CHUNK_CPU to { cpu.stateSave(buf) },
            Savestate.CHUNK_MAPPER to { memoryMapper?.stateSave(buf); Unit },
            Savestate.CHUNK_PPU to { ppu.stateSave(buf) },
        )
        buf.putInt(chunks.size)
        for ((name, write) in chunks) {
            buf.putInt(name.length)
            buf.putStringAscii(name)
            val lengthPos = buf.getPos()
            buf.putInt(0) // patched once the chunk's size is known
            val bodyStart = buf.getPos()
            write()
            val end = buf.getPos()
            buf.putInt(end - bodyStart, lengthPos)
            buf.goTo(end)
        }

        if (continueEmulation) {
            startEmulation()
        }
    }

    /**
     * Run the console until [stopEmulation].
     *
     * The loop lives here rather than inside `CPU.emulate`, so there is one execution
     * model: free-running is [stepInstruction] called repeatedly, which is exactly what
     * stepped execution already was. Measured at ~34M instructions/second against the
     * ~0.9M a real NES needs, so owning the loop out here costs nothing that matters.
     */
    fun startEmulation() {
        if (!papu.isRunning) {
            papu.start()
        }
        if (!isRomLoaded || isRunning) return

        stopRequested = false
        emulationThread = Thread(::runLoop, "knes-emulation").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stopEmulation() {
        stopRequested = true
        emulationThread?.let { thread ->
            if (thread !== Thread.currentThread()) {
                thread.join(STOP_TIMEOUT_MS)
            }
        }
        emulationThread = null

        if (papu.isRunning) {
            papu.stop()
        }
    }

    private fun runLoop() {
        while (!stopRequested) {
            stepInstruction()
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
            romIdentity = RomIdentity(
                mapperId = rom.mapperType,
                prgBanks = rom.romCount,
                chrBanks = rom.vromCount,
                mirroring = rom.mirroring,
                contentHash = RomIdentity.hashPrg(rom.rom),
            )
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

    /** Kept for the applet, which starts the console without going through [startEmulation]. */
    fun beginExecution() {
        startEmulation()
    }

    companion object {
        /** An NTSC frame is ~29780 CPU cycles; the ceiling only exists to turn a hang into an error. */
        const val MAX_CYCLES_PER_FRAME: Int = 200_000

        /** Roughly a second of CPU time — enough to reach anything a breakpoint is useful for. */
        const val DEFAULT_BREAKPOINT_BUDGET: Int = 1_000_000

        /** Everything below this is RAM, and reading it has no side effects. */
        const val RAM_END: Int = 0x2000

        /** Long enough for an instruction to finish; a stuck loop should not wedge a shutdown. */
        private const val STOP_TIMEOUT_MS: Long = 2_000
    }
}
