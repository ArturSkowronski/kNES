package knes.agent.perception

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The overworld decoder is Final Fantasy's, and only Final Fantasy's.
 *
 * It reads a pointer table at a fixed offset in a fixed bank. Pointed at another game's
 * cartridge those two bytes are whatever that game keeps there, and the first row resolves
 * to a negative file offset — which is exactly how a Super Mario Bros run died in `Main`,
 * three lines after the ROM path was read and long before the emulator started.
 */
class OverworldMapForProfileTest : FunSpec({

    test("a game this cannot decode gets a blank map, and its ROM is never opened") {
        // The file does not exist: reaching for it at all would throw.
        val missing = File("/nonexistent/smb.nes")
        OverworldMap.forProfile("smb", missing).tiles.size shouldBe 256 * 256
        OverworldMap.forProfile("smb", missing).tiles.all { it == 0.toByte() } shouldBe true
        OverworldMap.forProfile(null, missing).tiles.size shouldBe 256 * 256
    }

    test("the profile match ignores case, the way every other profile lookup does") {
        // Would try to read the file, which is the point: it took the decoding branch.
        shouldThrow<Exception> { OverworldMap.forProfile("FF1", File("/nonexistent/ff.nes")) }
    }

    test("another game's ROM is refused rather than decoded into nonsense") {
        // 32 KB of PRG whose pointer table is zeroes: row 0 resolves below the bank.
        shouldThrow<IllegalArgumentException> { OverworldMap.fromRom(ByteArray(0x8010)) }
    }

    test("a ROM too small to hold a bank is refused before any pointer is read") {
        shouldThrow<IllegalArgumentException> { OverworldMap.fromRom(ByteArray(1024)) }
    }
})
