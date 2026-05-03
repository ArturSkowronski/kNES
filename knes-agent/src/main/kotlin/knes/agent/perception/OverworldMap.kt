package knes.agent.perception

import java.io.File

/**
 * FF1 NES overworld map decoded from ROM into a 256x256 byte grid.
 *
 * Format reference: https://datacrystal.tcrf.net/wiki/Final_Fantasy/World_map_data
 *
 * iNES file layout:
 *   - 16-byte header
 *   - Bank 0 PRG (16 KB) at file offset 0x10..0x400F
 *   - Bank 1 PRG (16 KB) at file offset 0x4010..0x800F
 *
 * Pointer table sits at file offset 0x4010 (= NES addr $8000): 256 entries of
 * 16-bit little-endian addresses. To translate a pointer to a ROM file offset
 * subtract 0x3FF0.
 *
 * Each row is RLE-encoded:
 *   0x00..0x7F            -> emit single tile of that id
 *   0x80..0xFE            -> emit (byte - 0x80) repeated by next byte's count
 *                           (count == 0 means 256 tiles)
 *   0xFF                  -> end of row
 */
class OverworldMap private constructor(val tiles: ByteArray) : ViewportSource {

    init {
        require(tiles.size == 256 * 256) { "tiles must be 256x256, got ${tiles.size}" }
    }

    fun tileAt(worldX: Int, worldY: Int): Int {
        val x = ((worldX % 256) + 256) % 256
        val y = ((worldY % 256) + 256) % 256
        return tiles[y * 256 + x].toInt() and 0xFF
    }

    fun classifyAt(worldX: Int, worldY: Int): TileType =
        OverworldTileClassifier.classify(tileAt(worldX, worldY))

    /** Build a 16x16 ViewportMap centered on the given world coordinate. */
    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val size = ViewportMap.SIZE
        val partyLocal = size / 2 to size / 2
        val (pwx, pwy) = partyWorldXY
        val grid = Array(size) { ly ->
            Array(size) { lx ->
                val wx = pwx + (lx - partyLocal.first)
                val wy = pwy + (ly - partyLocal.second)
                classifyAt(wx, wy)
            }
        }
        return ViewportMap(grid, partyLocal, partyWorldXY)
    }

    /**
     * 256×256 ViewportMap covering the whole overworld. Local coords coincide with
     * world coords (party-local = party-world). Used by the global pathfinder so it
     * can plan around large blockers that span the 16×16 window.
     */
    override fun readFullMapView(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val (pwx, pwy) = partyWorldXY
        val grid = Array(256) { y -> Array(256) { x -> classifyAt(x, y) } }
        return ViewportMap(grid, partyLocalXY = pwx to pwy, partyWorldXY = partyWorldXY)
    }

    companion object {
        fun fromRom(romFile: File): OverworldMap = fromRom(romFile.readBytes())

        fun fromRom(rom: ByteArray): OverworldMap {
            require(rom.size >= 0x8010) { "ROM too small (${rom.size} bytes); expected at least 32 KB PRG" }
            // Pointer table at file offset 0x4010 (start of bank 1).
            val pointerTableFileOffset = 0x4010
            val nesToFile = -0x3FF0  // file_offset = nes_addr - 0x4000 + 0x10 = nes_addr - 0x3FF0
            val grid = ByteArray(256 * 256)
            for (row in 0 until 256) {
                val ptrLo = rom[pointerTableFileOffset + row * 2].toInt() and 0xFF
                val ptrHi = rom[pointerTableFileOffset + row * 2 + 1].toInt() and 0xFF
                val nesPtr = ptrLo or (ptrHi shl 8)
                val rowFileOffset = nesPtr + nesToFile
                require(rowFileOffset in 0x4010..0x800F) {
                    "row $row pointer 0x${nesPtr.toString(16)} resolves to invalid file offset 0x${rowFileOffset.toString(16)}"
                }
                decodeRow(rom, rowFileOffset, grid, row)
            }
            return OverworldMap(grid)
        }

        private fun decodeRow(rom: ByteArray, startOffset: Int, grid: ByteArray, row: Int) {
            var col = 0
            var off = startOffset
            while (col < 256) {
                val b = rom[off].toInt() and 0xFF
                off++
                when {
                    b == 0xFF -> {
                        // End of row terminator. If the row didn't fill 256, pad with grass (0x00).
                        // Real FF1 rows always emit exactly 256, so this branch is paranoia.
                        while (col < 256) {
                            grid[row * 256 + col] = 0x00
                            col++
                        }
                        return
                    }
                    b in 0x00..0x7F -> {
                        grid[row * 256 + col] = b.toByte()
                        col++
                    }
                    else /* 0x80..0xFE */ -> {
                        val tile = (b - 0x80).toByte()
                        val rawCount = rom[off].toInt() and 0xFF
                        off++
                        val count = if (rawCount == 0) 256 else rawCount
                        repeat(count) {
                            if (col >= 256) return  // overflow protection
                            grid[row * 256 + col] = tile
                            col++
                        }
                    }
                }
            }
        }
    }
}
