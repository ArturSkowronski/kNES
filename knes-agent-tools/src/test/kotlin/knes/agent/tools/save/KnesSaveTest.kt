package knes.agent.tools.save

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class KnesSaveTest : FunSpec({
    val json = Json { prettyPrint = false; encodeDefaults = true }

    test("KnesSave round-trips through JSON") {
        val save = KnesSave(
            schemaVersion = 1,
            createdAtMs = 1_715_342_400_000L,
            rom = "ff1.nes",
            emulatorState = "AAEC",
            currentIntent = "leave Pravoka south",
            recentMoves = listOf(
                MoveEntry(seq = 1, tMs = 100, dir = "DOWN", smPre = listOf(7, 8),
                    smPost = listOf(7, 9), moved = true, mapflagsPost = 1, note = null)
            ),
            decisionLog = listOf(
                DecisionEntry(seq = 2, tMs = 110, phase = "exit",
                    reasoning = "south door visible", action = "tap DOWN", outcome = null)
            ),
            landmarks = LandmarksSnapshot(
                kings = listOf(LandmarkRef(mapId = 1, x = 16, y = 8, label = "King of Coneria"))
            ),
            visitedMinimap = VisitedMinimap(width = 32, height = 32, bitsBase64 = "AA=="),
        )
        val encoded = json.encodeToString(KnesSave.serializer(), save)
        val decoded = json.decodeFromString(KnesSave.serializer(), encoded)
        decoded shouldBe save
    }
})
