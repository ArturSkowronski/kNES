package knes.agent.perception

/**
 * Minimal interface for anything that can produce a 16x16 ViewportMap centered on
 * the party's current world coordinate. Lets WalkOverworldTo / SkillRegistry depend
 * on a small contract instead of the full OverworldMap (which requires ROM bytes),
 * keeping unit tests light.
 */
fun interface ViewportSource {
    fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap
}
