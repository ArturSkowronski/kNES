package knes.agent.perception

import knes.api.EmulatorSession

/**
 * Reads a 16x16 ViewportMap centered on the party from PPU nametables.
 *
 * The FF1 overworld renders a single nametable (NT0) at any moment; the camera
 * scrolls so the party is roughly centered. We approximate "viewport around
 * the party" by reading NT0 around the screen center (col 16, row 15).
 *
 * Out-of-bounds tiles (beyond NT0 edges) become UNKNOWN.
 */
open class NametableReader(
    private val session: EmulatorSession,
    private val classifier: TileClassifier,
) {
    open fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val size = ViewportMap.SIZE
        val partyLocal = size / 2 to size / 2
        val ntCenterX = 16
        val ntCenterY = 15
        val originCol = ntCenterX - partyLocal.first
        val originRow = ntCenterY - partyLocal.second
        val tiles = Array(size) { ly ->
            Array(size) { lx ->
                val col = originCol + lx
                val row = originRow + ly
                if (col !in 0..31 || row !in 0..29) {
                    TileType.UNKNOWN
                } else {
                    classifier.classify(session.readNametableTile(0, col, row))
                }
            }
        }
        return ViewportMap(tiles, partyLocalXY = partyLocal, partyWorldXY = partyWorldXY)
    }
}
