package knes.agent.perception

class InteriorMap(internal val tiles: ByteArray) : ViewportSource {
    init {
        require(tiles.size == WIDTH * HEIGHT) { "tiles must be ${WIDTH * HEIGHT}, got ${tiles.size}" }
    }

    val width: Int get() = WIDTH
    val height: Int get() = HEIGHT

    fun tileAt(x: Int, y: Int): Int {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return 0
        return tiles[y * WIDTH + x].toInt() and 0xFF
    }

    fun classifyAt(x: Int, y: Int): TileType =
        InteriorTileClassifier.classify(tileAt(x, y))

    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val size = ViewportMap.SIZE
        val partyLocal = size / 2 to size / 2
        val (px, py) = partyWorldXY
        val grid = Array(size) { ly ->
            Array(size) { lx ->
                val gx = px + (lx - partyLocal.first)
                val gy = py + (ly - partyLocal.second)
                if (gx in 0 until WIDTH && gy in 0 until HEIGHT) classifyAt(gx, gy)
                else TileType.UNKNOWN
            }
        }
        return ViewportMap(grid, partyLocal, partyWorldXY)
    }

    /**
     * V2.6.2: 64×64 ViewportMap covering the whole interior. Used by InteriorPathfinder
     * so it can BFS the full map and find south-edge / DOOR / STAIRS / WARP exits even
     * when the party is far from them (originally V2.4.6-A in STATE doc; V2.5.x stalls
     * in mapId=24 sub-maps with party at (3, 2) where the 16×16 viewport doesn't reach
     * any exit). Local coords coincide with map coords (party-local = party-world).
     */
    override fun readFullMapView(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val (px, py) = partyWorldXY
        val grid = Array(HEIGHT) { y -> Array(WIDTH) { x -> classifyAt(x, y) } }
        return ViewportMap(grid, partyLocalXY = px to py, partyWorldXY = partyWorldXY)
    }

    companion object {
        const val WIDTH = 64
        const val HEIGHT = 64
    }
}
