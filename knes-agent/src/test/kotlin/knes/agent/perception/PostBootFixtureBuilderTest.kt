package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * Fixture builder: boots FF1, runs PressStartUntilOverworld, snapshots emulator
 * state to `src/test/resources/fixtures/ff1-post-boot.savestate`. Other tests
 * load this fixture instead of replaying boot every time (saves ~10 seconds
 * per test) and inspect the actual locType / mapId / coords we land in.
 *
 * Disabled by default — run manually to refresh the fixture when emulator or
 * profile semantics change.
 *
 * Usage:
 *   ./gradlew :knes-agent:test --tests "*PostBootFixtureBuilder*" -PrebuildFixture
 */
class PostBootFixtureBuilderTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()
    val rebuild = System.getProperty("rebuildFixture") == "true" ||
        System.getenv("REBUILD_FIXTURE") == "true"

    test("build post-boot fixture (Coneria Castle interior or overworld spawn)")
        .config(enabled = canRun && rebuild) {
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        check(PressStartUntilOverworld(toolset).invoke().ok)
        // Settle a few frames so PPU has stable state and any boot-time animations
        // have flushed.
        toolset.step(buttons = emptyList(), frames = 30)

        val ram = toolset.getState().ram
        val locType = ram["locationType"] ?: 0
        val mapId = ram["currentMapId"] ?: 0
        val state = if (locType != 0) "INTERIOR mapId=$mapId localX=${ram["localX"]} localY=${ram["localY"]}"
                    else "OVERWORLD worldX=${ram["worldX"]} worldY=${ram["worldY"]}"
        println("[fixture] post-boot state: $state (locType=0x${locType.toString(16)})")

        val saved = session.saveState()
        val fixtureFile = File("src/test/resources/fixtures/ff1-post-boot.savestate")
        fixtureFile.parentFile.mkdirs()
        fixtureFile.writeBytes(saved)

        // Companion JSON describing what's inside.
        val descFile = File("src/test/resources/fixtures/ff1-post-boot.json")
        descFile.writeText(buildString {
            append("{\n")
            append("  \"description\": \"FF1 post-PressStartUntilOverworld snapshot\",\n")
            append("  \"savestate_bytes\": ${saved.size},\n")
            append("  \"locationType\": $locType,\n")
            append("  \"currentMapId\": $mapId,\n")
            append("  \"worldX\": ${ram["worldX"] ?: 0},\n")
            append("  \"worldY\": ${ram["worldY"] ?: 0},\n")
            append("  \"localX\": ${ram["localX"] ?: 0},\n")
            append("  \"localY\": ${ram["localY"] ?: 0},\n")
            append("  \"interpretation\": \"$state\"\n")
            append("}\n")
        })
        // Also save a screenshot for visual reference.
        File("src/test/resources/fixtures/ff1-post-boot.png")
            .writeBytes(java.util.Base64.getDecoder().decode(toolset.getScreen().base64))

        println("[fixture] saved ${saved.size} bytes to ${fixtureFile.absolutePath}")
    }
})
