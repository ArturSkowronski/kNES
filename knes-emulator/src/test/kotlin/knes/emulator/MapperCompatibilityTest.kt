package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import knes.emulator.producers.MapperProducer
import knes.emulator.utils.HiResTimer
import java.io.File

private fun host(errors: MutableList<String> = mutableListOf()) = object : NesHost {
    override fun sendErrorMsg(message: String) { errors += message }
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0x40
    }
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
}

/**
 * A minimal but valid iNES file, so mapper handling can be tested without shipping
 * commercial ROMs. One 16 KiB PRG bank of zeroes is enough to load.
 */
private fun inesFile(mapper: Int, directory: File): File {
    val header = ByteArray(16)
    header[0] = 'N'.code.toByte()
    header[1] = 'E'.code.toByte()
    header[2] = 'S'.code.toByte()
    header[3] = 0x1A
    header[4] = 1 // one 16 KiB PRG bank
    header[5] = 0 // no CHR
    header[6] = ((mapper and 0x0F) shl 4).toByte()
    header[7] = (mapper and 0xF0).toByte()

    val file = File(directory, "mapper$mapper.nes")
    file.writeBytes(header + ByteArray(16384))
    return file
}

class MapperCompatibilityTest : FunSpec({

    test("the supported set and the factory agree") {
        // These used to be two lists — a when block and a membership check — which is
        // exactly how such a pair drifts apart.
        MapperProducer.SUPPORTED shouldBe setOf(MapperProducer.NROM, MapperProducer.MMC1)
        MapperProducer.isSupported(MapperProducer.NROM) shouldBe true
        MapperProducer.isSupported(MapperProducer.MMC1) shouldBe true
        MapperProducer.isSupported(4) shouldBe false
    }

    test("a supported mapper loads and says so") {
        val nes = NES(host(), NesConfig.HEADLESS)

        nes.loadRom(File("src/test/resources/nestest.nes").absolutePath) shouldBe true

        nes.isMapperSupported shouldBe true
        nes.romIdentity!!.mapperId shouldBe MapperProducer.NROM
    }

    test("an unsupported mapper loads on an NROM substitute, and admits it", ) {
        val errors = mutableListOf<String>()
        val nes = NES(host(errors), NesConfig.HEADLESS)
        val rom = inesFile(mapper = 4, directory = createTempDirectory())

        // Still true: the desktop UIs have always tolerated this, so the return value
        // is unchanged. What is new is being able to ask.
        nes.loadRom(rom.absolutePath) shouldBe true

        nes.isMapperSupported shouldBe false
        nes.romIdentity!!.mapperId shouldBe 4
        errors.any { it.contains("Mapper 4") && it.contains("not supported") } shouldBe true
    }

    test("loading a supported ROM after an unsupported one clears the flag") {
        val nes = NES(host(), NesConfig.HEADLESS)
        nes.loadRom(inesFile(mapper = 7, directory = createTempDirectory()).absolutePath)
        check(!nes.isMapperSupported)

        nes.loadRom(File("src/test/resources/nestest.nes").absolutePath)

        nes.isMapperSupported shouldBe true
    }
})

private fun createTempDirectory(): File =
    java.nio.file.Files.createTempDirectory("knes-mapper-test").toFile().apply { deleteOnExit() }
