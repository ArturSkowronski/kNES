package knes.agent.perception

enum class TileType(val glyph: Char) {
    GRASS('.'),
    FOREST('F'),
    MOUNTAIN('^'),
    WATER('~'),
    BRIDGE('B'),
    ROAD('R'),
    TOWN('T'),
    CASTLE('C'),
    DOOR('D'),
    STAIRS('>'),
    WARP('*'),
    UNKNOWN('?');

    /** Whether the party can walk onto this tile. UNKNOWN is conservatively impassable.
     *  DOOR / STAIRS / WARP are walkable destinations even though they trigger map transitions. */
    fun isPassable(): Boolean = when (this) {
        GRASS, FOREST, ROAD, BRIDGE, TOWN, CASTLE, DOOR, STAIRS, WARP -> true
        MOUNTAIN, WATER, UNKNOWN -> false
    }

    /**
     * Movement cost used by overworld pathfinder. Higher = avoid unless on path.
     * TOWN/CASTLE walking onto them triggers an interior transition the agent then
     * has to escape from — for goal-directed travel they should be detours, not shortcuts.
     */
    fun cost(): Int = when (this) {
        TOWN, CASTLE -> 50
        else -> 1
    }

    /**
     * Hard-impassable when traversed (not the destination). V2.4.6 evidence: cost-50
     * weighting alone fails when TOWN/CASTLE blobs span the full 16×16 viewport — the
     * pathfinder still routes through them because there's no alternative. With this
     * rule the pathfinder skips these tiles entirely *unless* they are the explicit
     * goal, embodying the LLM's intent: "go to town" → enter; "pass by town" → reroute.
     */
    fun isImpassableTransit(): Boolean = when (this) {
        TOWN, CASTLE -> true
        else -> false
    }
}
