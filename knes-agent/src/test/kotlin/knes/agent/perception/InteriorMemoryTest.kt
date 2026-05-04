package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class InteriorMemoryTest : FunSpec({

    fun tmpFile(): File {
        val dir = Files.createTempDirectory("interior-memory").toFile()
        dir.deleteOnExit()
        return File(dir, "mem.json")
    }

    test("record then get returns same fact") {
        val mem = InteriorMemory(tmpFile())
        mem.record(8, 11, 32, InteriorObservation.VISITED, note = "spawn")
        val got = mem.get(8, 11, 32)
        got?.observation shouldBe InteriorObservation.VISITED
        got?.note shouldBe "spawn"
        got?.confirmCount shouldBe 1
    }

    test("same-priority record increments confirmCount") {
        val mem = InteriorMemory(tmpFile())
        mem.record(8, 11, 32, InteriorObservation.VISITED)
        mem.record(8, 11, 32, InteriorObservation.VISITED)
        mem.record(8, 11, 32, InteriorObservation.VISITED)
        mem.get(8, 11, 32)?.confirmCount shouldBe 3
    }

    test("higher-priority observation replaces lower; confirmCount resets") {
        val mem = InteriorMemory(tmpFile())
        mem.record(8, 9, 32, InteriorObservation.VISITED)
        mem.record(8, 9, 32, InteriorObservation.POI_STAIRS)
        mem.get(8, 9, 32)?.observation shouldBe InteriorObservation.POI_STAIRS
        mem.get(8, 9, 32)?.confirmCount shouldBe 1
    }

    test("lower-priority observation is dropped silently") {
        val mem = InteriorMemory(tmpFile())
        mem.record(8, 9, 32, InteriorObservation.EXIT_CONFIRMED)
        mem.record(8, 9, 32, InteriorObservation.VISITED)
        mem.get(8, 9, 32)?.observation shouldBe InteriorObservation.EXIT_CONFIRMED
        mem.get(8, 9, 32)?.confirmCount shouldBe 1
    }

    test("visited returns only tiles for given mapId") {
        val mem = InteriorMemory(tmpFile())
        mem.record(8, 11, 32, InteriorObservation.VISITED)
        mem.record(8, 12, 32, InteriorObservation.VISITED)
        mem.record(5, 11, 32, InteriorObservation.VISITED)
        mem.visited(8) shouldBe setOf(11 to 32, 12 to 32)
        mem.visited(5) shouldBe setOf(11 to 32)
    }

    test("pois excludes plain VISITED") {
        val mem = InteriorMemory(tmpFile())
        mem.record(8, 11, 32, InteriorObservation.VISITED)
        mem.record(8, 9, 32, InteriorObservation.POI_STAIRS)
        mem.record(8, 5, 5, InteriorObservation.EXIT_CONFIRMED)
        val pois = mem.pois(8).map { it.observation }.toSet()
        pois shouldBe setOf(InteriorObservation.POI_STAIRS, InteriorObservation.EXIT_CONFIRMED)
    }

    test("save and reload preserves facts") {
        val file = tmpFile()
        val mem1 = InteriorMemory(file)
        mem1.record(8, 11, 32, InteriorObservation.VISITED, note = "spawn")
        mem1.record(8, 9, 32, InteriorObservation.POI_STAIRS)
        mem1.record(8, 5, 5, InteriorObservation.EXIT_CONFIRMED)
        mem1.save()
        file.exists() shouldBe true

        val mem2 = InteriorMemory(file)
        mem2.get(8, 11, 32)?.observation shouldBe InteriorObservation.VISITED
        mem2.get(8, 9, 32)?.observation shouldBe InteriorObservation.POI_STAIRS
        mem2.get(8, 5, 5)?.observation shouldBe InteriorObservation.EXIT_CONFIRMED
        mem2.get(8, 11, 32)?.note shouldBe "spawn"
    }

    test("get returns null for unknown coord") {
        val mem = InteriorMemory(tmpFile())
        mem.record(8, 11, 32, InteriorObservation.VISITED)
        mem.get(8, 0, 0).shouldBeNull()
        mem.get(99, 11, 32).shouldBeNull()
    }
})
