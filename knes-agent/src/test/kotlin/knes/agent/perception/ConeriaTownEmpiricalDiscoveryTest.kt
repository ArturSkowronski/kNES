package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File
import kotlin.math.abs

/**
 * H2: empirical raw-step DFS exploration of the FF1 overworld near spawn,
 * looking for the Coneria Town entry tile WITHOUT relying on the broken
 * OverworldTileClassifier. The engine's own walkability check (via raw
 * cardinal step + RAM observation) is the source of truth.
 *
 * Algorithm:
 *   1. Load post-boot fixture (party at overworld 146, 158).
 *   2. DFS from spawn, raw-stepping each cardinal direction. After each step:
 *        - locType != 0 → ENTERED interior; if mapId == 8 → save fixture, done.
 *          Otherwise reset (reload fixture + walk via parent path) and continue.
 *        - worldX/Y unchanged → tile blocked; mark and skip.
 *        - worldX/Y changed → walkable; recurse into the new tile.
 *   3. Heuristic: sort cardinals by Manhattan distance to nearest ROM-TOWN tile,
 *      so the search converges toward the town footprint instead of wandering.
 *   4. Bounded by maxTiles (default 60) and maxFrames (~3 min wall clock).
 *
 * Why this works where everything else failed:
 *   - No classifier consulted — engine's actual walkability is observed.
 *   - No vision needed — RAM tells the truth about every step.
 *   - No BFS hard-impassable rule — raw step bypasses pathfinder entirely.
 *   - OverworldMemory persists results: future runs of this or other tests can
 *     query "is (x, y) walkable from neighbor?" without re-probing.
 */
class ConeriaTownEmpiricalDiscoveryTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("DFS raw-step from spawn until mapId=8 entry tile found")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("4m")) {
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")

        // V5.2 H2 diagnostic: try LIVE PressStartUntilOverworld (no fixture load) to
        // establish baseline. If raw cardinal stepping works after a live boot but
        // fails after fixture load, the fixture infrastructure has a state-restoration
        // gap (e.g. controller state, input pipeline). Currently using live boot.
        check(PressStartUntilOverworld(toolset).invoke().ok)
        toolset.step(buttons = emptyList(), frames = 60)
        // Re-snapshot fixture from this LIVE state for use as cache afterward.
        val fixtureBytes: ByteArray = session.saveState()

        val spawn = run {
            val r = toolset.getState().ram
            (r["worldX"] ?: 146) to (r["worldY"] ?: 158)
        }
        println("[discover] spawn=$spawn")

        // Heuristic target: nearest ROM-TOWN tile. We don't trust it as an entry,
        // but we use it as a north-star to bias DFS direction.
        val overworldMap = OverworldMap.fromRom(File(romPath))
        val nearestTown: Pair<Int, Int>? = run {
            var best: Pair<Int, Int>? = null
            var bestD = Int.MAX_VALUE
            for (y in 0 until 256) for (x in 0 until 256) {
                if (overworldMap.classifyAt(x, y) == TileType.TOWN) {
                    val d = abs(x - spawn.first) + abs(y - spawn.second)
                    if (d < bestD) { bestD = d; best = x to y }
                }
            }
            best
        }
        println("[discover] heuristic north-star (nearest ROM-TOWN): $nearestTown")

        val memory = OverworldMemory()
        val parent = mutableMapOf<Pair<Int, Int>, Pair<Pair<Int, Int>, String>>()  // tile → (prev, dirToEnter)
        val walkable = mutableSetOf(spawn)
        val probed = mutableSetOf<Pair<Int, Int>>()
        val tomb = mutableSetOf<Pair<Int, Int>>()  // marked wall / wrong-interior

        fun ram() = toolset.getState().ram
        fun pos(): Pair<Int, Int> {
            val r = ram()
            return (r["worldX"] ?: -1) to (r["worldY"] ?: -1)
        }

        suspend fun resetAndWalkTo(target: Pair<Int, Int>) {
            session.loadState(fixtureBytes)
            toolset.step(buttons = emptyList(), frames = 60)
            if (target == spawn) return
            // Reconstruct path from parent map.
            val rev = ArrayDeque<String>()
            var cur = target
            while (cur != spawn) {
                val (prev, dir) = parent[cur] ?: error("no parent for $cur — graph broken")
                rev.addFirst(dir)
                cur = prev
            }
            for (dir in rev) {
                toolset.tap(button = dir, count = 2, pressFrames = 6, gapFrames = 14)
                toolset.step(buttons = emptyList(), frames = 8)
            }
            val arrived = pos()
            check(arrived == target) { "navigation diverged: wanted $target, got $arrived (path=$rev)" }
        }

        fun cardinalsSorted(from: Pair<Int, Int>): List<Pair<String, Pair<Int, Int>>> {
            val dirs = listOf(
                "DOWN" to (0 to 1),
                "RIGHT" to (1 to 0),
                "UP" to (0 to -1),
                "LEFT" to (-1 to 0),
            )
            val target = nearestTown ?: return dirs.map { it.first to (from.first + it.second.first to from.second + it.second.second) }
            return dirs.map { (name, dxy) ->
                val dest = (from.first + dxy.first) to (from.second + dxy.second)
                val d = abs(dest.first - target.first) + abs(dest.second - target.second)
                Triple(name, dest, d)
            }.sortedBy { it.third }.map { it.first to it.second }
        }

        var foundEntry: Pair<Pair<Int, Int>, Int>? = null  // (entryTile, mapId)
        val frontier = ArrayDeque<Pair<Int, Int>>()
        frontier.addLast(spawn)

        var iters = 0
        outer@ while (frontier.isNotEmpty() && iters < 400 && walkable.size < 80) {
            iters++
            val tile = frontier.removeFirst()
            if (tile in probed) continue
            probed.add(tile)

            // Probe ALL 4 cardinals from this tile. Between probes, reset to fixture
            // and re-navigate via parent path so each probe starts from `tile`.
            for ((dirName, neighbor) in cardinalsSorted(tile)) {
                if (neighbor in walkable || neighbor in tomb) continue
                resetAndWalkTo(tile)
                val before = pos()
                if (before != tile) {
                    println("[discover] WARN navigation diverged: wanted $tile got $before — aborting probe")
                    break
                }
                toolset.tap(button = dirName, count = 2, pressFrames = 6, gapFrames = 14)
                toolset.step(buttons = emptyList(), frames = 8)
                val r = ram()
                val locType = r["locationType"] ?: 0
                val after = (r["worldX"] ?: -1) to (r["worldY"] ?: -1)
                println("[discover] probe $tile $dirName: → $after locType=0x${locType.toString(16)}")
                if (locType != 0) {
                    val mapId = r["currentMapId"] ?: 0
                    memory.record(neighbor.first, neighbor.second, TileObservation.ENTRY,
                        enteredMapId = mapId, note = "raw-step from $tile dir=$dirName")
                    memory.save()
                    if (mapId == 8) {
                        foundEntry = neighbor to mapId
                        break@outer
                    }
                    println("[discover] entered wrong mapId=$mapId — recording and continuing")
                    tomb.add(neighbor)
                    continue
                }
                if (after != before && after !in walkable) {
                    walkable.add(after)
                    parent[after] = tile to dirName
                    memory.record(after.first, after.second, TileObservation.DECOR,
                        note = "walkable (raw-step from $tile dir=$dirName)")
                    memory.save()
                    frontier.addLast(after)
                } else if (after == before) {
                    tomb.add(neighbor)
                    memory.record(neighbor.first, neighbor.second, TileObservation.UNREACHABLE,
                        note = "raw-step blocked: $dirName from $tile")
                    memory.save()
                }
            }
        }

        println("[discover] iters=$iters walkable=${walkable.size} tomb=${tomb.size} found=$foundEntry")

        if (foundEntry != null) {
            val (entry, mapId) = foundEntry!!
            // We're now INSIDE mapId=8. Save the snapshot.
            val snap = session.saveState()
            val target = File("src/test/resources/fixtures/ff1-coneria-town.savestate")
            target.parentFile.mkdirs()
            target.writeBytes(snap)
            File("src/test/resources/fixtures/ff1-coneria-town.json").writeText("""
                {
                  "description": "FF1 inside Coneria Town (mapId=$mapId) — captured by H2 raw-step DFS",
                  "savestate_bytes": ${snap.size},
                  "currentMapId": $mapId,
                  "entryTileWorldX": ${entry.first},
                  "entryTileWorldY": ${entry.second},
                  "spawnX": ${spawn.first},
                  "spawnY": ${spawn.second},
                  "walkableTilesDiscovered": ${walkable.size}
                }
            """.trimIndent())
            File("src/test/resources/fixtures/ff1-coneria-town.png")
                .writeBytes(java.util.Base64.getDecoder().decode(toolset.getScreen().base64))
            println("[discover] SAVED fixture: mapId=$mapId at entry=$entry, ${snap.size} bytes")
        } else {
            error("DFS exhausted (iters=$iters walkable=${walkable.size}) without finding mapId=8 entry — " +
                "see ~/.knes/ff1-ow-memory.json for full empirical map")
        }
    }
})
