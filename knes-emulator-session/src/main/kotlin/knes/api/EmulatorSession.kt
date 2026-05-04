package knes.api

import knes.emulator.ByteBuffer
import knes.emulator.NES
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Wraps a NES instance for API access.
 *
 * Two modes:
 * - **Standalone** (no args): creates its own headless NES. Full control: load ROM, step, input.
 * - **Shared** (pass existing NES): observes a NES driven by the Compose UI.
 *   Read-only: /state, /screen, /watch, /profiles work. /step is disabled.
 *   /press and /release still work (merged with keyboard input).
 */
class EmulatorSession(externalNes: NES? = null) {
    val controller = ApiController()

    var frameCount: Int = 0
        private set

    var romLoaded: Boolean = false
        private set

    val shared: Boolean = externalNes != null

    // Double buffer: writeBuffer receives new frames, readyBuffer is served to API.
    // Swap happens atomically in updateFrameBuffer/imageReady — no torn frames.
    private var writeBuffer = IntArray(256 * 240)
    @Volatile private var readyBuffer = IntArray(256 * 240)

    private var watchedAddresses: MutableMap<String, Int> = mutableMapOf()

    val nes: NES

    init {
        if (externalNes != null) {
            nes = externalNes
            romLoaded = externalNes.isRomLoaded
        } else {
            Globals.appletMode = true
            Globals.enableSound = false
            Globals.palEmulation = false
            Globals.timeEmulation = false

            val inputHandler = object : InputHandler {
                override fun getKeyState(padKey: Int): Short = controller.getKeyState(padKey)
            }

            val gui = object : GUI {
                override fun sendErrorMsg(message: String) {}
                override fun sendDebugMessage(message: String) {}
                override fun destroy() {}
                override fun getJoy1(): InputHandler = inputHandler
                override fun getJoy2(): InputHandler? = null
                override fun getTimer(): HiResTimer = HiResTimer()
                override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
                    System.arraycopy(buffer, 0, writeBuffer, 0, buffer.size)
                    readyBuffer = writeBuffer.also { writeBuffer = readyBuffer }
                    frameCount++
                }
            }

            nes = NES(gui)
        }
    }

    fun loadRom(path: String): Boolean {
        if (shared) return false
        romLoaded = try {
            nes.loadRom(path)
        } catch (e: Exception) {
            false
        }
        if (romLoaded) frameCount = 0
        return romLoaded
    }

    fun reset() {
        nes.reset()
        frameCount = 0
        controller.releaseAll()
    }

    fun advanceFrames(n: Int) {
        val target = frameCount + n
        if (shared) {
            // In shared mode, UI drives the CPU — wait for it to produce frames
            val deadlineMs = System.currentTimeMillis() + n * 50L + 5000L
            while (frameCount < target) {
                Thread.sleep(1)
                if (System.currentTimeMillis() > deadlineMs) {
                    throw IllegalStateException("advanceFrames($n) timed out waiting for UI (got ${frameCount - target + n}/$n frames)")
                }
            }
        } else {
            val maxSteps = n * 300_000
            var steps = 0
            var lastFrame = frameCount
            while (frameCount < target) {
                nes.cpu.step()
                if (frameCount != lastFrame) {
                    controller.onFrameBoundary()
                    lastFrame = frameCount
                }
                if (++steps > maxSteps) throw IllegalStateException("advanceFrames($n) timed out")
            }
        }
    }

    fun readMemory(addr: Int): Int = nes.cpuMemory.load(addr).toInt() and 0xFF

    /**
     * Serialize current emulator state (CPU regs + RAM, PPU memory + regs, OAM, mapper
     * banks). Wraps the vNES-inherited [NES.stateSave] which writes a versioned blob to
     * a [ByteBuffer]. Round-trips with [loadState]: the byte payload from save followed
     * by load on the same (or compatible) ROM yields a deterministic resume point.
     *
     * Use case: capture a known-good post-boot game state once, persist as a fixture,
     * and have tests load the fixture instead of replaying boot every time.
     */
    fun saveState(): ByteArray {
        if (!romLoaded) error("saveState requires ROM loaded")
        val buf = ByteBuffer(64 * 1024, ByteBuffer.BO_LITTLE_ENDIAN)
        buf.setExpandable(true)
        nes.stateSave(buf)
        return buf.getBytes()
    }

    /**
     * Restore a previously saved emulator state. ROM must already be loaded (and match
     * the ROM the snapshot was taken against — no version negotiation beyond the
     * single-byte version header in the snapshot itself). Returns true on successful
     * load.
     */
    fun loadState(bytes: ByteArray): Boolean {
        if (!romLoaded) error("loadState requires ROM loaded")
        val buf = ByteBuffer(bytes, ByteBuffer.BO_LITTLE_ENDIAN)
        return nes.stateLoad(buf)
    }

    /**
     * Reads a single tile index from one of the four PPU nametables.
     * @param ntIndex 0..3 (NES has 4 nametable slots, mirrored per cartridge config).
     * @param x 0..31 (tile column within the 32x30 nametable).
     * @param y 0..29 (tile row).
     * @return tile pattern index 0..255, or 0 if PPU not initialised.
     */
    fun readNametableTile(ntIndex: Int, x: Int, y: Int): Int {
        require(ntIndex in 0..3) { "nametable index $ntIndex out of range" }
        require(x in 0..31) { "x $x out of range" }
        require(y in 0..29) { "y $y out of range" }
        val nt = nes.ppu.nameTable.getOrNull(ntIndex) ?: return 0
        return nt.getTileIndex(x, y).toInt() and 0xFF
    }

    fun setWatchedAddresses(addresses: Map<String, Int>) {
        watchedAddresses.clear()
        watchedAddresses.putAll(addresses)
    }

    fun getWatchedState(): Map<String, Int> = watchedAddresses.mapValues { readMemory(it.value) }

    fun getScreenPng(): ByteArray {
        val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 256, 240, readyBuffer, 0, 256)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    fun getScreenBase64(): String = java.util.Base64.getEncoder().encodeToString(getScreenPng())

    /** Alias for toolset surface: returns watched RAM as name→value map. */
    fun readWatchedRam(): Map<String, Int> = getWatchedState()

    /** Returns CPU register snapshot as name→value map. */
    fun readCpuRegs(): Map<String, Int> = mapOf(
        "pc" to nes.cpu.REG_PC_NEW,
        "a"  to nes.cpu.REG_ACC_NEW,
        "x"  to nes.cpu.REG_X_NEW,
        "y"  to nes.cpu.REG_Y_NEW,
        "sp" to nes.cpu.REG_SP,
    )

    /** Alias for toolset surface: returns base64-encoded PNG of the current frame. */
    fun screenshotBase64Png(): String = getScreenBase64()

    /** Applies a [knes.debug.GameProfile]: sets watched addresses. */
    fun applyProfile(profile: knes.debug.GameProfile) {
        setWatchedAddresses(profile.toWatchMap())
    }

    /** Allow external frame buffer updates (used by Compose UI to feed frames to shared session) */
    fun updateFrameBuffer(buffer: IntArray) {
        System.arraycopy(buffer, 0, writeBuffer, 0, buffer.size)
        readyBuffer = writeBuffer.also { writeBuffer = readyBuffer }
        frameCount++
        romLoaded = nes.isRomLoaded
    }
}
