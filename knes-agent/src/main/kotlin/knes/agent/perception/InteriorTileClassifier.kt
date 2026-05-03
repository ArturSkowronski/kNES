package knes.agent.perception

/**
 * Classifies FF1 NES interior tile bytes (0x00..0x7F) to TileType.
 *
 * V2.4.2: extended from map-8 (Coneria town) baseline using a histogram across
 * mapIds 0..30. Findings:
 *   - Each map type uses its own outside-of-map padding byte (towns/castles
 *     mostly 0x39; castle/dungeon variants 0x3c, 0x3f, 0x47).
 *   - Walls span 0x30..0x37 (V2.4 saw only 0x30/0x32-0x35 in Coneria town).
 *   - Decorations and furniture occupy 0x00..0x2F generously and are passable.
 *   - 0x40+ tile ids appear in dungeons; bytes around 0x40..0x4E are mostly
 *     passable floor/decoration. 0x44 remains tentatively STAIRS pending
 *     per-map verification.
 *
 * Strategy: explicitly enumerate confirmed walls + paddings. Treat all other
 * ids in 0x00..0x7F as GRASS (passable) — preferring over-permissive over
 * under-permissive, because FF1 maps lean heavily on a few wall ids surrounded
 * by lots of decoration variants. False-passable inside an actual wall would
 * be self-correcting via the WalkOverworldTo / ExitInterior idle-detection
 * (a non-moving step gets fog-marked blocked).
 *
 * NOTE: FF1 interiors do NOT have explicit DOOR tiles. InteriorPathfinder
 * detects south-edge exits geometrically.
 */
object InteriorTileClassifier {
    fun classify(tileId: Int): TileType = when (tileId and 0xFF) {
        // Floor tile common across town/castle interiors (must precede wall range)
        0x31 -> TileType.GRASS
        // Walls (interior-map building outlines + corners)
        0x30, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37 -> TileType.MOUNTAIN
        // Outside-of-map padding (per-map type — collected via histogram of mapIds 0..30)
        0x39, 0x3c, 0x3f, 0x47 -> TileType.UNKNOWN
        // Stairs / activatable tile (verified in Coneria town; tentative elsewhere)
        0x44 -> TileType.STAIRS
        // Everything else in the valid byte range: passable floor / decoration / NPC sprite
        in 0x00..0x7F -> TileType.GRASS
        else -> TileType.UNKNOWN
    }
}
