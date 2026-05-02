package knes.agent.pathfinding

data class PathResult(
    val found: Boolean,
    val steps: List<Direction>,
    val reachedTile: Pair<Int, Int>,
    val searchSpace: SearchSpace,
    val partial: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun blocked(from: Pair<Int, Int>, reason: String, space: SearchSpace = SearchSpace.VIEWPORT) =
            PathResult(false, emptyList(), from, space, partial = false, reason = reason)
    }
}
