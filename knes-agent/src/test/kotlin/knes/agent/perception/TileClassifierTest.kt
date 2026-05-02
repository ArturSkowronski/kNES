package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TileClassifierTest : FunSpec({
    test("classifies a known grass id") {
        val table = TileClassificationTable(
            version = 1, rom = "test",
            byType = mapOf("GRASS" to listOf(0x00))
        )
        val c = TileClassifier(table)
        c.classify(0x00) shouldBe TileType.GRASS
    }

    test("unknown id maps to UNKNOWN and is impassable") {
        val table = TileClassificationTable(version = 1, rom = "test", byType = emptyMap())
        val c = TileClassifier(table)
        c.classify(0xFF) shouldBe TileType.UNKNOWN
        c.classify(0xFF).isPassable() shouldBe false
    }

    test("loads bundled ff1-overworld resource without error") {
        val c = TileClassifier.loadFromResources("ff1-overworld")
        (c.classify(c.knownIdsForType(TileType.GRASS).first()) == TileType.GRASS) shouldBe true
    }

    test("invalid JSON resource returns degraded all-UNKNOWN classifier") {
        val c = TileClassifier.loadFromResources("does-not-exist")
        c.classify(0x00) shouldBe TileType.UNKNOWN
        c.classify(0x42) shouldBe TileType.UNKNOWN
    }
})
