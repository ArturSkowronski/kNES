package knes.agent.perception

class FrameChangeDetector {
    data class SpriteSlot(val slot: Int, val tileId: Int, val pixelX: Int, val pixelY: Int)

    enum class Mode { OAM, PIXEL_HASH }

    private val partySlotIds: Set<Int> = setOf(0, 1, 2, 3)
    var mode: Mode = Mode.OAM
        private set

    private var prevOam: Set<SpriteSlot>? = null
    private var prevPixelHash: Long? = null
    private var initialized: Boolean = false

    /** Returns true if a vision scan should be triggered this iter. */
    fun shouldScan(currOam: Set<SpriteSlot>?, currPixels: ByteArray): Boolean {
        if (!initialized) {
            initialized = true
            if (currOam == null) mode = Mode.PIXEL_HASH
            prevOam = currOam
            prevPixelHash = pixelHash(currPixels)
            return true
        }
        if (currOam == null) {
            mode = Mode.PIXEL_HASH
        }
        return when (mode) {
            Mode.OAM -> oamShouldTrigger(currOam!!)
            Mode.PIXEL_HASH -> pixelShouldTrigger(currPixels)
        }
    }

    private fun oamShouldTrigger(curr: Set<SpriteSlot>): Boolean {
        val prev = prevOam ?: emptySet()
        prevOam = curr
        val prevNonParty = prev.filter { it.slot !in partySlotIds }.map { it.slot }.toSet()
        val currNonParty = curr.filter { it.slot !in partySlotIds }.map { it.slot }.toSet()
        return currNonParty != prevNonParty
    }

    private fun pixelShouldTrigger(currPixels: ByteArray): Boolean {
        val curr = pixelHash(currPixels)
        val prev = prevPixelHash ?: 0L
        prevPixelHash = curr
        return curr != prev
    }

    private fun pixelHash(pixels: ByteArray): Long {
        if (pixels.isEmpty()) return 0L
        var h = 0xCBF29CE484222325UL.toLong()
        for (ty in 0 until 15) {
            for (tx in 0 until 16) {
                var sum = 0
                for (py in 0 until 16) {
                    for (px in 0 until 16) {
                        val idx = (ty * 16 + py) * 256 + (tx * 16 + px)
                        if (idx < pixels.size) sum += pixels[idx].toInt() and 0xFF
                    }
                }
                val avg = (sum shr 8).toByte()
                h = h xor avg.toLong()
                h *= 0x100000001B3L
            }
        }
        return h
    }
}
