package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import java.io.File

class OverworldMapTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("decodes FF1 ROM into 256x256 grid").config(enabled = romPresent) {
        val map = OverworldMap.fromRom(File(romPath))
        // Coneria spawn area: assert SOME grass exists within 4 tiles of (0x92, 0x9E).
        var grassNear = 0
        for (dy in -4..4) for (dx in -4..4) {
            if (map.classifyAt(0x92 + dx, 0x9E + dy) == TileType.GRASS) grassNear++
        }
        (grassNear >= 5) shouldBe true

        // Some non-grass tiles must exist somewhere on the map (sanity).
        val seenTypes = mutableSetOf<TileType>()
        for (y in 0 until 256) for (x in 0 until 256) {
            seenTypes += map.classifyAt(x, y)
            if (seenTypes.size >= 5) break
        }
        seenTypes shouldContain TileType.GRASS
        seenTypes shouldContain TileType.MOUNTAIN
        seenTypes shouldContain TileType.WATER
        // FOREST or FOREST-equivalent must show up too.
        (TileType.FOREST in seenTypes) shouldBe true
    }

    test("Coneria castle and town exist near spawn (146, 158)").config(enabled = romPresent) {
        val map = OverworldMap.fromRom(File(romPath))
        // Coneria castle is roughly north of party spawn. Coneria town slightly NE.
        // Search a bounding box and assert at least one CASTLE tile and one TOWN tile.
        var castles = 0
        var towns = 0
        for (y in 140..165) for (x in 130..160) {
            when (map.classifyAt(x, y)) {
                TileType.CASTLE -> castles++
                TileType.TOWN -> towns++
                else -> {}
            }
        }
        // Coneria castle is multiple tiles big.
        (castles >= 1) shouldBe true
        (towns >= 1) shouldBe true
    }

    test("readViewport centers party with 16x16 grid").config(enabled = romPresent) {
        val map = OverworldMap.fromRom(File(romPath))
        val vp = map.readViewport(partyWorldXY = 0x92 to 0x9E)
        vp.width shouldBe 16
        vp.height shouldBe 16
        vp.partyLocalXY shouldBe (8 to 8)
        vp.partyWorldXY shouldBe (0x92 to 0x9E)
        // Center tile classification at (0x92, 0x9E) — must match map.classifyAt directly.
        vp.tiles[8][8] shouldBe map.classifyAt(0x92, 0x9E)
    }

    test("tileAt wraps coordinates modulo 256").config(enabled = romPresent) {
        val map = OverworldMap.fromRom(File(romPath))
        map.tileAt(0, 0) shouldBe map.tileAt(256, 256)
        map.tileAt(-1, -1) shouldBe map.tileAt(255, 255)
    }

    test("OverworldTileClassifier classifies known tile bytes") {
        OverworldTileClassifier.classify(0x00) shouldBe TileType.GRASS
        OverworldTileClassifier.classify(0x10) shouldBe TileType.MOUNTAIN
        OverworldTileClassifier.classify(0x17) shouldBe TileType.WATER
        OverworldTileClassifier.classify(0x03) shouldBe TileType.FOREST
        OverworldTileClassifier.classify(0x4A) shouldBe TileType.TOWN
        OverworldTileClassifier.classify(0x47) shouldBe TileType.CASTLE
        OverworldTileClassifier.classify(0x46) shouldBe TileType.BRIDGE
        OverworldTileClassifier.classify(0xFF) shouldBe TileType.UNKNOWN
    }
})
