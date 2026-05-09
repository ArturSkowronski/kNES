package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Diagnostic-only test: decodes Coneria town (mapId=8) interior tiles from the
 * ROM and prints STAIRS tile coordinates + an ASCII rendering of the south
 * half of the map. Used to ground-truth the navigation advisor — empirical
 * runs showed the advisor's "weapon shop X~8-9" hint was wrong; this test
 * exposes where the actual sub-shop entry tiles sit.
 *
 * Skipped when the ROM is not present.
 */
class Coneria8DoorDumpTest : FunSpec({
    val romPath = "${System.getProperty("user.home")}/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("dump Coneria mapId=8 STAIRS positions and ASCII layout").config(enabled = romPresent) {
        val rom = File(romPath).readBytes()
        val map = InteriorMapLoader(rom).load(8)
        val stairs = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                if (map.classifyAt(x, y) == TileType.STAIRS) stairs.add(x to y)
            }
        }
        println("=== STAIRS tiles in mapId=8 (Coneria town) ===")
        stairs.forEach { (x, y) -> println("  (X=$x, Y=$y) raw=0x${"%02x".format(map.tileAt(x, y))}") }
        println("Total STAIRS: ${stairs.size}")
        println()
        // Dump ALL unique tile ids in the south plaza building strip (Y=27..31, X=0..30)
        // — looking for sub-shop entry tile ids that are NOT 0x44.
        println("=== Unique tile ids in Y=27..31, X=0..30 ===")
        val ids = mutableSetOf<Int>()
        for (y in 27..31) for (x in 0..30) ids.add(map.tileAt(x, y))
        ids.sorted().forEach { id ->
            val sample = mutableListOf<Pair<Int,Int>>()
            for (y in 27..31) for (x in 0..30) if (map.tileAt(x, y) == id) sample.add(x to y)
            println("  0x${"%02x".format(id)} (cls=${InteriorTileClassifier.classify(id)}) count=${sample.size} samples=${sample.take(5)}")
        }
        println()
        // ASCII render with TILE IDs (hex) instead of just glyph — for the south
        // plaza building strip — so we can spot which tile id gates each shop.
        println("=== HEX tile ids Y=26..32, X=0..30 (south plaza buildings) ===")
        println("    " + (0..30).joinToString(" ") { "%2d".format(it) })
        for (y in 26..32) {
            val row = (0..30).joinToString(" ") { x -> "%02x".format(map.tileAt(x, y)) }
            println("Y%2d %s".format(y, row))
        }
        println()
        println("=== ASCII map (Y 0..40, X 0..30) ===")
        println("    " + (0..30).joinToString("") { "%2d".format(it).last().toString() })
        for (y in 0..40) {
            val row = (0..30).joinToString("") { x ->
                when (map.classifyAt(x, y)) {
                    TileType.STAIRS -> "D"
                    TileType.MOUNTAIN -> "#"
                    TileType.GRASS -> "."
                    else -> "?"
                }
            }
            println("Y%2d %s".format(y, row))
        }
        println()
        // Full hex dump of entire 64-wide top strip, Y=0..16, looking for any
        // unusual tile ids (sub-shop entry tiles likely live at top of map
        // since the south plaza Y=27..31 buildings have no obvious entry tile).
        println("=== HEX tile ids Y=0..16, X=0..30 (top half of Coneria) ===")
        println("    " + (0..30).joinToString(" ") { "%2d".format(it) })
        for (y in 0..16) {
            val row = (0..30).joinToString(" ") { x -> "%02x".format(map.tileAt(x, y)) }
            println("Y%2d %s".format(y, row))
        }
    }
})
