package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class OverworldTerrainMemoryTest : FunSpec({
    test("records, queries, and persists tiles round-trip") {
        val tmp = Files.createTempFile("terrain", ".json").toFile().apply { deleteOnExit() }
        val mem = OverworldTerrainMemory(file = tmp)

        mem.record(146, 158, TileType.GRASS)
        mem.record(147, 154, TileType.TOWN)
        mem.record(146, 157, TileType.GRASS)

        mem.tileAt(146, 158) shouldBe TileType.GRASS
        mem.tileAt(147, 154) shouldBe TileType.TOWN
        mem.tileAt(999, 999) shouldBe null
        mem.seenCount() shouldBe 3
        mem.save()

        val reload = OverworldTerrainMemory(file = tmp)
        reload.tileAt(147, 154) shouldBe TileType.TOWN
        reload.seenCount() shouldBe 3
    }

    test("merge from ViewportMap returns count of newly added tiles") {
        val tmp = Files.createTempFile("terrain", ".json").toFile().apply { deleteOnExit() }
        val mem = OverworldTerrainMemory(file = tmp)

        // 2x2 viewport, party at (0,0), so localToWorld maps directly with partyWorld=(10,10)
        val tiles = arrayOf(
            arrayOf(TileType.GRASS, TileType.FOREST),
            arrayOf(TileType.WATER, TileType.UNKNOWN),  // UNKNOWN should be skipped
        )
        val vm = ViewportMap(tiles = tiles, partyLocalXY = 0 to 0, partyWorldXY = 10 to 10)

        val added1 = mem.merge(vm)
        added1 shouldBe 3   // UNKNOWN excluded

        val added2 = mem.merge(vm)
        added2 shouldBe 0   // already known

        mem.tileAt(10, 10) shouldBe TileType.GRASS
        mem.tileAt(11, 10) shouldBe TileType.FOREST
        mem.tileAt(10, 11) shouldBe TileType.WATER
    }
})
