package knes.agent.pathfinding

import io.kotest.core.spec.style.FunSpec
import knes.agent.perception.FogOfWar
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldTileClassifier
import java.io.File

/**
 * V5.15 — diagnose: Coneria8VisualDiffTest fails with WalkOverworldTo
 * reporting "stuck at (146,158): no path within viewport (closest
 * reachable: (146, 158))" — i.e. BFS made zero progress. Replicate offline
 * against the ROM-decoded map to determine whether this is a pathfinder
 * bug or an emulator/loop-level issue.
 */
class V515ConeriaPathfinderDiagTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("offline findPath (146,158)→(150,160) on ROM map — diagnostic dump")
        .config(enabled = canRun) {
        val map = OverworldMap.fromRom(File(romPath))
        val pf = ViewportPathfinder(maxSteps = 64)
        val from = 146 to 158
        val to = 150 to 160

        // Print neighbours of start.
        println("[v515-diag] from=$from to=$to (target tile=${map.classifyAt(to.first, to.second)})")
        for ((dx, dy, name) in listOf(
            Triple(0, -1, "N"), Triple(0, 1, "S"),
            Triple(1, 0, "E"), Triple(-1, 0, "W"),
        )) {
            val nx = from.first + dx
            val ny = from.second + dy
            val byte = map.tileAt(nx, ny)
            val type = OverworldTileClassifier.classify(byte)
            println("[v515-diag] neighbour $name ($nx,$ny): byte=0x${byte.toString(16).padStart(2,'0')} " +
                "type=$type passable=${type.isPassable()} impassableTransit=${type.isImpassableTransit()}")
        }

        val view = map.readFullMapView(from)
        val res = pf.findPath(from, to, view, FogOfWar())
        println("[v515-diag] RESULT: found=${res.found} partial=${res.partial} steps=${res.steps.size} " +
            "reached=${res.reachedTile} closestReachable=${res.closestReachable} " +
            "targetPassable=${res.targetPassable} reason='${res.reason}'")

        // No assertion — pure diagnostic. If found=true, problem is in
        // WalkOverworldTo loop. If found=false + closestReachable==start,
        // pathfinder bug to fix.
    }

    test("offline findPath (146,158)→(146,153) — control case, known to work")
        .config(enabled = canRun) {
        val map = OverworldMap.fromRom(File(romPath))
        val pf = ViewportPathfinder(maxSteps = 64)
        val view = map.readFullMapView(146 to 158)
        val res = pf.findPath(146 to 158, 146 to 153, view, FogOfWar())
        println("[v515-control] (146,158)→(146,153): found=${res.found} steps=${res.steps.size} " +
            "reached=${res.reachedTile}")
    }
})
