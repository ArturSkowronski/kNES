package knes.agent.perception

/**
 * 16x16 grid of TileType centered on the party.
 *
 * @param tiles row-major, tiles[y][x] where y=0 is north edge.
 * @param partyLocalXY party position within the 16x16 grid (typically (8,8)).
 * @param partyWorldXY party position in world coordinates; used to translate
 *                     local (gridX, gridY) into world (worldX, worldY).
 */
data class ViewportMap(
    val tiles: Array<Array<TileType>>,
    val partyLocalXY: Pair<Int, Int>,
    val partyWorldXY: Pair<Int, Int>,
) {
    val width: Int get() = tiles[0].size
    val height: Int get() = tiles.size

    fun at(localX: Int, localY: Int): TileType =
        if (localX in 0 until width && localY in 0 until height) tiles[localY][localX]
        else TileType.UNKNOWN

    fun localToWorld(localX: Int, localY: Int): Pair<Int, Int> {
        val (px, py) = partyLocalXY
        val (wx, wy) = partyWorldXY
        return (wx + (localX - px)) to (wy + (localY - py))
    }

    fun worldToLocal(worldX: Int, worldY: Int): Pair<Int, Int>? {
        val (px, py) = partyLocalXY
        val (wx, wy) = partyWorldXY
        val lx = px + (worldX - wx)
        val ly = py + (worldY - wy)
        return if (lx in 0 until width && ly in 0 until height) lx to ly else null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewportMap) return false
        if (partyLocalXY != other.partyLocalXY) return false
        if (partyWorldXY != other.partyWorldXY) return false
        if (tiles.size != other.tiles.size) return false
        return tiles.indices.all { tiles[it].contentEquals(other.tiles[it]) }
    }

    override fun hashCode(): Int {
        var result = partyLocalXY.hashCode()
        result = 31 * result + partyWorldXY.hashCode()
        result = 31 * result + tiles.sumOf { it.contentHashCode() }
        return result
    }

    companion object {
        const val SIZE = 16
        fun ofUnknown(partyWorldXY: Pair<Int, Int>): ViewportMap = ViewportMap(
            tiles = Array(SIZE) { Array(SIZE) { TileType.UNKNOWN } },
            partyLocalXY = SIZE / 2 to SIZE / 2,
            partyWorldXY = partyWorldXY,
        )
    }
}
