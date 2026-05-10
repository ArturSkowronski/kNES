package knes.agent.runtime

import knes.agent.perception.OverworldMap
import knes.agent.perception.TileType
import kotlin.math.abs

/**
 * Picks a grind anchor near the party using overworld tile classifications.
 * Encounter zones are GRASS or FOREST (FF1 mechanic, user-confirmed).
 * If the party already stands on an encounter tile, the anchor IS the party
 * tile. Otherwise the closest encounter tile within `radius` is picked,
 * breaking ties by preferring south (dy>0) then east (dx>0).
 */
object GrindAnchorSelector {
    data class Pick(
        val anchor: Pair<Int, Int>,
        val tileClass: TileType,
        val fellBack: Boolean,
    )

    fun pickGrindAnchor(
        party: Pair<Int, Int>,
        map: OverworldMap,
        radius: Int = 2,
    ): Pick {
        val (px, py) = party
        val partyClass = map.classifyAt(px, py)
        if (partyClass == TileType.GRASS || partyClass == TileType.FOREST) {
            return Pick(party, partyClass, fellBack = false)
        }
        var best: Pick? = null
        var bestDist = Int.MAX_VALUE
        var bestSouthBias = Int.MIN_VALUE
        var bestEastBias = Int.MIN_VALUE
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx == 0 && dy == 0) continue
                val tx = px + dx
                val ty = py + dy
                val klass = map.classifyAt(tx, ty)
                if (klass != TileType.GRASS && klass != TileType.FOREST) continue
                val dist = abs(dx) + abs(dy)
                val southBias = if (dy > 0) dy else 0
                val eastBias = if (dx > 0) dx else 0
                val better = when {
                    dist < bestDist -> true
                    dist == bestDist && southBias > bestSouthBias -> true
                    dist == bestDist && southBias == bestSouthBias && eastBias > bestEastBias -> true
                    else -> false
                }
                if (better) {
                    best = Pick(tx to ty, klass, fellBack = false)
                    bestDist = dist
                    bestSouthBias = southBias
                    bestEastBias = eastBias
                }
            }
        }
        return best ?: Pick(party, partyClass, fellBack = true)
    }
}
