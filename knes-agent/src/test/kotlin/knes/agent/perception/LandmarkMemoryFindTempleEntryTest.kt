package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class LandmarkMemoryFindTempleEntryTest : FunSpec({
    test("returns null when no TEMPLE_ENTRY landmark exists") {
        val tmp = Files.createTempFile("lm-tof-empty-", ".json").toFile().apply { deleteOnExit() }
        val memory = LandmarkMemory(file = tmp)
        memory.findTempleEntry().shouldBeNull()
    }

    test("returns the TEMPLE_ENTRY landmark when present") {
        val tmp = Files.createTempFile("lm-tof-present-", ".json").toFile().apply { deleteOnExit() }
        val memory = LandmarkMemory(file = tmp)
        memory.record(Landmark(
            id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY,
            worldX = 211, worldY = 137,
            note = "chaos-shrine entrance",
        ))
        val found = memory.findTempleEntry()
        found?.kind shouldBe LandmarkKind.TEMPLE_ENTRY
        found?.worldX shouldBe 211
        found?.worldY shouldBe 137
    }
})
