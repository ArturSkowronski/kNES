package knes.agent.tools.results

import knes.debug.ProfileSemantics
import kotlinx.serialization.Serializable

@Serializable
enum class AgentPhase {
    Boot,
    Overworld,
    Town,
    Indoors,
    Battle,
    MenuStuck,
    Unknown,
}

@Serializable
data class AgentPosition(
    val worldX: Int? = null,
    val worldY: Int? = null,
    val localX: Int? = null,
    val localY: Int? = null,
)

@Serializable
data class LocationHint(
    val id: String,
    val name: String,
    val confidence: Double,
    val reason: String,
)

@Serializable
data class AgentObservation(
    val frame: Int,
    val phase: AgentPhase,
    val position: AgentPosition,
    val location: LocationHint? = null,
    val ram: Map<String, Int>,
    val cpu: Map<String, Int>,
    val heldButtons: List<String>,
    val screenshot: ScreenPng? = null,
    /** Profile whose semantics produced [phase], [position] and [location]; null means none applied. */
    val profileId: String? = null,
)

/**
 * Turns a raw [StateSnapshot] into an agent-oriented observation.
 *
 * All game knowledge comes from the profile's [ProfileSemantics]; this builder holds no
 * per-game constants. Without a profile the snapshot is passed through with an unknown phase.
 */
object AgentObservationBuilder {
    fun from(
        state: StateSnapshot,
        screen: ScreenPng? = null,
        profileId: String? = null,
    ): AgentObservation {
        val semantics = profileId?.let { ProfileSemantics.get(it) }
        val phase = phaseFor(state.ram, profileId)

        return AgentObservation(
            frame = state.frame,
            phase = phase,
            position = semantics?.position?.let {
                AgentPosition(
                    worldX = it.worldX(state.ram),
                    worldY = it.worldY(state.ram),
                    localX = it.localX(state.ram),
                    localY = it.localY(state.ram),
                )
            } ?: AgentPosition(),
            location = semantics?.landmarkFor(phase.name, state.ram)?.let {
                LocationHint(it.id, it.name, it.confidence, it.reason)
            },
            ram = state.ram,
            cpu = state.cpu,
            heldButtons = state.heldButtons,
            screenshot = screen,
            profileId = semantics?.let { profileId },
        )
    }

    /**
     * Phase on its own, for callers that already hold a RAM snapshot and do not need a
     * full observation. Shares the rules [from] uses — there is one classifier, not two.
     */
    fun phaseFor(ram: Map<String, Int>, profileId: String?): AgentPhase {
        val semantics = profileId?.let { ProfileSemantics.get(it) } ?: return AgentPhase.Unknown
        return toPhase(semantics.phaseFor(ram))
    }

    /** Phases are named in profile JSON; anything the contract does not know is [AgentPhase.Unknown]. */
    private fun toPhase(name: String): AgentPhase =
        AgentPhase.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AgentPhase.Unknown
}
