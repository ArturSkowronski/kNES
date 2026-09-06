package knes.agent.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.tools.results.AgentObservationBuilder
import knes.agent.tools.results.AgentPhase
import knes.agent.tools.results.ScreenPng
import knes.agent.tools.results.StateSnapshot

class AgentObservationTest : FunSpec({

    test("classifies FF1 overworld near Coneria from RAM") {
        val observation = AgentObservationBuilder.from(
            StateSnapshot(
                frame = 120,
                ram = mapOf(
                    "currentMapId" to 0,
                    "mapflags" to 0,
                    "char1_hpLow" to 35,
                    "worldX" to 146,
                    "worldY" to 158
                ),
                cpu = emptyMap(),
                heldButtons = emptyList()
            ),
            profileId = "ff1"
        )

        observation.phase shouldBe AgentPhase.Overworld
        observation.position.worldX shouldBe 146
        observation.position.worldY shouldBe 158
        observation.location?.id shouldBe "ff1.coneria_region"
        observation.location?.name shouldBe "Coneria region"
    }

    test("classifies FF1 town overlay as Coneria when mapflags and world anchor match") {
        val observation = AgentObservationBuilder.from(
            StateSnapshot(
                frame = 240,
                ram = mapOf(
                    "currentMapId" to 0,
                    "mapflags" to 1,
                    "char1_hpLow" to 35,
                    "worldX" to 145,
                    "worldY" to 152,
                    "smPlayerX" to 11,
                    "smPlayerY" to 14
                ),
                cpu = emptyMap(),
                heldButtons = listOf("A")
            ),
            screen = ScreenPng(base64 = "png"),
            profileId = "ff1"
        )

        observation.phase shouldBe AgentPhase.Town
        observation.position.localX shouldBe 11
        observation.position.localY shouldBe 14
        observation.location?.id shouldBe "ff1.coneria"
        observation.location?.name shouldBe "Coneria"
        observation.screenshot?.base64 shouldBe "png"
        observation.heldButtons shouldBe listOf("A")
    }

    test("battle screen state wins over map position") {
        val observation = AgentObservationBuilder.from(
            StateSnapshot(
                frame = 300,
                ram = mapOf(
                    "screenState" to 0x68,
                    "currentMapId" to 0,
                    "mapflags" to 0,
                    "char1_hpLow" to 35,
                    "worldX" to 146,
                    "worldY" to 158
                ),
                cpu = emptyMap(),
                heldButtons = emptyList()
            ),
            profileId = "ff1"
        )

        observation.phase shouldBe AgentPhase.Battle
        observation.location?.id shouldBe "ff1.coneria_region"
    }
})
