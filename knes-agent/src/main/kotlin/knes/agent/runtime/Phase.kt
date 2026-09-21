package knes.agent.runtime

import knes.agent.tools.results.AgentObservationBuilder
import knes.agent.tools.results.AgentPhase

/**
 * What the agent is doing this turn.
 *
 * Everything except [CartographerExplore] mirrors [AgentPhase] and is classified from
 * RAM by the active profile's semantics — the rules live in `profiles/<id>.json`, not
 * here. [CartographerExplore] is an agent-owned mode that no RAM field expresses, so
 * only the Cartographer sets it.
 */
enum class Phase {
    Boot, Overworld, Town, Indoors, Battle, MenuStuck, Unknown, CartographerExplore;

    companion object {
        fun of(observed: AgentPhase): Phase = when (observed) {
            AgentPhase.Boot -> Boot
            AgentPhase.Overworld -> Overworld
            AgentPhase.Town -> Town
            AgentPhase.Indoors -> Indoors
            AgentPhase.Battle -> Battle
            AgentPhase.MenuStuck -> MenuStuck
            AgentPhase.Unknown -> Unknown
        }

        /**
         * Classify a watched-RAM snapshot through [profileId]'s semantics.
         *
         * [Unknown] means the profile's rules matched nothing. With a profile that
         * watches the fields its own rules name, that should not happen — treat it as a
         * profile bug rather than a state to handle.
         */
        fun fromRam(ram: Map<String, Int>, profileId: String): Phase =
            of(AgentObservationBuilder.phaseFor(ram, profileId))
    }
}

/**
 * Phases whose RAM is expected to sit still, so the Watchdog must not count them as
 * stuck. Empty until the profile semantics can actually report a waiting state — see
 * task G1 (dialog/menu state in observations).
 */
val PHASE_STATIC_WHITELIST: Set<Phase> = emptySet()
