package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import knes.emulator.producers.MapperProducer
import knes.emulator.utils.HiResTimer
import java.io.File

/**
 * Compatibility against commercial ROMs, tracked by name and expected result.
 *
 * These ROMs cannot be redistributed, so they live in the gitignored `roms/` directory
 * and this suite **skips itself** when they are absent — CI stays green, a developer with
 * the files gets the coverage. That is the separation backlog task E2 asks for.
 *
 * What it buys: `nestest` is NROM, 16 KiB, no bank switching at all. Final Fantasy is
 * MMC1 with sixteen PRG banks, so booting it at all is proof the mapper works. Nothing
 * else in the suite exercises that.
 */
private data class RomExpectation(
    val file: String,
    val mapper: Int,
    val prgBanks: Int,
    /** A RAM address and the value the game is known to write there once booted. */
    val bootMarker: Pair<Int, Int>,
    val markerMeaning: String,
)

private val EXPECTED = listOf(
    RomExpectation(
        file = "ff.nes",
        mapper = MapperProducer.MMC1,
        prgBanks = 16,
        // The FF1 profile documents $00F9 = 0x4D as "warm boot, skips intro".
        bootMarker = 0x00F9 to 0x4D,
        markerMeaning = "FF1's boot flag reaches its warm-boot value",
    ),
)

private fun romsDirectory(): File? {
    var dir: File? = File(System.getProperty("user.dir"))
    repeat(4) {
        val candidate = dir?.let { File(it, "roms") }
        if (candidate != null && candidate.isDirectory) return candidate
        dir = dir?.parentFile
    }
    return null
}

private fun host() = object : NesHost {
    override fun sendErrorMsg(message: String) {}
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0x40
    }
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
}

private val held = HashSet<Int>()

class CommercialRomCompatibilityTest : FunSpec({

    val roms = romsDirectory()

    for (expected in EXPECTED) {
        val rom = roms?.let { File(it, expected.file) }
        val available = rom != null && rom.isFile

        test("${expected.file}: loads on mapper ${expected.mapper} and ${expected.markerMeaning}")
            .config(enabled = available) {
                val nes = NES(host(), NesConfig.HEADLESS)

                nes.loadRom(rom!!.absolutePath) shouldBe true
                nes.isMapperSupported shouldBe true

                val identity = nes.romIdentity!!
                identity.mapperId shouldBe expected.mapper
                identity.prgBanks shouldBe expected.prgBanks
                // Sixteen 16 KiB banks is 256 KiB against a 32 KiB CPU window, so the
                // game cannot reach its own boot code without the mapper switching banks.
                identity.prgBanks shouldBeGreaterThan 2

                repeat(120) { nes.stepFrame() }

                val (address, value) = expected.bootMarker
                nes.readByte(address) shouldBe value
            }

        test("${expected.file}: boots into a machine that is actually running")
            .config(enabled = available) {
                val nes = NES(host(), NesConfig.HEADLESS)
                check(nes.loadRom(rom!!.absolutePath))

                repeat(120) { nes.stepFrame() }

                // Guards the assertion above: a marker value means nothing if the game
                // never ran and RAM is whatever reset left behind.
                (0 until 0x0800).count { nes.readByte(it) != 0 } shouldBeGreaterThan 100
                nes.frameCount shouldBe 120L
            }
    }

    val ff1 = roms?.let { File(it, "ff.nes") }
    val ff1Available = ff1 != null && ff1.isFile

    test("the PPU renders a real picture once a game is running")
        .config(enabled = ff1Available) {
            // Rendering had no coverage at all: every `imageReady` in this repo's tests
            // is an empty lambda, so a regression that blanked the screen would not have
            // failed anything. nestest is no use here — it draws two colours — so this
            // needs a commercial ROM.
            var latest: IntArray? = null
            var frames = 0
            val recordingHost = object : NesHost {
                override fun sendErrorMsg(message: String) {}
                override fun sendDebugMessage(message: String) {}
                override fun destroy() {}
                override fun getJoy1(): InputHandler = object : InputHandler {
                    override fun getKeyState(padKey: Int): Short = if (padKey in held) 0x41 else 0x40
                }
                override fun getJoy2(): InputHandler? = null
                override fun getTimer(): HiResTimer = HiResTimer()
                override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
                    latest = buffer.copyOf()
                    frames++
                }
            }

            val nes = NES(recordingHost, NesConfig.HEADLESS)
            check(nes.loadRom(ff1!!.absolutePath))

            fun runFrames(count: Int) {
                val target = frames + count
                var guard = 0
                while (frames < target && guard++ < count * 400_000) nes.stepInstruction()
            }

            runFrames(60)
            val blank = latest!!.map { it and 0xFFFFFF }.toSet()

            // Boot the game: alternate START and A through NEW GAME, class select, names.
            repeat(70) { round ->
                held.clear()
                held += if (round % 2 == 0) InputHandler.KEY_START else InputHandler.KEY_A
                runFrames(6)
                held.clear()
                runFrames(14)
            }

            val playing = latest!!.map { it and 0xFFFFFF }.toSet()

            // The boot screen is near-blank; a running game is not.
            blank.size shouldBe 2
            playing.size shouldBeGreaterThan blank.size
            playing.size shouldBeGreaterThan 5
        }

    test("the suite reports whether it ran against real ROMs") {
        // Not an assertion about the emulator — a note in the output so a green run
        // cannot be mistaken for coverage that did not happen.
        val found = EXPECTED.count { roms?.let { dir -> File(dir, it.file).isFile } == true }
        println("commercial ROM compatibility: ${found}/${EXPECTED.size} ROMs available in ${roms?.path ?: "(no roms directory)"}")
        true shouldBe true
    }
})
