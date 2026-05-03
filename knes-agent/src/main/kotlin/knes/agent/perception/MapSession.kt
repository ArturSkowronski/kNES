package knes.agent.perception

/**
 * Caches the currently-loaded interior map. Reloads + clears fog on map transition.
 * Implements ViewportSource so it can plug into the same advisor / pathfinder
 * code paths as OverworldMap.
 */
class MapSession(
    private val loader: InteriorMapLoader,
    private val fog: FogOfWar,
) : ViewportSource {
    private var cachedId: Int = -1
    var currentMap: InteriorMap? = null
        private set

    fun ensureCurrent(mapId: Int) {
        if (mapId == cachedId && currentMap != null) return
        currentMap = loader.load(mapId)
        cachedId = mapId
        fog.clear()
    }

    val currentMapId: Int get() = cachedId

    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val map = currentMap ?: error("MapSession.readViewport called before ensureCurrent")
        return map.readViewport(partyWorldXY)
    }

    /** V2.6.2: full 64×64 interior view for BFS over the whole map (vs 16×16 advisor view). */
    override fun readFullMapView(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val map = currentMap ?: error("MapSession.readFullMapView called before ensureCurrent")
        return map.readFullMapView(partyWorldXY)
    }
}
