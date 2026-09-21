package knes.agent.tools.results

import knes.debug.ProfileSemantics

/**
 * Everything a runtime agent is allowed to know about the game it is playing.
 *
 * Tools ask this instead of reading RAM addresses, so the game's constants stay in
 * `profiles/<id>.json`. A profile with no semantics answers "I don't know" to every
 * question rather than guessing, which is what a tool needs in order to reject cleanly.
 */
class GameSemantics private constructor(
    val profileId: String?,
    private val semantics: ProfileSemantics?,
) {
    val known: Boolean get() = semantics != null

    fun phase(ram: Map<String, Int>): AgentPhase = AgentObservationBuilder.phaseFor(ram, profileId)

    /** True while the game is mid-transition, so its RAM should not be acted on yet. */
    fun isTransitioning(ram: Map<String, Int>): Boolean =
        semantics?.signals?.isTransitioning(ram) ?: false

    /**
     * Opaque identity of the map or overlay the party is on. Compare two of these to
     * tell whether a step changed location; an empty list means the profile cannot say.
     */
    fun locationIdentity(ram: Map<String, Int>): List<Int> =
        semantics?.signals?.locationIdentity(ram) ?: emptyList()

    /**
     * Opaque fingerprint of menu/dialog state, for telling "my taps did nothing" from
     * "my taps opened something". Empty means the profile cannot say.
     */
    fun menuFingerprint(ram: Map<String, Int>): List<Int> =
        semantics?.signals?.menuFingerprint(ram) ?: emptyList()

    /** Party position in local (map) coordinates, or null if the profile has no mapping. */
    fun localPosition(ram: Map<String, Int>): Pair<Int, Int>? {
        val position = semantics?.position ?: return null
        val x = position.localX(ram) ?: return null
        val y = position.localY(ram) ?: return null
        return x to y
    }

    /** Party position in world coordinates, or null if the profile has no mapping. */
    fun worldPosition(ram: Map<String, Int>): Pair<Int, Int>? {
        val position = semantics?.position ?: return null
        val x = position.worldX(ram) ?: return null
        val y = position.worldY(ram) ?: return null
        return x to y
    }

    companion object {
        fun of(profileId: String?): GameSemantics =
            GameSemantics(profileId, profileId?.let { ProfileSemantics.get(it) })
    }
}
