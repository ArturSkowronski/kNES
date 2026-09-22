package knes.debug

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What an agent is allowed to want in this game, and how to describe the game to it.
 *
 * The companion piece to [ProfileSemantics]: that says which phase the RAM means, this says
 * what to *do* about it. Both live in `resources/profiles/<id>.json`, for the same reason —
 * a goal list written in Kotlin is a game constant in runtime code, and the first one to
 * notice was Super Mario Bros, which arrived to find `boot`, `walkTo` and `press_a` waiting
 * for it along with Final Fantasy's shop warnings.
 *
 * Everything here is data. The one goal that needs code is the one that follows the
 * Advisor's plan, and it is named by [GoalRule.PLAN_STEP] rather than special-cased by id.
 *
 * Optional: a profile without an `"agent"` key yields no goals, and the agent falls back to
 * asking a chat model, which is what it did before any of this existed.
 */
@Serializable
data class ProfileAgent(
    /**
     * What the decision model is asked, once per turn.
     *
     * Per game because the words matter: Final Fantasy's "the party" was being asked in
     * front of a picture of Mario.
     */
    val question: String = "Which of these should the player do on this turn?",

    /**
     * How large a frame a pixel-reading decision model should look at, as `WxH`.
     *
     * Per game because the thing that has to be noticed is a different size. Final Fantasy
     * is a grid of 16x16 tiles and reads fine at the NES's own 256x240; a gap in Mario's
     * floor two tiles ahead is a handful of pixels there, and doubling the frame took him
     * from zero jumps to six.
     */
    val frame: String? = null,

    /** Lines of state handed to the model, in order, naming only fields this game has. */
    val state: List<StateLine> = emptyList(),

    /** Everything the agent may choose between, before any of them are filtered. */
    val goals: List<GoalRule> = emptyList(),

    /**
     * How to read the level's collision geometry out of memory, when the game keeps one.
     *
     * A profile names the handful of addresses a game turns on, which is the right shape for
     * a coordinate and the wrong one for a map. Without this the agent is told where the
     * player is and nothing about what is in front of him — which is exactly how a decision
     * model walks into a pit while ranking "walk right" at 0.92.
     */
    val map: TileMap? = null,

    /** Where the moving hazards are, for games that keep them in fixed slots. */
    val sprites: SpriteTable? = null,
) {
    /** The state lines this snapshot can actually fill in; a missing field says nothing. */
    fun stateLines(ram: Map<String, Int>): List<String> = state.mapNotNull { it.render(ram) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = mutableMapOf<String, ProfileAgent?>()

        fun get(profileId: String): ProfileAgent? {
            val key = profileId.lowercase()
            if (!cache.containsKey(key)) cache[key] = load(key)
            return cache[key]
        }

        /** Override or inject an agent config at runtime, mirroring [ProfileSemantics.register]. */
        fun register(profileId: String, agent: ProfileAgent?) {
            cache[profileId.lowercase()] = agent
        }

        private fun load(profileId: String): ProfileAgent? {
            val text = ProfileAgent::class.java.classLoader
                .getResourceAsStream("profiles/$profileId.json")
                ?.bufferedReader()?.readText() ?: return null
            return try {
                val node = json.parseToJsonElement(text).jsonObject["agent"] ?: return null
                json.decodeFromJsonElement(serializer(), node)
            } catch (e: Exception) {
                System.err.println("Failed to load agent config for profile $profileId: ${e.message}")
                null
            }
        }
    }
}

/**
 * One line of the state the decision model reads.
 *
 * Either a labelled number — `gold: 400` — or, with [say], a whole sentence chosen by the
 * value, for a byte whose meaning is worth spelling out rather than leaving as a digit.
 */
@Serializable
data class StateLine(
    val field: String,
    /** Prefix for the raw value. Ignored when [say] matches. */
    val label: String? = null,
    /** Value to sentence. The key `"*"` is the fallback for any other value. */
    val say: Map<String, String> = emptyMap(),
    /**
     * Further bytes of the same number, least significant first after [field].
     *
     * A console keeps a number larger than 255 across several addresses, and the digits are
     * meaningless apart: Final Fantasy's gold is three bytes, and a state line reading
     * `goldLow: 144` tells a model nothing about whether the party can afford a sword.
     */
    val plusBytes: List<String> = emptyList(),
) {
    fun render(ram: Map<String, Int>): String? {
        var value = ram[field] ?: return null
        plusBytes.forEachIndexed { i, name -> value += (ram[name] ?: 0) shl (8 * (i + 1)) }
        if (say.isNotEmpty()) return say[value.toString()] ?: say["*"]
        return "${label ?: field}: $value"
    }
}

/**
 * A window on the level's collision geometry, rendered as a small ASCII map.
 *
 * Super Mario Bros keeps the screen's tiles in a buffer of [pages] pages, each [rows] by
 * [cols], one byte a tile: non-zero means something solid. Everything here is addresses and
 * shape, so another game with a tile buffer is another JSON block rather than another class.
 *
 * The window is given in tiles around the player, and the map is drawn with the player at a
 * fixed spot in it, so the model reads the same picture from turn to turn.
 */
@Serializable
data class TileMap(
    /** First byte of the buffer, as `"0x0500"`. */
    val base: String,
    val cols: Int,
    val rows: Int,
    val pages: Int = 1,
    /** Pixels a tile covers, in both directions. */
    val tile: Int = 16,
    /** Screen pixels above the first row of the buffer — the status bar, usually. */
    val originY: Int = 0,
    /** Tiles left, right, up and down of the player that the window covers. */
    val left: Int = 2,
    val right: Int = 8,
    val up: Int = 4,
    val down: Int = 4,
    /**
     * Whether to put the map in the state the decision model reads.
     *
     * Off for Super Mario Bros, and the reason is measured rather than assumed. A correct
     * map made a vision model *worse*: 440 turns reached 1662 px with the screen alone and
     * 817 px with the screen plus the map, because a model given two descriptions of the
     * same thing has to reconcile them. The same map with no screen at all — the shape the
     * reference Jev harness uses — reached 296 px and spent 331 of 440 turns standing still.
     *
     * The map stays declared because it is verified correct (`MarioTileMapTest`) and because
     * a model trained for structured decisions would want it. This switch is which.
     */
    val inState: Boolean = true,
    val solid: String = "#",
    val empty: String = ".",
    val player: String = "M",
    val enemy: String = "E",
) {
    val width: Int get() = left + right + 1
    val height: Int get() = up + down + 1
    val baseAddress: Int get() = Integer.decode(base)

    /** Where in the buffer a screen pixel lands, or null when it is off the buffer. */
    fun addressOf(x: Int, y: Int): Int? {
        val page = if (pages <= 1) 0 else (x / (cols * tile)) % pages
        val column = (x % (cols * tile)) / tile
        val row = (y - originY) / tile
        if (row < 0 || row >= rows) return null
        return baseAddress + page * rows * cols + row * cols + column
    }
}

/**
 * Enemies kept in a fixed number of slots, the way the NES era did it.
 *
 * A count of active enemies says a hazard exists; where it is and which way it is heading is
 * what decides whether to jump.
 */
@Serializable
data class SpriteTable(
    val count: Int,
    /** Non-zero while the slot holds something. */
    val active: String,
    /** The slot's kind; zero means empty even when [active] is set. */
    val kind: String,
    /** Position, high byte and low byte of x. */
    val xHigh: String,
    val xLow: String,
    val y: String,
) {
    private fun at(spec: String, slot: Int) = Integer.decode(spec) + slot

    fun activeAddress(slot: Int) = at(active, slot)
    fun kindAddress(slot: Int) = at(kind, slot)
    fun xHighAddress(slot: Int) = at(xHigh, slot)
    fun xLowAddress(slot: Int) = at(xLow, slot)
    fun yAddress(slot: Int) = at(y, slot)
}

/**
 * One thing the agent may want, and the conditions under which it may want it.
 *
 * Modelled on Minecraft's `Goal`: it answers whether it can run at all, and the ones that
 * can become the declared options of a typed decision. [priority] orders them when nothing
 * ranks them, and decides which survive when more apply than the readout has answer slots.
 */
@Serializable
data class GoalRule(
    val id: String,
    val priority: Int,
    /** The one sentence the model reads. Say what it does, not why it is a good idea. */
    val description: String,

    /** The tool to dispatch, or [PLAN_STEP] for the goal that follows the Advisor's plan. */
    val tool: String,
    val args: Map<String, String> = emptyMap(),

    /** Phases this may run in. Empty means any. */
    val phases: List<String> = emptyList(),
    /** Phases this may never run in, checked after [phases]. */
    val notPhases: List<String> = emptyList(),
    /** Every condition must hold. */
    @SerialName("when") val conditions: List<RamCondition> = emptyList(),

    /**
     * Turns this may spend changing nothing before it comes off the menu.
     *
     * Minecraft asks a running goal `canContinueToUse()` every tick; ceasing to be an option
     * is the only way a goal can say no here. Per game because the games differ: Final
     * Fantasy's world waits, so three tries that moved nothing means blocked, while Mario's
     * moves on its own and a press that changed nothing is often just a press made mid-air.
     */
    val retryLimit: Int = 3,

    /**
     * Also offer this when nothing has moved for this many turns, whatever the phase says.
     *
     * For the way out of a menu the phase classifier does not recognise as a menu: from
     * outside, that looks exactly like a screen that keeps changing while the player never
     * does.
     */
    val alsoWhenStuckFor: Int? = null,

    /**
     * For [PLAN_STEP] only: tools that make sense in some phases and nowhere else.
     *
     * The Advisor's opening plan always starts with `boot`, and the turn loop has already
     * booted by the time it runs; taking that step at its word pressed START on a running
     * game and opened a menu the classifier did not recognise for the rest of the run.
     */
    val phaseGuards: Map<String, List<String>> = emptyMap(),
) {
    /** Everything except the stall rule, which needs history this does not see. */
    fun applies(phase: String, ram: Map<String, Int>): Boolean {
        if (phases.isNotEmpty() && phase !in phases) return false
        if (phase in notPhases) return false
        return conditions.all { it.matches(ram) }
    }

    /** Whether [tool] is allowed in [phase], for a plan step naming it. */
    fun guardAllows(tool: String, phase: String): Boolean =
        phaseGuards[tool]?.contains(phase) ?: true

    companion object {
        /** The one goal that is code rather than data: it dispatches whatever the plan says. */
        const val PLAN_STEP = "planStep"
    }
}
