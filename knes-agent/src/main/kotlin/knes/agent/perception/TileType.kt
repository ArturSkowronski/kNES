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
    UNKNOWN('?');

    /** Whether the party can walk onto this tile. UNKNOWN is conservatively impassable. */
    fun isPassable(): Boolean = when (this) {
        GRASS, FOREST, ROAD, BRIDGE, TOWN, CASTLE -> true
        MOUNTAIN, WATER, UNKNOWN -> false
    }
}
