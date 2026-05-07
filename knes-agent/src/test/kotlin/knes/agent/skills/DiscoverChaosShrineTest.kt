package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ScreenPng
import knes.agent.tools.results.StateSnapshot
import knes.api.EmulatorSession
import java.nio.file.Files
import java.util.Base64

class DiscoverChaosShrineTest : FunSpec({
    test("Discovered: persists TEMPLE_ENTRY landmark with world coords") {
        val ramAt200_140 = mapOf("worldX" to 200, "worldY" to 140)
        val pngBytes = byteArrayOf(0x42)
        val toolset = StubToolset(ramAt200_140, screenshotB64 = Base64.getEncoder().encodeToString(pngBytes))
        val viewport = StubViewportSource(ViewportMap(
            tiles = Array(16) { Array(16) { TileType.UNKNOWN } },
            partyLocalXY = 8 to 8,
            partyWorldXY = 200 to 140,
        ))
        // Gemini reports shrine at screen tile (11, 4) — world via localToWorld:
        // (200 + 11 - 8, 140 + 4 - 8) = (203, 136)
        val vision = FakeHaikuConsult(overworldClassifications = listOf(
            HaikuConsult.OverworldClassification.Found(screenX = 11, screenY = 4, costUsd = 0.005),
        ))
        val tmp = Files.createTempFile("dcs-found-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)

        val skill = DiscoverChaosShrine(toolset, viewport, landmarks, vision)
        val r = skill.invoke(emptyMap())

        r.ok shouldBe true
        r.message.contains("Discovered") shouldBe true
        r.message.contains("(203,136)") shouldBe true
        landmarks.findTempleEntry()?.worldX shouldBe 203
        landmarks.findTempleEntry()?.worldY shouldBe 136
    }

    test("NotVisible: no landmark persisted, ok=true with NotVisible message") {
        val toolset = StubToolset(mapOf("worldX" to 150, "worldY" to 150), screenshotB64 = Base64.getEncoder().encodeToString(byteArrayOf(0x01)))
        val viewport = StubViewportSource(ViewportMap.ofUnknown(150 to 150))
        val vision = FakeHaikuConsult(overworldClassifications = listOf(
            HaikuConsult.OverworldClassification.NotFound(costUsd = 0.005),
        ))
        val tmp = Files.createTempFile("dcs-nv-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)

        val skill = DiscoverChaosShrine(toolset, viewport, landmarks, vision)
        val r = skill.invoke(emptyMap())

        r.ok shouldBe true
        r.message.contains("NotVisible") shouldBe true
        landmarks.findTempleEntry() shouldBe null
    }
})

private class StubToolset(
    private val ram: Map<String, Int>,
    private val screenshotB64: String,
) : EmulatorToolset(EmulatorSession()) {
    override fun getState(): StateSnapshot =
        StateSnapshot(frame = 0, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    override fun getScreen(): ScreenPng = ScreenPng(base64 = screenshotB64)
}

private class StubViewportSource(private val vm: ViewportMap) : ViewportSource {
    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap = vm
}
