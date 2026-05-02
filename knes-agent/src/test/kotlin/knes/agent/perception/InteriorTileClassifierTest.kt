package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InteriorTileClassifierTest : FunSpec({
    test("0x31 (primary floor) classifies as GRASS — passable") {
        InteriorTileClassifier.classify(0x31) shouldBe TileType.GRASS
        TileType.GRASS.isPassable() shouldBe true
    }

    test("0x21 (secondary floor) classifies as GRASS") {
        InteriorTileClassifier.classify(0x21) shouldBe TileType.GRASS
    }

    test("0x30 (wall) classifies as MOUNTAIN — impassable") {
        InteriorTileClassifier.classify(0x30) shouldBe TileType.MOUNTAIN
        TileType.MOUNTAIN.isPassable() shouldBe false
    }

    test("0x39 (outside-map padding) classifies as UNKNOWN — impassable") {
        InteriorTileClassifier.classify(0x39) shouldBe TileType.UNKNOWN
        TileType.UNKNOWN.isPassable() shouldBe false
    }

    test("0x44 (isolated tile in floor) classifies as STAIRS") {
        InteriorTileClassifier.classify(0x44) shouldBe TileType.STAIRS
    }

    test("furniture/decoration ids in 0x00..0x1F classify as GRASS") {
        for (id in 0x00..0x1F) {
            InteriorTileClassifier.classify(id) shouldBe TileType.GRASS
        }
    }

    test("0xFF (invalid) classifies as UNKNOWN") {
        InteriorTileClassifier.classify(0xFF) shouldBe TileType.UNKNOWN
    }
})
