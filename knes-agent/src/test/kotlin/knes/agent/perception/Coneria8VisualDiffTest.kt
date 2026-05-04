package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.skills.WalkOverworldTo
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/**
 * V5.1 — visual diff: live Coneria Town frame vs offline-decoded mapId=$targetMapId.
 *
 * Goal: identify the fingerprint of the InteriorMapLoader.load(8) bug.
 * Off-by-one? Wrong bank? Completely wrong ROM section?
 *
 * Procedure:
 *   1. Boot ROM, walk into Coneria area until mapId=$targetMapId appears in RAM.
 *   2. Capture screenshot of the live frame (Coneria Town as the engine
 *      actually renders it).
 *   3. Read RAM party position (localX, localY).
 *   4. Render the offline decoder's mapId=$targetMapId ASCII glyph dump at the same
 *      party-centred 16x15 viewport.
 *   5. Save both to docs/superpowers/notes/coneria8-diff-2026-05-03/.
 *
 * Manual review compares the screenshot against the ASCII window. Mismatch
 * fingerprint → diagnosis path:
 *   - "ASCII looks like a castle, screenshot is a town" → wrong map data
 *     (loader resolves to a different mapId entirely; off-by-one in pointer
 *     table or wrong bank).
 *   - "ASCII matches town shape but tiles offset N rows/columns" → coord
 *     transform missing.
 *   - "ASCII matches" → bug is somewhere else (collision tables?
 *     viewport-vs-full-map mismatch?).
 */
class Coneria8VisualDiffTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("capture Coneria Town live frame + decoded mapId=8 ASCII for visual diff")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("3m")) {

        val repoRoot = File(".").canonicalFile.let { if (it.name == "knes-agent") it.parentFile else it }
        val outDir = File(repoRoot, "docs/superpowers/notes/coneria8-diff-2026-05-03").also { it.mkdirs() }
        val traceFile = File(outDir, "trace.jsonl")
        traceFile.writeText("")

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")

        // V5.2 fixture path: load post-boot savestate instead of replaying boot
        // (~10s saved per run, deterministic resume point at overworld 146,158).
        // Falls back to PressStartUntilOverworld if fixture missing — first run
        // must rebuild fixture via REBUILD_FIXTURE=true PostBootFixtureBuilderTest.
        val fixture = File("src/test/resources/fixtures/ff1-post-boot.savestate")
        if (fixture.exists()) {
            check(session.loadState(fixture.readBytes())) { "fixture loadState failed" }
            // After loadState, ready-buffer reflects pre-save frame; step 1 frame
            // so PPU produces a fresh visible frame.
            toolset.step(buttons = emptyList(), frames = 1)
            val ram = toolset.getState().ram
            println("[fixture] loaded post-boot state: world=(${ram["worldX"]},${ram["worldY"]}) " +
                "locType=0x${(ram["locationType"]?:0).toString(16)}")
        } else {
            println("[fixture] not found at ${fixture.path} — running PressStartUntilOverworld " +
                "(slow path; rebuild fixture with REBUILD_FIXTURE=true)")
            check(PressStartUntilOverworld(toolset).invoke().ok)
        }

        // V5.2 — ROM-scan + persistent memory probe.
        //
        // The OverworldTileClassifier marks ~11 tile bytes as TileType.TOWN, but FF1
        // mechanics (per Entroper bank_0F.asm:1633) tell us teleport-into-town is a
        // tileset_prop bit, NOT derivable from the tile byte alone. So some "TOWN"
        // tiles are entry-triggers, others are decoration. We can't tell offline.
        //
        // Strategy: ROM-scan candidate TOWN tiles → for each one BFS-target → step →
        // observe locType. Record outcome (ENTRY/DECOR/UNREACHABLE) in OverworldMemory
        // (~/.knes/ff1-ow-memory.json). Future sessions load the memory and skip
        // probing — straight BFS to a known ENTRY. Like a player exploring once and
        // remembering the way next time.
        val overworldMap = OverworldMap.fromRom(File(romPath))
        val fog = FogOfWar()
        val memory = OverworldMemory()

        // Save spawn frame for manual review.
        val spawnRam = toolset.getState().ram
        val spawnB64 = toolset.getScreen().base64
        File(outDir, "overworld-spawn.png").writeBytes(Base64.getDecoder().decode(spawnB64))
        val spawnX = spawnRam["worldX"] ?: 146
        val spawnY = spawnRam["worldY"] ?: 158
        println("[diff] spawn world=($spawnX, $spawnY); scanning ROM for nearest TileType.TOWN")

        // ROM scan: collect TOWN candidates within 20 tiles of spawn (sorted by Manhattan).
        val candidates: List<Pair<Int, Int>> = run {
            val list = mutableListOf<Triple<Int, Int, Int>>()
            for (y in 0 until 256) for (x in 0 until 256) {
                if (overworldMap.classifyAt(x, y) == TileType.TOWN) {
                    val d = kotlin.math.abs(x - spawnX) + kotlin.math.abs(y - spawnY)
                    if (d <= 20) list.add(Triple(x, y, d))
                }
            }
            list.sortBy { it.third }
            list.map { it.first to it.second }
        }
        println("[diff] ${candidates.size} TOWN candidates within 20 tiles of spawn; closest 8:")
        for ((x, y) in candidates.take(8)) {
            val mem = memory.get(x, y)
            println("  ($x, $y) memory=${mem?.observation ?: "UNKNOWN"}" +
                (mem?.enteredMapId?.let { " mapId=$it" } ?: ""))
        }

        // Memory shortcut: if any candidate is a known ENTRY for mapId=8, BFS straight there.
        val cachedEntry = candidates.firstNotNullOfOrNull { (x, y) ->
            memory.get(x, y)?.takeIf { it.observation == TileObservation.ENTRY && it.enteredMapId == 8 }
        }

        suspend fun walkBfs(tx: Int, ty: Int, maxSteps: Int = 60): Boolean {
            val r = WalkOverworldTo(toolset, overworldMap, fog).invoke(
                mapOf("targetX" to "$tx", "targetY" to "$ty", "maxSteps" to "$maxSteps")
            )
            traceFile.appendText(buildJsonObject {
                put("phase", "walk"); put("targetX", tx); put("targetY", ty)
                put("ok", r.ok); put("message", r.message)
            }.toString() + "\n")
            return r.ok
        }

        var reachedMapId = -1
        if (cachedEntry != null) {
            println("[diff] memory hit — known ENTRY for mapId=8 at (${cachedEntry.worldX}, ${cachedEntry.worldY}); skipping probe")
            walkBfs(cachedEntry.worldX, cachedEntry.worldY)
            toolset.step(buttons = emptyList(), frames = 30)
            val ram = toolset.getState().ram
            if ((ram["locationType"] ?: 0) != 0) reachedMapId = ram["currentMapId"] ?: 0
        } else {
            println("[diff] no cached ENTRY — BFS to first town tile, then raw flood-fill across blob")
            // Step 1: BFS to the closest TOWN candidate (BFS treats target TOWN as passable).
            val (firstX, firstY) = candidates.first()
            val firstOk = walkBfs(firstX, firstY)
            if (!firstOk) {
                memory.record(firstX, firstY, TileObservation.UNREACHABLE, note = "initial BFS failed")
                memory.save()
                error("could not BFS to first TOWN candidate ($firstX, $firstY) — ${candidates.size} candidates total")
            }
            toolset.step(buttons = emptyList(), frames = 30)

            // Step 2: flood-fill raw cardinal stepping. After landing on first TOWN tile,
            // BFS can no longer route to other TOWN tiles (hard-impassable for non-target).
            // But the engine WILL let the player physically walk between adjacent TOWN tiles
            // via direct button presses. We snake through the blob, recording each tile we
            // land on, and break the moment locType flips (ENTRY found).
            val candidateSet = candidates.toSet()
            val visited = mutableSetOf<Pair<Int, Int>>()
            val dirs = listOf(
                "DOWN" to (0 to 1),
                "RIGHT" to (1 to 0),
                "UP" to (0 to -1),
                "LEFT" to (-1 to 0),
            )
            fun curRam() = toolset.getState().ram
            fun curPos(): Pair<Int, Int> {
                val r = curRam()
                return (r["worldX"] ?: -1) to (r["worldY"] ?: -1)
            }
            // Record the first tile we landed on.
            val landed = curPos()
            val landedRam = curRam()
            val landedLoc = landedRam["locationType"] ?: 0
            if (landedLoc != 0) {
                val mapId = landedRam["currentMapId"] ?: 0
                memory.record(landed.first, landed.second, TileObservation.ENTRY,
                    enteredMapId = mapId, note = "first landing")
                memory.save()
                reachedMapId = mapId
                if (mapId != 8) error("first-landing entered wrong mapId=$mapId at $landed")
            } else {
                memory.record(landed.first, landed.second, TileObservation.DECOR,
                    note = "first landing, no teleport")
                memory.save()
                visited.add(landed)
                println("[probe] landed at $landed → DECOR; flood-filling blob")

                // Iterative flood: from current pos, try adjacent unvisited candidates.
                var iter = 0
                while (iter++ < 30 && reachedMapId == -1) {
                    val cur = curPos()
                    var moved = false
                    for ((btn, dxy) in dirs) {
                        val nx = cur.first + dxy.first
                        val ny = cur.second + dxy.second
                        if ((nx to ny) !in candidateSet) continue
                        if ((nx to ny) in visited) continue
                        toolset.step(buttons = listOf(btn), frames = 24)
                        val newPos = curPos()
                        val r = curRam()
                        val locT = r["locationType"] ?: 0
                        if (locT != 0) {
                            val mapId = r["currentMapId"] ?: 0
                            memory.record(nx, ny, TileObservation.ENTRY,
                                enteredMapId = mapId, note = "flood-fill found ENTRY")
                            memory.save()
                            reachedMapId = mapId
                            println("[probe] flood→$btn ($nx, $ny) → ENTRY locType=0x${locT.toString(16)} mapId=$mapId")
                            break
                        }
                        if (newPos == (nx to ny)) {
                            memory.record(nx, ny, TileObservation.DECOR, note = "flood-fill DECOR")
                            memory.save()
                            visited.add(nx to ny)
                            println("[probe] flood→$btn ($nx, $ny) → DECOR")
                            moved = true
                            break
                        } else {
                            memory.record(nx, ny, TileObservation.UNREACHABLE,
                                note = "tried $btn, party stayed at $cur")
                            memory.save()
                            println("[probe] flood→$btn ($nx, $ny) blocked (party at $cur)")
                        }
                    }
                    if (!moved && reachedMapId == -1) {
                        println("[probe] no unvisited adjacent candidate from $cur — flood-fill exhausted")
                        break
                    }
                }
            }
        }
        traceFile.appendText(buildJsonObject {
            put("phase", "memory-summary")
            put("totalFacts", memory.all().size)
            put("entries", memory.all().count { it.observation == TileObservation.ENTRY })
            put("decor", memory.all().count { it.observation == TileObservation.DECOR })
            put("reachedMapId", reachedMapId)
        }.toString() + "\n")

        val postRam = toolset.getState().ram
        // Always save the final frame + RAM dump for diagnostics, regardless of outcome.
        File(outDir, "final-frame.png").writeBytes(Base64.getDecoder().decode(toolset.getScreen().base64))
        File(outDir, "final-ram.txt").writeText(buildString {
            appendLine("=== Final RAM after BFS + cardinal probes ===")
            for ((k, v) in postRam.entries.sortedBy { it.key }) {
                appendLine("  $k = $v (0x${v.toString(16)})")
            }
        })
        val reachedTown = (postRam["currentMapId"] ?: 0) == 8 && (postRam["locationType"] ?: 0) != 0
        if (reachedTown) {
            val ram = toolset.getState().ram
            println("[diff] reached mapId=8 at " +
                "world=(${ram["worldX"]},${ram["worldY"]}) " +
                "local=(${ram["localX"]},${ram["localY"]})")
        } else {
            val ram = toolset.getState().ram
            val mid = ram["currentMapId"] ?: 0
            val locType = ram["locationType"] ?: 0
            println("[diff] could not reach mapId=8; current interior: " +
                "mapId=$mid locType=0x${locType.toString(16)} — dumping anyway for diff")
            check(locType != 0) { "agent is on overworld; no interior to diff" }
        }

        // Capture live screenshot
        val ram = toolset.getState().ram
        val partyLx = ram["localX"] ?: 0
        val partyLy = ram["localY"] ?: 0
        val pngBytes = Base64.getDecoder().decode(toolset.getScreen().base64)
        File(outDir, "live-frame.png").writeBytes(pngBytes)
        File(outDir, "live-frame.ram.txt").writeText(buildString {
            appendLine("Party RAM at capture:")
            appendLine("  worldX=${ram["worldX"]}  worldY=${ram["worldY"]}")
            appendLine("  localX=$partyLx  localY=$partyLy")
            appendLine("  mapId=${ram["currentMapId"]}  locType=0x${(ram["locationType"]?:0).toString(16)}")
            appendLine("  scrolling=${ram["scrolling"]}  screenState=0x${(ram["screenState"]?:0).toString(16)}")
        })

        // Decode the current mapId and render glyph dump centred on party position.
        val rom = File(romPath).readBytes()
        val targetMapId = ram["currentMapId"] ?: 0
        val map = InteriorMapLoader(rom).load(targetMapId)
        // 16x15 viewport (NES screen size in tiles), centred on party
        val viewW = 16
        val viewH = 15
        val originX = (partyLx - viewW / 2).coerceAtLeast(0)
        val originY = (partyLy - viewH / 2).coerceAtLeast(0)

        val ascii = StringBuilder()
        ascii.appendLine("=== Decoded mapId=$targetMapId ASCII viewport (16x15, party @ centre) ===")
        ascii.appendLine("Party RAM: localX=$partyLx, localY=$partyLy")
        ascii.appendLine("Viewport origin: ($originX, $originY)")
        ascii.appendLine()
        ascii.append("       ")
        for (x in 0 until viewW) ascii.append((originX + x) % 10)
        ascii.appendLine()
        for (y in 0 until viewH) {
            val mapY = originY + y
            ascii.append("y=${mapY.toString().padStart(2)}: ")
            for (x in 0 until viewW) {
                val mapX = originX + x
                val isParty = (mapX == partyLx && mapY == partyLy)
                val tile = map.classifyAt(mapX, mapY)
                ascii.append(if (isParty) '@' else tile.glyph)
            }
            ascii.appendLine()
        }

        // Also include raw bytes for the same window (for hex-pattern matching)
        ascii.appendLine()
        ascii.appendLine("=== Raw byte dump (same window) ===")
        ascii.append("       ")
        for (x in 0 until viewW) ascii.append("%2d ".format((originX + x) % 100))
        ascii.appendLine()
        for (y in 0 until viewH) {
            val mapY = originY + y
            ascii.append("y=${mapY.toString().padStart(2)}: ")
            for (x in 0 until viewW) {
                val mapX = originX + x
                ascii.append("%02x ".format(map.tileAt(mapX, mapY)))
            }
            ascii.appendLine()
        }
        File(outDir, "decoded-mapid-viewport.txt").writeText(ascii.toString())

        // Also dump full mapId=$targetMapId for reference
        val full = StringBuilder()
        full.appendLine("=== mapId=$targetMapId FULL 64x64 glyph dump ===")
        full.append("       ")
        for (x in 0 until 64) full.append(x % 10)
        full.appendLine()
        for (y in 0 until 64) {
            full.append("y=${y.toString().padStart(2)}: ")
            for (x in 0 until 64) {
                val isParty = (x == partyLx && y == partyLy)
                full.append(if (isParty) '@' else map.classifyAt(x, y).glyph)
            }
            full.appendLine()
        }
        File(outDir, "decoded-mapid-full.txt").writeText(full.toString())

        println("[diff] saved live frame + ASCII viewport + full 64x64 dump → ${outDir.absolutePath}")
    }
})
