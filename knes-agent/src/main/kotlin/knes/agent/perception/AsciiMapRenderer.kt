package knes.agent.perception

object AsciiMapRenderer {

    /**
     * Renders the viewport as an ASCII grid with world-coord axis labels and a legend.
     * `@` marks party; `X` marks fog-confirmed-blocked tiles (overrides terrain);
     * `?` marks UNKNOWN tiles.
     */
    fun render(vm: ViewportMap, fog: FogOfWar): String {
        val sb = StringBuilder()
        val (pwx, pwy) = vm.partyWorldXY
        sb.append("WORLD VIEW (party at world coord $pwx,$pwy; viewport ${vm.width}x${vm.height}):\n\n")

        // Column header (world X coords every 2 tiles to keep width).
        sb.append("     ")
        for (lx in 0 until vm.width) {
            val (wx, _) = vm.localToWorld(lx, 0)
            if (lx % 2 == 0) sb.append(String.format("%3d ", wx)) else sb.append("    ")
        }
        sb.append('\n')

        for (ly in 0 until vm.height) {
            val (_, wy) = vm.localToWorld(0, ly)
            sb.append(String.format("%3d  ", wy))
            for (lx in 0 until vm.width) {
                val (wx, wyT) = vm.localToWorld(lx, ly)
                val glyph = when {
                    lx == vm.partyLocalXY.first && ly == vm.partyLocalXY.second -> '@'
                    fog.isBlocked(wx, wyT) -> 'X'
                    else -> vm.tiles[ly][lx].glyph
                }
                sb.append(' ').append(glyph).append("  ")
            }
            sb.append('\n')
        }

        sb.append("\nLegend: @ party, . grass, ^ mountain, ~ water, F forest,\n")
        sb.append("        R road, B bridge, T town, C castle, ? unseen, X blocked-confirmed\n")

        sb.append("\nFOG STATS: ${fog.size} tiles visited")
        fog.bbox()?.let { (mn, mx) ->
            sb.append(", bbox (${mn.first}-${mx.first}, ${mn.second}-${mx.second})")
        }
        sb.append(".\n")

        val recentBlocked = fog.blockedTiles()
        if (recentBlocked.isNotEmpty()) {
            sb.append("BLOCKED TILES: ")
                .append(recentBlocked.take(8).joinToString { "(${it.first},${it.second})" })
            if (recentBlocked.size > 8) sb.append(" …")
            sb.append('\n')
        }
        return sb.toString()
    }
}
