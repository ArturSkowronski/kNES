package knes.agent.pathfinding

/** FF1 overworld is 4-way only. */
enum class Direction(val dx: Int, val dy: Int, val button: String) {
    N(0, -1, "UP"),
    S(0, 1, "DOWN"),
    E(1, 0, "RIGHT"),
    W(-1, 0, "LEFT");
}
