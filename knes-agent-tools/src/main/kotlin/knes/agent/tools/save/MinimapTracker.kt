package knes.agent.tools.save

import java.util.BitSet
import java.util.Base64

class MinimapTracker(
    val width: Int = 32,
    val height: Int = 32,
    private val bits: BitSet = BitSet(width * height),
) {
    fun markVisited(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        bits.set(y * width + x)
    }

    fun isVisited(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return bits.get(y * width + x)
    }

    fun toSnapshot(): VisitedMinimap {
        val byteCount = (width * height + 7) / 8
        val raw = bits.toByteArray()
        val padded = ByteArray(byteCount)
        System.arraycopy(raw, 0, padded, 0, minOf(raw.size, byteCount))
        return VisitedMinimap(
            width = width,
            height = height,
            bitsBase64 = Base64.getEncoder().encodeToString(padded),
        )
    }

    companion object {
        fun fromSnapshot(snap: VisitedMinimap): MinimapTracker {
            val bytes = if (snap.bitsBase64.isEmpty()) ByteArray(0)
                else Base64.getDecoder().decode(snap.bitsBase64)
            val bs = BitSet.valueOf(bytes)
            return MinimapTracker(width = snap.width, height = snap.height, bits = bs)
        }
    }
}
