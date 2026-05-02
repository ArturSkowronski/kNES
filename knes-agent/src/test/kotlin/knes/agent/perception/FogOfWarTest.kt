package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FogOfWarTest : FunSpec({
    test("merge adds new tiles from a viewport") {
        val fog = FogOfWar()
        val vm = ViewportMap.ofUnknown(partyWorldXY = 100 to 100)
        vm.tiles[8][8] = TileType.GRASS
        fog.merge(vm)
        fog.tileAt(100, 100) shouldBe TileType.GRASS
        fog.size shouldBe 1
    }

    test("merge overwrites previously seen tile (latest wins)") {
        val fog = FogOfWar()
        val vm1 = ViewportMap.ofUnknown(partyWorldXY = 50 to 50).also { it.tiles[8][8] = TileType.GRASS }
        val vm2 = ViewportMap.ofUnknown(partyWorldXY = 50 to 50).also { it.tiles[8][8] = TileType.MOUNTAIN }
        fog.merge(vm1)
        fog.merge(vm2)
        fog.tileAt(50, 50) shouldBe TileType.MOUNTAIN
    }

    test("clear empties state") {
        val fog = FogOfWar()
        fog.merge(ViewportMap.ofUnknown(0 to 0).also { it.tiles[8][8] = TileType.GRASS })
        fog.markBlocked(1, 1)
        fog.clear()
        fog.size shouldBe 0
        fog.isBlocked(1, 1) shouldBe false
    }

    test("blocked tile mark survives subsequent merge of same coord") {
        val fog = FogOfWar()
        fog.markBlocked(7, 7)
        fog.merge(ViewportMap.ofUnknown(7 to 7).also { it.tiles[8][8] = TileType.GRASS })
        fog.isBlocked(7, 7) shouldBe true
    }

    test("UNKNOWN tiles are not stored (preserve last real classification)") {
        val fog = FogOfWar()
        val vm1 = ViewportMap.ofUnknown(50 to 50).also { it.tiles[8][8] = TileType.GRASS }
        val vm2 = ViewportMap.ofUnknown(50 to 50)
        fog.merge(vm1)
        fog.merge(vm2)
        fog.tileAt(50, 50) shouldBe TileType.GRASS
    }

    test("bbox returns null for empty fog and rectangle when populated") {
        val fog = FogOfWar()
        fog.bbox() shouldBe null
        fog.merge(ViewportMap.ofUnknown(10 to 20).also { it.tiles[8][8] = TileType.GRASS })
        fog.merge(ViewportMap.ofUnknown(30 to 40).also { it.tiles[8][8] = TileType.GRASS })
        fog.bbox() shouldBe (10 to 20 to (30 to 40))
    }
})
