package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InteriorMapLoaderTest : FunSpec({
    test("decodes a simple RLE map (single 1-byte tiles)") {
        val rom = ByteArray(0x10010 + 0x4000)
        rom[0x10010] = 0x00
        rom[0x10011] = 0x02
        rom[0x10210] = 0x05
        rom[0x10211] = 0x06
        rom[0x10212] = 0x07
        rom[0x10213] = 0x08
        rom[0x10214] = 0xFF.toByte()
        val map = InteriorMapLoader(rom).load(mapId = 0)
        map.width shouldBe 64
        map.height shouldBe 64
        map.tileAt(0, 0) shouldBe 0x05
        map.tileAt(1, 0) shouldBe 0x06
        map.tileAt(2, 0) shouldBe 0x07
        map.tileAt(3, 0) shouldBe 0x08
        map.tileAt(4, 0) shouldBe 0x00
    }

    test("decodes 2-byte RLE entry with repeat count") {
        val rom = ByteArray(0x10010 + 0x4000)
        rom[0x10010] = 0x00; rom[0x10011] = 0x02
        rom[0x10210] = 0x83.toByte()
        rom[0x10211] = 0x05
        rom[0x10212] = 0xFF.toByte()
        val map = InteriorMapLoader(rom).load(0)
        for (i in 0 until 5) map.tileAt(i, 0) shouldBe 0x03
        map.tileAt(5, 0) shouldBe 0x00
    }

    test("2-byte entry wraps row boundary") {
        val rom = ByteArray(0x10010 + 0x4000)
        rom[0x10010] = 0x00; rom[0x10011] = 0x02
        var off = 0x10210
        for (i in 0 until 62) { rom[off++] = 0x01 }
        rom[off++] = 0x89.toByte()
        rom[off++] = 0x04
        rom[off++] = 0xFF.toByte()
        val map = InteriorMapLoader(rom).load(0)
        map.tileAt(61, 0) shouldBe 0x01
        map.tileAt(62, 0) shouldBe 0x09
        map.tileAt(63, 0) shouldBe 0x09
        map.tileAt(0, 1) shouldBe 0x09
        map.tileAt(1, 1) shouldBe 0x09
        map.tileAt(2, 1) shouldBe 0x00
    }

    test("respects bank index in upper 2 bits of pointer") {
        val rom = ByteArray(0x10010 + 4 * 0x4000)
        rom[0x10010] = 0x00; rom[0x10011] = 0x40
        rom[0x14010] = 0x42
        rom[0x14011] = 0xFF.toByte()
        val map = InteriorMapLoader(rom).load(0)
        map.tileAt(0, 0) shouldBe 0x42
    }

    test("rejects out-of-range mapId") {
        val rom = ByteArray(0x10010 + 0x4000)
        try {
            InteriorMapLoader(rom).load(-1)
            error("expected throw")
        } catch (_: IllegalArgumentException) { /* ok */ }
    }
})
