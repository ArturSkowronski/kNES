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
})
