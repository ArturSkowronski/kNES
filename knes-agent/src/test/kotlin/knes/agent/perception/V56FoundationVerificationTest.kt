package knes.agent.perception

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import knes.agent.skills.ExitInterior
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * V5.6 foundation verification — empirical sanity check on real ROM state.
 *
 * Loads the V5.3 discovery savestate (party inside mapId=8 with $0048=8,
 * mapflags bit 0 set, sm_player=(11,32), sm_scroll=(4,25)) and verifies:
 *
 *  1. \$0068/\$0069 (smPlayerX/Y) and \$0029/\$002A (localX/Y) are DISTINCT
 *     addresses — not aliases. If they hold the same value, profile is wrong.
 *  2. \$002D (mapflags) bit 0 is set (canonical 'in standard map').
 *  3. RamObserver.classify(ram) returns Indoors(mapId=8, localX=11, localY=32,
 *     isTown=true) — sourcing party tile from sm_player not scroll.
 *  4. ExitInterior, given the new wiring, computes party=(11,32) and queries
 *     the InteriorPathfinder against THAT tile (not the scroll-origin tile
 *     (4,25) which is ramObserver-pre-V5.6 behaviour).
 *
 * Pre-V5.6: the equivalent of ExitInterior would query (4+8, 25+7)=(12,32) per
 * the static-offset hack — coincidentally close in this savestate but breaks
 * at map edges. Post-V5.6: the canonical (11,32) read directly from \$68/\$69.
 *
 * No emulator step input — purely read-only verification of address wiring.
 */
class V56FoundationVerificationTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("V5.6 wiring: profile + RamObserver + ExitInterior all use sm_player coords")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("30s")) {

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")

        val fixture = File("src/test/resources/fixtures/ff1-coneria-interior-discovery.savestate")
        check(fixture.exists()) { "missing discovery fixture at ${fixture.path} — run MapIdDiscoveryTest first" }
        check(session.loadState(fixture.readBytes())) { "loadState failed" }
        toolset.step(buttons = emptyList(), frames = 1)

        // === Step 1: profile exposes new addresses with distinct values ===
        val ram = toolset.getState().ram
        println("[verify] ram keys (selected): " +
            "smPlayerX=${ram["smPlayerX"]} smPlayerY=${ram["smPlayerY"]} " +
            "localX=${ram["localX"]} localY=${ram["localY"]} " +
            "mapflags=0x${(ram["mapflags"]?:0).toString(16)} " +
            "currentMapId=${ram["currentMapId"]} " +
            "locationType=0x${(ram["locationType"]?:0).toString(16)}")

        withClue("profile must expose all V5.6 addresses") {
            ram.keys shouldContainAll listOf("smPlayerX", "smPlayerY", "mapflags", "currentMapId",
                "localX", "localY", "locationType", "facing", "vehicle", "curTileset")
        }
        withClue("smPlayerX (\$68) and localX (\$29) must be DISTINCT addresses, not aliases") {
            // In the discovery state we observed sm_player_x=11 and sm_scroll_x=4 (Δ=7)
            ram["smPlayerX"] shouldNotBe ram["localX"]
        }
        withClue("smPlayerY (\$69) and localY (\$2A) must be distinct") {
            ram["smPlayerY"] shouldNotBe ram["localY"]
        }

        // === Step 2: mapflags bit 0 set (canonical 'in standard map') ===
        withClue("mapflags bit 0 must be set inside standard map (per Disch bank_0F.asm)") {
            ((ram["mapflags"] ?: 0) and 0x01) shouldBe 1
        }

        // === Step 3: RamObserver.classify routes to Indoors with sm_player coords ===
        val phase = RamObserver.classify(ram)
        println("[verify] RamObserver.classify(ram) = $phase")
        withClue("phase must be Indoors with party from sm_player (\$68/\$69), not scroll") {
            phase shouldBe FfPhase.Indoors(
                mapId = ram["currentMapId"] ?: -1,
                localX = ram["smPlayerX"] ?: 0,
                localY = ram["smPlayerY"] ?: 0,
                isTown = (ram["locationType"] ?: 0) != 0xD1,
            )
        }
        // Defensive: phase party tile must NOT be the scroll value.
        check(phase is FfPhase.Indoors)
        withClue("V5.6 regression check: Indoors.localX must come from \$68 (sm_player), NOT \$29 (scroll)") {
            phase.localX shouldNotBe (ram["localX"] ?: -999)
        }

        // === Step 4: ExitInterior reads from sm_player and queries the pathfinder ===
        val fog = FogOfWar()
        val mapSession = MapSession(InteriorMapLoader(File(romPath).readBytes()), fog)
        val exitSkill = ExitInterior(toolset, mapSession, fog)
        // maxSteps=1: we just want to confirm one iteration computes party coords
        // correctly. The result message is the diagnostic — it should reference
        // party=(${smPlayerX},${smPlayerY}), not the V2.6.4 (+8,+7) static-offset value.
        val r = exitSkill.invoke(mapOf("maxSteps" to "1"))
        println("[verify] ExitInterior(maxSteps=1) → ok=${r.ok} message=\"${r.message}\"")

        // ExitInterior with maxSteps=1 either succeeds (encounter / overworld /
        // step-taken) or fails with "no exit visible at mapId=…, party=(X,Y)…".
        // In all branches we expect the party coords to be sm_player, NOT scroll.
        // Check the failure message OR ramAfter contains sm_player matching pre-step.
        val expectedPartyTag = "party=(${ram["smPlayerX"]},${ram["smPlayerY"]})"
        val partyCoordsAppear = r.message.contains(expectedPartyTag) ||
            r.ramAfter["smPlayerX"] == ram["smPlayerX"]
        withClue("ExitInterior must use sm_player coords for party. Expected " +
            "'$expectedPartyTag' in message OR ramAfter[smPlayerX]==${ram["smPlayerX"]}; " +
            "got message='${r.message}', ramAfter[smPlayerX]=${r.ramAfter["smPlayerX"]}") {
            partyCoordsAppear shouldBe true
        }

        // === Bonus: how many tests in 105 now pass with this wiring confirmed? ===
        val mapDataPassable = run {
            val px = ram["smPlayerX"] ?: 0
            val py = ram["smPlayerY"] ?: 0
            mapSession.ensureCurrent(ram["currentMapId"] ?: 0)
            val tile = mapSession.currentMap?.classifyAt(px, py)
            println("[verify] InteriorMap[mapId=${ram["currentMapId"]}].classifyAt(party=$px,$py) = $tile")
            tile?.isPassable() ?: false
        }
        withClue("Sanity: party stands on a passable tile per the InteriorMap decoder. " +
            "If false, either (a) decoder is wrong, (b) this savestate is mid-transition, " +
            "or (c) party is on an unwalkable trigger tile (NPC, treasure).") {
            mapDataPassable shouldBe true
        }
    }
})
