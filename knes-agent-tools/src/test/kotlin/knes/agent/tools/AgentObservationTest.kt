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

    test("without a profile nothing is interpreted, but raw state still passes through") {
        val observation = AgentObservationBuilder.from(
            StateSnapshot(
                frame = 90,
                ram = mapOf("currentMapId" to 0, "mapflags" to 1, "worldX" to 146, "worldY" to 158),
                cpu = mapOf("pc" to 0x8000),
                heldButtons = listOf("B")
            )
        )

        observation.profileId shouldBe null
        observation.phase shouldBe AgentPhase.Unknown
        observation.position.worldX shouldBe null
        observation.location shouldBe null
        observation.frame shouldBe 90
        observation.cpu["pc"] shouldBe 0x8000
        observation.heldButtons shouldBe listOf("B")
    }

    test("a profile without semantics is reported as not applied") {
        val observation = AgentObservationBuilder.from(
            StateSnapshot(frame = 1, ram = emptyMap(), cpu = emptyMap(), heldButtons = emptyList()),
            profileId = "no-such-game"
        )

        observation.profileId shouldBe null
        observation.phase shouldBe AgentPhase.Unknown
    }

    test("interpretation is driven by profile semantics, not by the builder") {
        val observation = AgentObservationBuilder.from(
            StateSnapshot(
                frame = 5,
                ram = mapOf("gameState" to 1, "playerX" to 72, "playerY" to 120),
                cpu = emptyMap(),
                heldButtons = emptyList()
            ),
            profileId = "smb"
        )

        observation.profileId shouldBe "smb"
        observation.phase shouldBe AgentPhase.Overworld
        observation.position.localX shouldBe 72
        observation.position.localY shouldBe 120
        observation.location shouldBe null
    }
})
