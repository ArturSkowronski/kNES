package knes.agent.perception

/**
 * Producer of ViewportMaps centered on the party. Two views:
 *  - readViewport: 16x16 around party (ASCII rendering / advisor prompts).
 *  - readFullMapView: 256x256 overworld for global pathfinding so the planner
 *    can route around large blockers (Coneria town etc.) that span the entire
 *    16x16 window. Test fakes can implement only readViewport; the default
 *    implementation falls back to it so legacy 16x16 tests keep working.
 */
interface ViewportSource {
    fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap
    fun readFullMapView(partyWorldXY: Pair<Int, Int>): ViewportMap = readViewport(partyWorldXY)
}
