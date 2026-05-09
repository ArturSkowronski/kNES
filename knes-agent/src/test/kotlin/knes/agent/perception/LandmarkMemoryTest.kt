package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class LandmarkMemoryTest : FunSpec({
    test("record + findByKind + atLocation + markVisited round-trip") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)

        val town = Landmark(id = "coneria_town_entry_147_154", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 147, worldY = 154, mapIdInterior = 8)
        val king = Landmark(id = "king", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 14, localY = 4, note = "throne")
        mem.record(town); mem.record(king)

        mem.findByKind(LandmarkKind.TOWN_ENTRY) shouldHaveSize 1
        mem.findByKind(LandmarkKind.NPC_KING) shouldHaveSize 1
        mem.atLocation(147, 154).shouldNotBeNull().id shouldBe "coneria_town_entry_147_154"
        mem.atLocation(0, 0).shouldBeNull()

        mem.markVisited("king")
        mem.findByKind(LandmarkKind.NPC_KING).first().visited shouldBe true

        mem.save()
        val reload = LandmarkMemory(file = tmp)
        reload.findByKind(LandmarkKind.NPC_KING).first().visited shouldBe true
        reload.findByKind(LandmarkKind.TOWN_ENTRY).first().visited shouldBe false
    }

    test("recordIfNew is idempotent on (kind, worldX, worldY)") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        mem.recordIfNew(Landmark(id = "a", kind = LandmarkKind.TOWN_ENTRY, worldX = 1, worldY = 2))
        mem.recordIfNew(Landmark(id = "b", kind = LandmarkKind.TOWN_ENTRY, worldX = 1, worldY = 2))
        mem.findByKind(LandmarkKind.TOWN_ENTRY) shouldHaveSize 1
    }

    test("recordIfNew dedupes interior landmarks on (kind, mapId, localX, localY)") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        mem.recordIfNew(Landmark(id = "k1", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 14, localY = 4)) shouldBe true
        mem.recordIfNew(Landmark(id = "k2", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 14, localY = 4)) shouldBe false  // duplicate interior coords
        mem.findByKind(LandmarkKind.NPC_KING) shouldHaveSize 1
    }

    test("recordIfNew does NOT dedupe coord-less landmarks (treated as distinct)") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        mem.recordIfNew(Landmark(id = "g1", kind = LandmarkKind.NPC_GENERIC)) shouldBe true
        mem.recordIfNew(Landmark(id = "g2", kind = LandmarkKind.NPC_GENERIC)) shouldBe true
        mem.findByKind(LandmarkKind.NPC_GENERIC) shouldHaveSize 2
    }

    test("recordIfNew upgrades existing record with stronger info on duplicate (visited + mapIdInterior)") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        // First: SalienceStrategy auto-records a tile-tagged candidate (no entry yet).
        mem.recordIfNew(Landmark(id = "tagged_147_154", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 147, worldY = 154, visited = false)) shouldBe true
        // Then: handleNewInterior records the confirmed entry at the same coords.
        mem.recordIfNew(Landmark(id = "interior_entry_8_147_154", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 147, worldY = 154, mapIdInterior = 8, visited = true,
            note = "first entry", discoveredRunId = "run-1")) shouldBe false  // dedup'd
        // The existing record must now reflect visited=true, mapIdInterior=8, note + runId filled.
        mem.findByKind(LandmarkKind.TOWN_ENTRY) shouldHaveSize 1
        val merged = mem.findByKind(LandmarkKind.TOWN_ENTRY).first()
        merged.visited shouldBe true
        merged.mapIdInterior shouldBe 8
        merged.note shouldBe "first entry"
        merged.discoveredRunId shouldBe "run-1"
    }

    test("removeTileTaggedAt drops only tile-tagged candidates at coords (mapIdInterior == null)") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        // tile-tagged castle decoration misclassified at (146,150)
        mem.record(Landmark(id = "tagged_146_150", kind = LandmarkKind.CASTLE_ENTRY,
            worldX = 146, worldY = 150, visited = false))
        // confirmed entry at SAME coords — must NOT be removed
        mem.record(Landmark(id = "confirmed_146_150", kind = LandmarkKind.CASTLE_ENTRY,
            worldX = 146, worldY = 150, mapIdInterior = 1, visited = true))
        // tile-tagged at different coords — must NOT be removed
        mem.record(Landmark(id = "tagged_147_154", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 147, worldY = 154, visited = false))

        mem.removeTileTaggedAt(146, 150) shouldBe true
        // confirmed remains; tagged elsewhere remains
        mem.findByKind(LandmarkKind.CASTLE_ENTRY) shouldHaveSize 1
        mem.findByKind(LandmarkKind.CASTLE_ENTRY).first().mapIdInterior shouldBe 1
        mem.findByKind(LandmarkKind.TOWN_ENTRY) shouldHaveSize 1
    }

    test("removeTileTaggedAt returns false when nothing matches") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        mem.record(Landmark(id = "t", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 100, worldY = 100, mapIdInterior = 8))
        mem.removeTileTaggedAt(100, 100) shouldBe false  // confirmed, not tile-tagged
        mem.removeTileTaggedAt(0, 0) shouldBe false       // nothing at coords
        mem.findByKind(LandmarkKind.TOWN_ENTRY) shouldHaveSize 1
    }

    test("new landmark kinds CHEST + SIGN + DIALOGUE_TRIGGER serialize and deserialize") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        val kinds = listOf(LandmarkKind.CHEST, LandmarkKind.SIGN, LandmarkKind.DIALOGUE_TRIGGER)
        kinds.forEachIndexed { i, k ->
            mem.recordIfNew(Landmark(id = "lm_$i", kind = k,
                mapId = 8, localX = i, localY = i, note = "kind=$k")) shouldBe true
        }
        mem.save()

        val reloaded = LandmarkMemory(file = tmp)
        val total = reloaded.findByKind(LandmarkKind.CHEST).size +
            reloaded.findByKind(LandmarkKind.SIGN).size +
            reloaded.findByKind(LandmarkKind.DIALOGUE_TRIGGER).size
        total shouldBe 3
        reloaded.findByKind(LandmarkKind.CHEST) shouldHaveSize 1
        reloaded.findByKind(LandmarkKind.SIGN) shouldHaveSize 1
        reloaded.findByKind(LandmarkKind.DIALOGUE_TRIGGER) shouldHaveSize 1
    }

    test("recordIfNew upgrade is monotonic — never weakens visited or unsets mapIdInterior") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        mem.recordIfNew(Landmark(id = "strong", kind = LandmarkKind.CASTLE_ENTRY,
            worldX = 152, worldY = 151, mapIdInterior = 1, visited = true, note = "throne"))
        mem.recordIfNew(Landmark(id = "weaker", kind = LandmarkKind.CASTLE_ENTRY,
            worldX = 152, worldY = 151, mapIdInterior = null, visited = false, note = ""))
        val res = mem.findByKind(LandmarkKind.CASTLE_ENTRY).first()
        res.visited shouldBe true
        res.mapIdInterior shouldBe 1
        res.note shouldBe "throne"
    }
})
