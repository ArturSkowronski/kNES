package knes.debug

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Versioned, game-specific interpretation rules for watched RAM.
 *
 * [GameProfile] says *where* a value lives; semantics say what it *means* — which phase the
 * game is in, which RAM fields carry party position, and which landmark the party is near.
 * Keeping this in the profile JSON is what stops game constants from leaking into runtime
 * agent code.
 *
 * Semantics live under a `"semantics"` key in the same `resources/profiles/<id>.json` file as
 * the address map, and are optional — a profile without them yields no phase or location hint.
 */
@Serializable
data class ProfileSemantics(
    val version: Int = 1,
    /** Phase name reported when no rule in [phases] matches. */
    val unknownPhase: String = "Unknown",
    val position: PositionMapping = PositionMapping(),
    val phases: List<PhaseRule> = emptyList(),
    val landmarks: List<LandmarkRule> = emptyList(),
    val signals: SignalMapping = SignalMapping()
) {
    /** First matching rule wins, so order [phases] most-specific first. */
    fun phaseFor(ram: Map<String, Int>): String =
        phases.firstOrNull { it.matches(ram) }?.phase ?: unknownPhase

    /** First matching landmark wins. */
    fun landmarkFor(phase: String, ram: Map<String, Int>): LandmarkRule? =
        landmarks.firstOrNull { it.matches(phase, ram, position) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = mutableMapOf<String, ProfileSemantics?>()

        fun get(profileId: String): ProfileSemantics? {
            val key = profileId.lowercase()
            if (!cache.containsKey(key)) cache[key] = load(key)
            return cache[key]
        }

        /** Override or inject semantics at runtime, mirroring [GameProfile.register]. */
        fun register(profileId: String, semantics: ProfileSemantics?) {
            cache[profileId.lowercase()] = semantics
        }

        private fun load(profileId: String): ProfileSemantics? {
            val text = ProfileSemantics::class.java.classLoader
                .getResourceAsStream("profiles/$profileId.json")
                ?.bufferedReader()?.readText() ?: return null
            return try {
                val node = json.parseToJsonElement(text).jsonObject["semantics"] ?: return null
                json.decodeFromJsonElement(serializer(), node)
            } catch (e: Exception) {
                System.err.println("Failed to load semantics for profile $profileId: ${e.message}")
                null
            }
        }
    }
}

/**
 * Game facts that are neither a phase nor a place, but that tools have to ask about:
 * is the game mid-transition, which map are we on, and is a menu open.
 *
 * Without these, every tool ends up reading raw RAM addresses and the game leaks back
 * into the runtime.
 */
@Serializable
data class SignalMapping(
    /** All conditions hold while the game is mid-transition and its RAM cannot be trusted. */
    val transitioning: List<RamCondition> = emptyList(),
    /** Fields that together answer "which map or overlay are we on"; a change means we moved. */
    val locationIdentity: List<RamField> = emptyList(),
    /** Fields that together fingerprint menu/dialog state, for detecting a no-op interaction. */
    val menuFingerprint: List<RamField> = emptyList()
) {
    fun isTransitioning(ram: Map<String, Int>): Boolean =
        transitioning.isNotEmpty() && transitioning.all { it.matches(ram) }

    fun locationIdentity(ram: Map<String, Int>): List<Int> = locationIdentity.map { it.read(ram) }

    fun menuFingerprint(ram: Map<String, Int>): List<Int> = menuFingerprint.map { it.read(ram) }
}

/** One watched field, optionally narrowed to some of its bits. */
@Serializable
data class RamField(val field: String, val mask: Int? = null, val default: Int = 0) {
    fun read(ram: Map<String, Int>): Int {
        val value = ram[field] ?: default
        return if (mask != null) value and mask else value
    }
}

/** Which RAM fields carry position, in priority order — the first field present wins. */
@Serializable
data class PositionMapping(
    @SerialName("worldX") val worldXFields: List<String> = emptyList(),
    @SerialName("worldY") val worldYFields: List<String> = emptyList(),
    @SerialName("localX") val localXFields: List<String> = emptyList(),
    @SerialName("localY") val localYFields: List<String> = emptyList()
) {
    fun worldX(ram: Map<String, Int>): Int? = pick(worldXFields, ram)
    fun worldY(ram: Map<String, Int>): Int? = pick(worldYFields, ram)
    fun localX(ram: Map<String, Int>): Int? = pick(localXFields, ram)
    fun localY(ram: Map<String, Int>): Int? = pick(localYFields, ram)

    private fun pick(fields: List<String>, ram: Map<String, Int>): Int? =
        fields.firstNotNullOfOrNull { ram[it] }
}

/** A named phase plus the RAM conditions that identify it. All conditions must hold. */
@Serializable
data class PhaseRule(
    val phase: String,
    @SerialName("when") val conditions: List<RamCondition> = emptyList()
) {
    fun matches(ram: Map<String, Int>): Boolean =
        conditions.isNotEmpty() && conditions.all { it.matches(ram) }
}

/**
 * One test against a watched RAM field. Every non-null bound must hold.
 *
 * A field missing from the snapshot fails the condition unless [default] supplies the value
 * the game would have there.
 */
@Serializable
data class RamCondition(
    val field: String,
    val equals: Int? = null,
    val notEquals: Int? = null,
    val bitSet: Int? = null,
    val bitClear: Int? = null,
    val atLeast: Int? = null,
    val atMost: Int? = null,
    val default: Int? = null
) {
    fun matches(ram: Map<String, Int>): Boolean {
        val value = ram[field] ?: default ?: return false
        if (equals != null && value != equals) return false
        if (notEquals != null && value == notEquals) return false
        if (bitSet != null && (value and bitSet) == 0) return false
        if (bitClear != null && (value and bitClear) != 0) return false
        if (atLeast != null && value < atLeast) return false
        if (atMost != null && value > atMost) return false
        return true
    }
}

/** Inclusive bounds on a coordinate. */
@Serializable
data class IntBounds(val min: Int, val max: Int) {
    operator fun contains(value: Int): Boolean = value in min..max
}

/**
 * A known place, recognised from phase plus world coordinates.
 *
 * [confidence] and [reason] are carried through to the agent so it can tell a strong anchor
 * from a guess.
 */
@Serializable
data class LandmarkRule(
    val id: String,
    val name: String,
    /** Phases this landmark applies to; empty means any phase. */
    val phases: List<String> = emptyList(),
    val worldX: IntBounds? = null,
    val worldY: IntBounds? = null,
    val confidence: Double,
    val reason: String
) {
    fun matches(phase: String, ram: Map<String, Int>, position: PositionMapping): Boolean {
        if (phases.isNotEmpty() && phase !in phases) return false
        if (worldX != null && position.worldX(ram)?.let { it in worldX } != true) return false
        if (worldY != null && position.worldY(ram)?.let { it in worldY } != true) return false
        return true
    }
}
