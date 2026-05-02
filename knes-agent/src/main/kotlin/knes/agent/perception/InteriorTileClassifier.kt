package knes.agent.perception

/**
 * Classifies FF1 NES interior tile bytes (0x00..0x7F) to TileType.
 *
 * Built empirically from map-8 (Coneria town) decoded hex grid cross-referenced
 * with FF1 map data:
 *   - 0x39: outside-map padding (UNKNOWN/impassable)
 *   - 0x30, 0x32-0x35: building walls/corners (MOUNTAIN/impassable)
 *   - 0x21, 0x31: floors (GRASS/passable)
 *   - 0x38, 0x3a, 0x3b, 0x3d: in-town decorations (GRASS/passable)
 *   - 0x00..0x1F: furniture/shop items/NPC sprites (GRASS/passable)
 *   - 0x44: isolated tile in town floor — tentatively STAIRS (passable + transition)
 *
 * NOTE: FF1 town/castle interiors do NOT have explicit DOOR tiles. The engine
 * handles "walk off south edge of playable area" implicitly. InteriorPathfinder
 * detects the south-edge exit geometrically.
 *
 * Anything outside these buckets returns UNKNOWN (conservative — impassable).
 * Future maps may surface tile ids that need adding (Marsh Cave, towers, etc.).
 */
object InteriorTileClassifier {
    fun classify(tileId: Int): TileType = when (tileId and 0xFF) {
        // Furniture / shop sprites / decorations (passable in towns)
        in 0x00..0x1F -> TileType.GRASS
        // Floors
        0x21, 0x31 -> TileType.GRASS
        // Walls (building outlines)
        0x30, 0x32, 0x33, 0x34, 0x35 -> TileType.MOUNTAIN
        // In-town decoration props (lamp post / sign / well)
        0x38, 0x3a, 0x3b, 0x3d -> TileType.GRASS
        // Outside-of-map padding
        0x39 -> TileType.UNKNOWN
        // Stairs / activatable tile
        0x44 -> TileType.STAIRS
        else -> TileType.UNKNOWN
    }
}
