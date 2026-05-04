package knes.agent.pathfinding

data class PathResult(
    val found: Boolean,
    val steps: List<Direction>,
    val reachedTile: Pair<Int, Int>,
    val searchSpace: SearchSpace,
    val partial: Boolean,
    val reason: String? = null,
    /**
     * V5.14 GPP-lesson "richer no-path feedback": when path doesn't exist
     * (or only a partial path is returned), this is the BFS-closest tile to
     * the requested target that the searcher could reach. Null when the
     * pathfinder couldn't move from start at all (everything around is
     * impassable).
     */
    val closestReachable: Pair<Int, Int>? = null,
    /**
     * V5.14: was the requested target tile itself walkable in the viewport?
     * `false` here distinguishes "target is WATER/MOUNTAIN/UNKNOWN, no path
     * possible no matter what" from "target is fine, but blocked by terrain
     * between here and there". Null when target is outside the viewport.
     */
    val targetPassable: Boolean? = null,
) {
    companion object {
        fun blocked(
            from: Pair<Int, Int>,
            reason: String,
            space: SearchSpace = SearchSpace.VIEWPORT,
            closestReachable: Pair<Int, Int>? = null,
            targetPassable: Boolean? = null,
        ) = PathResult(
            false, emptyList(), from, space, partial = false,
            reason = reason,
            closestReachable = closestReachable,
            targetPassable = targetPassable,
        )
    }
}
