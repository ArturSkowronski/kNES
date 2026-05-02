package knes.agent.perception

/**
 * Classifies FF1 NES overworld tile bytes (0x00..0x7F) to TileType.
 *
 * Source: https://datacrystal.tcrf.net/wiki/Final_Fantasy/World_map_data
 *
 * For pathfinding we collapse desert / marsh / tall grass into GRASS (passable
 * but typically encounter-heavy — V2.6 may add cost weights).
 * Caves are mapped to CASTLE (enterable; treated as passable destinations).
 * Unknown bytes (those not in any documented bucket) become UNKNOWN, which is
 * impassable per TileType.isPassable().
 */
object OverworldTileClassifier {

    fun classify(tileId: Int): TileType = when (tileId and 0xFF) {
        // Grass / plains
        0x00, 0x06, 0x07, 0x08, 0x16, 0x18, 0x26, 0x27, 0x28, 0x76,
            // Tall grass — still walkable, treated as grass
        0x54, 0x60, 0x61, 0x70, 0x71,
            // Marsh / swamp — passable, encounter-heavy
        0x55, 0x62, 0x63, 0x72, 0x73,
            // Desert — passable
        0x36, 0x37, 0x42, 0x43, 0x45, 0x52, 0x53,
        -> TileType.GRASS

        // Forest
        0x03, 0x04, 0x05, 0x13, 0x14, 0x15, 0x23, 0x24, 0x25 ->
            TileType.FOREST

        // Mountain (impassable)
        0x10, 0x11, 0x12, 0x20, 0x21, 0x22, 0x30, 0x31, 0x33 ->
            TileType.MOUNTAIN

        // Ocean (impassable on foot)
        0x17 -> TileType.WATER

        // Rivers — impassable without canoe (V2.3 has no canoe mechanic)
        0x40, 0x41, 0x44, 0x50, 0x51 -> TileType.WATER

        // Castles / ruins
        0x01, 0x02, 0x09, 0x0A, 0x0B, 0x0C, 0x1B, 0x1C,
        0x29, 0x2A, 0x38, 0x39, 0x47, 0x48,
        0x56, 0x57, 0x58, 0x59,
        // Caves / grottoes — enter-able destinations, treat as castle
        0x0E, 0x2B, 0x2F, 0x32, 0x34, 0x35, 0x3A,
        0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6E,
        -> TileType.CASTLE

        // Towns / villages
        0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E,
        0x5A, 0x5D, 0x6D ->
            TileType.TOWN

        // Bridges / docks
        0x0F, 0x1F, 0x46, 0x77, 0x78, 0x79, 0x7A ->
            TileType.BRIDGE

        else -> TileType.UNKNOWN
    }
}
