package knes.agent.perception

class InteriorMapLoader(private val rom: ByteArray) {

    fun load(mapId: Int): InteriorMap {
        require(mapId in 0..127) { "mapId $mapId out of range" }
        val ptrFile = POINTER_TABLE_OFFSET + mapId * 2
        require(ptrFile + 1 < rom.size) { "ROM too small for mapId $mapId" }
        val raw = (rom[ptrFile].toInt() and 0xFF) or
                  ((rom[ptrFile + 1].toInt() and 0xFF) shl 8)
        val bankIndex = (raw ushr 14) and 0x03
        val bankBase = (4 + bankIndex) * 0x4000
        val dataStart = bankBase + 0x10 + (raw and 0x3FFF)
        require(dataStart in 0 until rom.size) {
            "mapId $mapId pointer 0x${raw.toString(16)} resolves to invalid offset 0x${dataStart.toString(16)}"
        }
        return InteriorMap(decodeRle(rom, dataStart))
    }

    companion object {
        const val POINTER_TABLE_OFFSET = 0x10010

        internal fun decodeRle(rom: ByteArray, start: Int): ByteArray {
            val grid = ByteArray(InteriorMap.WIDTH * InteriorMap.HEIGHT)
            var idx = 0
            var off = start
            while (idx < grid.size) {
                if (off >= rom.size) break
                val b = rom[off].toInt() and 0xFF
                off++
                when {
                    b == 0xFF -> return grid
                    b in 0x00..0x7F -> {
                        grid[idx++] = b.toByte()
                    }
                    else -> {
                        if (off >= rom.size) break
                        val tile = (b - 0x80).toByte()
                        val rawCount = rom[off].toInt() and 0xFF
                        off++
                        val count = if (rawCount == 0) 256 else rawCount
                        repeat(count) {
                            if (idx >= grid.size) return grid
                            grid[idx++] = tile
                        }
                    }
                }
            }
            return grid
        }
    }
}
