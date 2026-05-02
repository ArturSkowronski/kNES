package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class AsciiMapRendererTest : FunSpec({
    test("renders party glyph at center and known terrain glyphs") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        tiles[5][5] = TileType.MOUNTAIN
        tiles[10][10] = TileType.WATER
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 100 to 100)
        val rendered = AsciiMapRenderer.render(vm, FogOfWar())
        rendered shouldContain "@"
        rendered shouldContain "^"
        rendered shouldContain "~"
        rendered shouldContain "Legend"
        rendered shouldContain "100"
    }

    test("renders X for fog-blocked tiles") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 50 to 50)
        val fog = FogOfWar().apply { markBlocked(51, 50) }
        val out = AsciiMapRenderer.render(vm, fog)
        out shouldContain "X"
    }

    test("renders ? for UNKNOWN viewport tiles") {
        val tiles = Array(16) { Array(16) { TileType.UNKNOWN } }
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 50 to 50)
        val out = AsciiMapRenderer.render(vm, FogOfWar())
        out shouldContain "?"
    }

    test("FOG STATS line includes visited count") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 50 to 50)
        val fog = FogOfWar().apply { merge(vm) }
        val out = AsciiMapRenderer.render(vm, fog)
        out shouldContain "FOG STATS"
        out shouldContain "256"
    }
})
