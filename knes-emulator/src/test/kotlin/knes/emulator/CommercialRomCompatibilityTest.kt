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

    test("the suite reports whether it ran against real ROMs") {
        // Not an assertion about the emulator — a note in the output so a green run
        // cannot be mistaken for coverage that did not happen.
        val found = EXPECTED.count { roms?.let { dir -> File(dir, it.file).isFile } == true }
        println("commercial ROM compatibility: ${found}/${EXPECTED.size} ROMs available in ${roms?.path ?: "(no roms directory)"}")
        true shouldBe true
    }
})
