package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import java.io.File

class InteriorMapLiveTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("decodes interior maps 0..63 without crashing").config(enabled = romPresent) {
        val rom = File(romPath).readBytes()
        val loader = InteriorMapLoader(rom)
        var nonEmpty = 0
        for (id in 0..63) {
            val map = loader.load(id)
            val nonZero = (0 until InteriorMap.WIDTH * InteriorMap.HEIGHT)
                .any { map.tiles[it].toInt() != 0 }
            if (nonZero) nonEmpty++
        }
        check(nonEmpty >= 30) { "only $nonEmpty non-empty maps in 0..63 — decoder likely broken" }
    }

    test("dump map id 8 (Coneria town per T4 research)").config(enabled = romPresent) {
        val outDir = File("build/research/interior-maps").also { it.mkdirs() }
        val rom = File(romPath).readBytes()
        val loader = InteriorMapLoader(rom)
        for (id in listOf(0, 1, 8, 12)) {
            val map = loader.load(id)
            val sb = StringBuilder()
            sb.append("=== InteriorMap id=$id (64x64 hex) ===\n")
            for (y in 0 until InteriorMap.HEIGHT) {
                for (x in 0 until InteriorMap.WIDTH) {
                    sb.append("%02x ".format(map.tileAt(x, y)))
                }
                sb.append('\n')
            }
            File(outDir, "map-$id.hex.txt").writeText(sb.toString())
        }
    }
})
