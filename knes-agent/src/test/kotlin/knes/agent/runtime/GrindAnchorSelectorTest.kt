package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.OverworldMap
import knes.agent.perception.TileType
import java.lang.reflect.Constructor

/**
 * Builds a 256×256 OverworldMap from a tileId mapping. Tiles default to 0x17
 * (WATER, impassable) so any cell not explicitly populated is non-encounter.
 *
 * 0x00 = GRASS (per OverworldTileClassifier)
 * 0x03 = FOREST
 * 0x10 = MOUNTAIN
 * 0x17 = WATER (default fill)
 * 0x4A = TOWN
 */
private fun buildMap(populated: Map<Pair<Int, Int>, Int>): OverworldMap {
    val tiles = ByteArray(256 * 256) { 0x17.toByte() }
    for ((xy, id) in populated) {
        val (x, y) = xy
        tiles[y * 256 + x] = id.toByte()
    }
    val ctor: Constructor<OverworldMap> =
        OverworldMap::class.java.getDeclaredConstructor(ByteArray::class.java)
    ctor.isAccessible = true
    return ctor.newInstance(tiles)
}

class GrindAnchorSelectorTest : FunSpec({
    test("party on GRASS → anchor = party, no fallback") {
        val map = buildMap(mapOf(146 to 156 to 0x00))
        val pick = GrindAnchorSelector.pickGrindAnchor(146 to 156, map)
        pick.anchor shouldBe (146 to 156)
        pick.tileClass shouldBe TileType.GRASS
        pick.fellBack shouldBe false
    }

    test("party on FOREST → anchor = party, no fallback") {
        val map = buildMap(mapOf(146 to 156 to 0x03))
        val pick = GrindAnchorSelector.pickGrindAnchor(146 to 156, map)
        pick.anchor shouldBe (146 to 156)
        pick.tileClass shouldBe TileType.FOREST
        pick.fellBack shouldBe false
    }

    test("party on TOWN tile, GRASS one south → anchor shifts south") {
        val map = buildMap(mapOf(
            147 to 155 to 0x4A,  // party tile = TOWN
            147 to 156 to 0x00,  // south = GRASS
            147 to 154 to 0x00,  // north = GRASS (also valid — should lose tie-break)
        ))
        val pick = GrindAnchorSelector.pickGrindAnchor(147 to 155, map)
        pick.anchor shouldBe (147 to 156)
        pick.tileClass shouldBe TileType.GRASS
        pick.fellBack shouldBe false
    }

    test("party on TOWN tile, FOREST diagonally SE wins south+east tie-break vs NW GRASS") {
        val map = buildMap(mapOf(
            100 to 100 to 0x4A,                    // party = TOWN
            99 to 99 to 0x00,                      // NW grass (Manhattan 2)
            101 to 101 to 0x03,                    // SE forest (Manhattan 2) — should win
        ))
        val pick = GrindAnchorSelector.pickGrindAnchor(100 to 100, map)
        pick.anchor shouldBe (101 to 101)
        pick.tileClass shouldBe TileType.FOREST
        pick.fellBack shouldBe false
    }

    test("no encounter tile within radius → fellBack=true, anchor=party") {
        // Default fill is WATER; nothing populated near (50,50).
        val map = buildMap(mapOf(50 to 50 to 0x17))
        val pick = GrindAnchorSelector.pickGrindAnchor(50 to 50, map)
        pick.anchor shouldBe (50 to 50)
        pick.fellBack shouldBe true
    }
})
