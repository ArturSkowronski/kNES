package knes.agent.perception

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import knes.agent.pathfinding.InteriorPathfinder
import knes.agent.runtime.ToolCallLog
import knes.agent.skills.ExitInterior
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * V5.12 — live integration smoke test for the V5.8→V5.11 stack on a real
 * Coneria castle (mapId=8) entry from boot.
 *
 * Verifies the GPP-inspired pipeline end-to-end:
 *  1. ExitInterior with InteriorMemory wired → records VISITED on each
 *     party-tile-change and saves the JSON file (V5.9 wiring).
 *  2. InteriorPathfinder honours pre-populated POI_STAIRS over the
 *     south-edge fallback when both are reachable (V5.10 priority).
 *  3. JSON roundtrip: a fresh InteriorMemory loaded from the saved file
 *     sees the same visited set (cross-session persistence).
 *
 * Does NOT exercise V5.11 forced exploration prompt (that requires a
 * live vision navigator + ANTHROPIC_API_KEY — out of scope for this
 * deterministic smoke test).
 *
 * Method:
 *   live boot → walk to Coneria interior → run ExitInterior(mem=fresh)
 *   for ≤20 steps → assert memory.visited(8) is non-empty + file exists
 *   → reload from file → assert reload matches.
 *   Then a separate phase reads the live viewport and demonstrates
 *   POI-priority by calling pathfinder.findPath with vs without a
 *   pre-populated POI_STAIRS at a known-reachable target tile.
 */
class V58InteriorMemoryLiveTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("V5.8→V5.10 memory wiring + persistence on Coneria interior")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("3m")) {

        // ---- live boot to Coneria interior (same path as V56InteriorPathfindingTest)
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        check(PressStartUntilOverworld(toolset).invoke().ok) { "PressStartUntilOverworld failed" }
        toolset.step(buttons = emptyList(), frames = 60)
        suspend fun stepDir(name: String, n: Int) {
            repeat(n) {
                toolset.tap(button = name, count = 2, pressFrames = 6, gapFrames = 14)
                toolset.step(buttons = emptyList(), frames = 8)
            }
        }
        stepDir("UP", 6); stepDir("LEFT", 1); stepDir("UP", 1)
        toolset.step(buttons = emptyList(), frames = 30)

        val ramEntry = toolset.getState().ram
        val mapflags = ramEntry["mapflags"] ?: 0
        check((mapflags and 0x01) != 0) {
            "expected interior, got mapflags=0x${mapflags.toString(16)} mapId=${ramEntry["currentMapId"]}"
        }
        val mapId = ramEntry["currentMapId"] ?: -1
        val partyX = ramEntry["smPlayerX"] ?: 0
        val partyY = ramEntry["smPlayerY"] ?: 0
        println("[v58-live] entered interior: mapId=$mapId party=($partyX,$partyY)")

        // ---- Phase A: ExitInterior writes to fresh InteriorMemory + saves
        val tmpMemFile = File.createTempFile("v58-mem-", ".json").apply { delete() }
        val mem = InteriorMemory(tmpMemFile)
        val fog = FogOfWar()
        val mapSession = MapSession(InteriorMapLoader(File(romPath).readBytes()), fog)
        val pathfinder = InteriorPathfinder(
            memory = mem,
            mapIdProvider = { toolset.getState().ram["currentMapId"] ?: -1 },
        )
        val toolCallLog = ToolCallLog()
        val exitSkill = ExitInterior(toolset, mapSession, fog, pathfinder, toolCallLog, mem)
        val result = exitSkill.invoke(mapOf("maxSteps" to "20"))
        println("[v58-live] ExitInterior(maxSteps=20) → ok=${result.ok} message=\"${result.message}\"")
        println("[v58-live] memory.visited(mapId=$mapId).size = ${mem.visited(mapId).size}")
        val pois = mem.pois(mapId)
        println("[v58-live] memory.pois = ${pois.map { "${it.observation}@(${it.tileX},${it.tileY})" }}")
        println("[v58-live] memory file: ${tmpMemFile.path} exists=${tmpMemFile.exists()} " +
            "size=${if (tmpMemFile.exists()) tmpMemFile.length() else 0}")

        withClue("ExitInterior should record at least the spawn tile as VISITED") {
            mem.visited(mapId).size shouldBeGreaterThan 0
        }
        withClue("save() should have written the JSON file") {
            tmpMemFile.exists() shouldBe true
        }

        // ---- Phase B: cross-session roundtrip
        val mem2 = InteriorMemory(tmpMemFile)
        withClue("reloaded memory sees the same visited set") {
            mem2.visited(mapId) shouldBe mem.visited(mapId)
        }
        println("[v58-live] roundtrip: reload visited.size=${mem2.visited(mapId).size}")

        // ---- Phase C: POI priority evidence on live viewport
        // Read the live viewport and find any reachable tile != spawn that the
        // pathfinder will accept as a target. We pick the first BFS-discovered
        // tile from spawn and pre-populate it as POI_STAIRS.
        val ramNow = toolset.getState().ram
        val partyXNow = ramNow["smPlayerX"] ?: partyX
        val partyYNow = ramNow["smPlayerY"] ?: partyY
        val mapIdNow = ramNow["currentMapId"] ?: mapId
        mapSession.ensureCurrent(mapIdNow)
        val viewport = mapSession.readFullMapView(partyXNow to partyYNow)

        // Without memory POI: V5.7-equivalent fallback (south-edge or viewport DOOR/STAIRS)
        val pfNoMem = InteriorPathfinder()
        val resNoMem = pfNoMem.findPath(partyXNow to partyYNow, 0 to 0, viewport, FogOfWar())
        println("[v58-live] phase C noMem: found=${resNoMem.found} reached=${resNoMem.reachedTile} steps=${resNoMem.steps.size} reason=${resNoMem.reason}")

        // Pick a target that is NOT the no-memory result so we can prove memory
        // overrides fallback. Walk one step in any reachable direction from spawn
        // and use that tile as POI_STAIRS.
        val frontier = InteriorFrontier.nearestUnvisited(
            viewport, visited = setOf(partyXNow to partyYNow), from = partyXNow to partyYNow,
        )
        if (frontier == null) {
            println("[v58-live] phase C: no reachable neighbour from party — skipping priority assertion")
            return@config
        }
        val poiTile = frontier.frontier
        println("[v58-live] phase C: pre-populating POI_STAIRS at $poiTile (1 step ${frontier.firstDirection})")

        val memWithPoi = InteriorMemory(File.createTempFile("v58-poi-", ".json").apply { delete() })
        memWithPoi.record(mapIdNow, poiTile.first, poiTile.second, InteriorObservation.POI_STAIRS)
        val pfWithMem = InteriorPathfinder(
            memory = memWithPoi, mapIdProvider = { mapIdNow },
        )
        val resWithMem = pfWithMem.findPath(partyXNow to partyYNow, 0 to 0, viewport, FogOfWar())
        println("[v58-live] phase C withMem: found=${resWithMem.found} reached=${resWithMem.reachedTile} steps=${resWithMem.steps.size}")

        withClue("Memory-driven pathfinder should reach the pre-populated POI_STAIRS") {
            resWithMem.found shouldBe true
            resWithMem.reachedTile shouldBe poiTile
        }
        withClue("With memory + adjacent POI, path is at most 1 step (frontier was 1 away)") {
            resWithMem.steps.size shouldBe frontier.distance
        }
        resWithMem.steps.shouldNotBeEmpty()
    }
})
