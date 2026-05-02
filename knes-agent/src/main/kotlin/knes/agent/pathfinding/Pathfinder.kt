package knes.agent.pathfinding

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportMap

interface Pathfinder {
    fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        viewport: ViewportMap,
        fog: FogOfWar,
    ): PathResult
}
