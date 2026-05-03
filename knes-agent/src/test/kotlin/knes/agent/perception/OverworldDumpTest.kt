package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.pathfinding.ViewportPathfinder
import java.io.File

/** Diagnostic dump — not a real test. Run only when ROM is present at default path. */
class OverworldDumpTest : FunSpec({
    test("dump overworld region around Coneria → Garland north corridor").config(enabled = false) {
        val romPath = File("/Users/askowronski/Priv/kNES/roms/ff.nes")
        if (!romPath.exists()) return@config
        val map = OverworldMap.fromRom(romPath)
        val xMin = 130; val xMax = 170; val yMin = 125; val yMax = 165
        println("Overworld x=$xMin..$xMax y=$yMin..$yMax (party last seen ~140-152, 149-158):")
        print("       ")
        for (x in xMin..xMax) print(x % 10)
        println()
        for (y in yMin..yMax) {
            print("y=${y.toString().padStart(3)}: ")
            for (x in xMin..xMax) print(map.classifyAt(x, y).glyph)
            println()
        }

        // Verification: live pathfinder fails with found=false from (140,152) and (142,149).
        // Try the same calls offline against the actual ROM-decoded full map.
        val pf = ViewportPathfinder(maxSteps = 64)
        val cases = listOf(
            (140 to 152) to (140 to 145),
            (140 to 152) to (140 to 140),
            (142 to 149) to (142 to 145),
            (146 to 158) to (146 to 153),  // 5 N — adjacent
            (146 to 158) to (146 to 150),  // 8 N — should hit (146, 150)=WATER
            (146 to 158) to (140 to 153),  // diagonal-ish
            (146 to 158) to (140 to 140),  // long detour
            (146 to 158) to (146 to 140),
            (146 to 158) to (146 to 130),
        )
        for ((from, to) in cases) {
            val v = map.readFullMapView(from)
            val res = pf.findPath(from, to, v, FogOfWar())
            println("findPath $from → $to : found=${res.found} partial=${res.partial} " +
                "len=${res.steps.size} reason='${res.reason}' reached=${res.reachedTile}")
        }
    }
})
