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
}
