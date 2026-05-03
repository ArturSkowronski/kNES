package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.pathfinding.ViewportPathfinder
import java.io.File

/** Diagnostic dump — not a real test. Run only when ROM is present at default path. */
class OverworldDumpTest : FunSpec({
    test("dump interior mapId=24 (V2.6.3 stuck point)").config(enabled = false) {
        val romPath = File("/Users/askowronski/Priv/kNES/roms/ff.nes")
        if (!romPath.exists()) return@config
        val rom = romPath.readBytes()
        val loader = InteriorMapLoader(rom)
        val map = loader.load(24)
        println("Interior mapId=24, full 64x64 byte dump (top 24 rows, party at (3,2)):")
        for (y in 0..23) {
            print("  y=${y.toString().padStart(2)}: ")
            for (x in 0..23) {
                val b = map.tileAt(x, y)
                print("0x${b.toString(16).padStart(2,'0')} ")
            }
            println()
        }
        println()
        println("Interior mapId=24, glyph dump (top 24 rows, x=0..23):")
        for (y in 0..23) {
            print("  y=${y.toString().padStart(2)}: ")
            for (x in 0..23) {
                val t = map.classifyAt(x, y)
                print(t.glyph)
            }
            println()
        }

        // Try the pathfinder from (3, 2) on full 64×64 map
        val pf = knes.agent.pathfinding.InteriorPathfinder(maxSteps = 128)
        val viewport = map.readFullMapView(3 to 2)
        val res = pf.findPath(3 to 2, 0 to 0, viewport, FogOfWar())
        println()
        println("InteriorPathfinder.findPath((3,2)) = found=${res.found} partial=${res.partial} " +
            "len=${res.steps.size} reached=${res.reachedTile} reason='${res.reason}'")
    }

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

        // V2.6.1: dump raw tile bytes around suspected TOWN/CASTLE entry tiles
        // to identify the byte values our classifier mis-categorises.
        // V2.6.0 evidence: stepping N from (145, 152) into (145, 151) transported
        // party into Coneria Town interior — yet (145, 151) shows as '.' (grass)
        // in the glyph dump.
        println()
        println("Raw tile bytes around suspected entries:")
        val probes = listOf(
            "above-spawn-grass" to (145 to 151),
            "spawn-tile" to (146 to 158),
            "Coneria-castle-glyph-CC" to (152 to 157),
            "Coneria-town-glyph-CC" to (152 to 159),
            "Coneria-town-glyph-TT" to (152 to 160),
            "south-of-spawn" to (146 to 159),
        )
        for ((name, p) in probes) {
            val (px, py) = p
            val byte = map.tileAt(px, py)
            val tt = map.classifyAt(px, py)
            println("  $name @ ($px, $py) = byte 0x${byte.toString(16).padStart(2, '0')} → ${tt.name} (${tt.glyph})")
        }
        println()
        println("Row y=151 bytes (col 7..16, glyphs all '.'):")
        for (x in 137..147) {
            val b = map.tileAt(x, 151)
            print("  ($x,151)=0x${b.toString(16).padStart(2,'0')}")
        }
        println()
        println("Row y=152 bytes (col 8..17):")
        for (x in 138..147) {
            val b = map.tileAt(x, 152)
            print("  ($x,152)=0x${b.toString(16).padStart(2,'0')}")
        }
        println()
        println("Row y=160 bytes around Coneria town:")
        for (x in 149..156) {
            val b = map.tileAt(x, 160)
            print("  ($x,160)=0x${b.toString(16).padStart(2,'0')}")
        }
        println()
        println("Suspect entry trigger zone — agent stepped S from (145,152) and entered mapId=8:")
        for (y in 152..162) {
            print("  y=$y: ")
            for (x in 142..156) {
                val b = map.tileAt(x, y)
                print("0x${b.toString(16).padStart(2,'0')} ")
            }
            println()
        }
    }
})
