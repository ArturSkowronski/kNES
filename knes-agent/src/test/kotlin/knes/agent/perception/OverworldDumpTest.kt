package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.pathfinding.ViewportPathfinder
import java.io.File

/** Diagnostic dump — not a real test. Run only when ROM is present at default path. */
class OverworldDumpTest : FunSpec({
    test("dump mapId=8 to verify scroll-offset hypothesis against V2.4.4 evidence").config(enabled = false) {
        val romPath = File("/Users/askowronski/Priv/kNES/roms/ff.nes")
        if (!romPath.exists()) return@config
        val rom = romPath.readBytes()
        val map = InteriorMapLoader(rom).load(8)
        // Party RAM signatures we've seen: (5, 28) and (4, 11).
        // If localX/Y = party tile directly, those tiles should be passable.
        // If localX/Y = scroll offset, party tile = (lx+8, ly+7).
        val testCases = listOf(5 to 28, 4 to 11, 13 to 35, 12 to 18, 10 to 32)
        println("mapId=8 byte/classify at known positions:")
        for ((x, y) in testCases) {
            val b = map.tileAt(x, y)
            val t = map.classifyAt(x, y)
            println("  ($x, $y) byte=0x${b.toString(16).padStart(2,'0')} → ${t.name} passable=${t.isPassable()}")
        }
        println()
        println("mapId=8 glyph dump 0..47 x 0..47:")
        print("       ")
        for (x in 0..47) print(x % 10)
        println()
        for (y in 0..47) {
            print("y=${y.toString().padStart(2)}: ")
            for (x in 0..47) print(map.classifyAt(x, y).glyph)
            println()
        }
    }

    test("dump interior mapId=24 full 64x64 — locate playable area").config(enabled = false) {
        val romPath = File("/Users/askowronski/Priv/kNES/roms/ff.nes")
        if (!romPath.exists()) return@config
        val rom = romPath.readBytes()
        val loader = InteriorMapLoader(rom)
        val map = loader.load(24)
        // Glyph dump full 64x64
        println("Interior mapId=24 FULL 64x64 glyph dump:")
        print("       ")
        for (x in 0 until 64) print(x % 10)
        println()
        for (y in 0 until 64) {
            print("y=${y.toString().padStart(2)}: ")
            for (x in 0 until 64) print(map.classifyAt(x, y).glyph)
            println()
        }
        // Find any non-padding rectangular regions
        println()
        var minPlayX = 64; var maxPlayX = -1; var minPlayY = 64; var maxPlayY = -1
        for (y in 0 until 64) for (x in 0 until 64) {
            val t = map.classifyAt(x, y)
            if (t != TileType.UNKNOWN) {
                if (x < minPlayX) minPlayX = x
                if (x > maxPlayX) maxPlayX = x
                if (y < minPlayY) minPlayY = y
                if (y > maxPlayY) maxPlayY = y
            }
        }
        println("Playable bbox: x=$minPlayX..$maxPlayX, y=$minPlayY..$maxPlayY")
        println("Party RAM coords: (3, 2)  / (3, 11)")
        println("If party is in bbox: localX/Y = ROM coords directly (current assumption)")
        println("If not: localX/Y likely scroll-relative; party's actual ROM coord = (scroll_x + 3, scroll_y + 2)")
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
