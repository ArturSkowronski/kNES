package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import java.nio.file.Files

class LandmarkContextTest : FunSpec({
    fun emptyMem(): LandmarkMemory =
        LandmarkMemory(file = Files.createTempFile("lm", ".json").toFile().apply { deleteOnExit() })

    test("render returns null when memory is empty") {
        LandmarkContext.render(emptyMem()) shouldBe null
    }

    test("render groups entries by kind in canonical order") {
        val mem = emptyMem().apply {
            record(Landmark(id = "npc_generic_1", kind = LandmarkKind.NPC_GENERIC,
                mapId = 8, localX = 5, localY = 6))
            record(Landmark(id = "town_147", kind = LandmarkKind.TOWN_ENTRY,
                worldX = 147, worldY = 154, mapIdInterior = 8, visited = true))
            record(Landmark(id = "castle_152", kind = LandmarkKind.CASTLE_ENTRY,
                worldX = 152, worldY = 151, mapIdInterior = 1))
            record(Landmark(id = "king_1", kind = LandmarkKind.NPC_KING,
                mapId = 1, note = "asks for bridge"))
        }
        val text = LandmarkContext.render(mem)!!
        text shouldContain "Known landmarks"
        // ordering: TOWN before CASTLE before NPC_KING before NPC_GENERIC
        val tIdx = text.indexOf("TOWN_ENTRY")
        val cIdx = text.indexOf("CASTLE_ENTRY")
        val kIdx = text.indexOf("NPC_KING")
        val gIdx = text.indexOf("NPC_GENERIC")
        (tIdx in 0 until cIdx) shouldBe true
        (cIdx in 0 until kIdx) shouldBe true
        (kIdx in 0 until gIdx) shouldBe true
    }

    test("render formats overworld landmark with world coords + mapId destination") {
        val mem = emptyMem().apply {
            record(Landmark(id = "town", kind = LandmarkKind.TOWN_ENTRY,
                worldX = 147, worldY = 154, mapIdInterior = 8, visited = true,
                note = "south Coneria edge"))
        }
        val text = LandmarkContext.render(mem)!!
        text shouldContain "TOWN_ENTRY at world(147,154)"
        text shouldContain "→ mapId=8"
        text shouldContain "[visited]"
        text shouldContain "south Coneria edge"
    }

    test("render formats interior NPC landmark with mapId only") {
        val mem = emptyMem().apply {
            record(Landmark(id = "k", kind = LandmarkKind.NPC_KING,
                mapId = 1, note = "throne"))
        }
        val text = LandmarkContext.render(mem)!!
        text shouldContain "NPC_KING in mapId=1"
        text shouldContain "throne"
        text shouldNotContain "world("
    }

    test("render truncates long notes to 60 chars") {
        val long = "x".repeat(120)
        val mem = emptyMem().apply {
            record(Landmark(id = "g", kind = LandmarkKind.NPC_GENERIC, mapId = 8, note = long))
        }
        val text = LandmarkContext.render(mem)!!
        text shouldContain "x".repeat(60)
        // 61 'x' would mean the truncation didn't fire
        text shouldNotContain "x".repeat(61)
    }
})
