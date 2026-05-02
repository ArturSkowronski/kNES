package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import java.io.File

class DumpAround152Test : FunSpec({
    test("dump around (145, 152)") {
        val map = OverworldMap.fromRom(File("/Users/askowronski/Priv/kNES/roms/ff.nes"))
        println("=== bytes around (145, 152) — viewport 16x16 ===")
        for (dy in -8..7) {
            for (dx in -8..7) {
                val x = 145 + dx; val y = 152 + dy
                print("%02x ".format(map.tileAt(x, y)))
            }
            println()
        }
        println("=== glyphs around (145, 152) ===")
        for (dy in -8..7) {
            for (dx in -8..7) {
                val x = 145 + dx; val y = 152 + dy
                print(map.classifyAt(x, y).glyph.toString() + "  ")
            }
            println()
        }
    }
})
