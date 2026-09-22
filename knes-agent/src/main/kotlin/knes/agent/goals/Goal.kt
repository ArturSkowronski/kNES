package knes.agent.goals

import knes.agent.runtime.Phase
import knes.agent.runtime.PlanStep

/**
 * What a turn actually amounted to.
 *
 * Not whether the tool returned Ok, and not even whether the player moved — whether the
 * turn reached ground the player had not been standing on. Both weaker readings were tried
 * and both failed in the same way: `sequence(Up)` reports Ok four times while the party
 * stands on one overworld tile, and a jump at a pipe changes Mario's position and puts him
 * back exactly where he started. A rule that gives up when nothing is happening has to
 * count arrival somewhere new, or the goal that was about to be taken away keeps being
 * rescued by a jump that achieved nothing.
 */
data class TurnEffect(
    val outcome: String,
    /** Whether the player ended the turn somewhere they had not been in recent memory. */
    val newGround: Boolean,
    /** What was tried — the goal's id when a goal chose it, so a goal can spot itself looping. */
    val action: String = "",
) {
    val progressed: Boolean get() = outcome == "Ok" && newGround

    /** How the state reads it out: `step_north: Ok (nowhere new)`. */
    override fun toString(): String {
        val effect = if (outcome == "Ok") "Ok (${if (newGround) "somewhere new" else "nowhere new"})" else outcome
        return if (action.isBlank()) effect else "$action: $effect"
    }
}

/**
 * Everything a goal is allowed to look at.
 *
 * Deliberately the turn's facts and nothing else — no emulator handle, no toolset, no
 * network. A goal decides whether it applies and what it would do; it never acts, so it
 * stays cheap enough to evaluate all of them every turn and pure enough to test without
 * booting a ROM.
 */
data class WorldSnapshot(
    val turn: Int,
    val phase: Phase,
    val ram: Map<String, Int>,
    val planStep: PlanStep?,
    val milestone: String,
    /** The last few turns, oldest first, with whether each one actually moved anything. */
    val recentTurns: List<TurnEffect>,
    /** One line about what is on screen, when something has described it. May be blank. */
    val scene: String = "",
    /**
     * Where the player is, per the active profile's own position fields.
     *
     * Null when the profile declares none; goals then work from [ram] directly rather than
     * from a coordinate the profile never promised.
     */
    val position: Pair<Int, Int>? = null,
    /** The current frame, for a decision model that can look at it. */
    val screenB64: String? = null,
) {
    val sm: Pair<Int, Int> get() = position ?: ((ram["smPlayerX"] ?: 0) to (ram["smPlayerY"] ?: 0))

    /** Only Final Fantasy has one; null elsewhere, and the state simply does not mention it. */
    val world: Pair<Int, Int>? get() {
        val x = ram["worldX"] ?: return null
        return x to (ram["worldY"] ?: 0)
    }

    /** How many of the last few turns did not work out. Goals use it to offer a way out. */
    val recentFailures: Int get() = recentTurns.count { !it.progressed }

    /**
     * How many turns in a row have changed nothing.
     *
     * Minecraft asks a running goal `canContinueToUse()` every tick and stops it when the
     * answer is no; this is what the FF1 goals answer that question with. It counts
     * effect rather than outcome for the reason [TurnEffect] gives. The count resets when
     * the Advisor writes a new plan, because the turn loop clears the history then — a
     * fresh plan deserves a fresh run of attempts.
     */
    val turnsWithoutProgress: Int get() = recentTurns.reversed().takeWhile { !it.progressed }.size

    /**
     * How many times [action] has been tried inside the current run of turns that changed
     * nothing.
     *
     * The per-goal version of [turnsWithoutProgress], and the one that stops a single goal
     * from eating a run. The second smoke run of the selector tapped Up into the same
     * overworld wall for twenty-one turns: every option on the menu was still applicable,
     * so `step_north` stayed the model's favourite and nothing ever took it away.
     *
     * Counted across the whole streak rather than as an unbroken run of its own, so that
     * alternating between two goals that both achieve nothing silences both of them
     * instead of resetting each other. Any turn that moved something clears it.
     */
    fun stalledOn(action: String): Int =
        recentTurns.reversed().takeWhile { !it.progressed }.count { it.action == action }

    companion object {
        /**
         * Reads the comma-joined `key=value` line the turn loop already builds.
         *
         * Values that are not integers are dropped rather than guessed at: the watched-RAM
         * digest is all integers today, and a field that stops being one should go missing
         * loudly in a goal's `canUse` rather than arrive as a silent zero.
         */
        fun parseRam(digest: String): Map<String, Int> = digest.split(",")
            .mapNotNull { field ->
                val key = field.substringBefore('=').trim()
                val value = field.substringAfter('=', "").trim().toIntOrNull()
                if (key.isEmpty() || value == null) null else key to value
            }
            .toMap()
    }
}

/** A tool call, in the shape [knes.agent.agents.ExecutorAgent] already dispatches. */
data class GoalAction(val tool: String, val args: Map<String, String> = emptyMap())

/**
 * One thing the agent could be doing right now — Minecraft's `Goal`, with its own name.
 *
 * Minecraft gives every mob a set of goals and a `GoalSelector`. Each tick the selector
 * asks every goal `canUse()`, and runs the applicable ones in priority order. A zombie
 * does not invent behaviour; it picks from behaviour that was written down, and the ones
 * that do not apply right now are not on the menu at all.
 *
 * That is the same shape a System-One decision wants: a small set of declared options,
 * re-derived from the world every tick. So kNES borrows the structure and swaps the
 * arbiter — instead of the lowest priority number always winning, the goals that
 * [canUse] become the declared options of a [knes.agent.decision.Choice] and a model
 * ranks them. [priority] stays as the tie-break and as the whole rule when no model is
 * configured.
 *
 * What this buys, concretely: the Executor's failures this year were all failures of
 * *generation* — a plan naming `armCharsViaMenu`, which no dispatcher knows; a decision
 * thrown away because `{"x":11}` would not decode into a string map; a turn lost to a
 * reply with no JSON object in it. None of them can be expressed here. A goal that is
 * not on the menu cannot be picked, and every goal on the menu already knows its own
 * arguments.
 */
interface Goal {
    /** Stable, and what the log and the decision row call it. */
    val id: String

    /** Lower goes first. Minecraft's priority int, and the tie-break when no model ranks. */
    val priority: Int

    /** Minecraft's `canUse()`: could this goal run at all, given the world as it is? */
    fun canUse(world: WorldSnapshot): Boolean

    /** The one sentence the model reads. Say what it does, not why it is a good idea. */
    fun describe(world: WorldSnapshot): String

    /** What to dispatch if this goal wins. Only called when [canUse] just said yes. */
    fun act(world: WorldSnapshot): GoalAction
}

/** A goal with a fixed action and a fixed sentence — most of them are this. */
class SimpleGoal(
    override val id: String,
    override val priority: Int,
    private val description: String,
    private val action: GoalAction,
    private val applies: (WorldSnapshot) -> Boolean,
) : Goal {
    override fun canUse(world: WorldSnapshot) = applies(world)
    override fun describe(world: WorldSnapshot) = description
    override fun act(world: WorldSnapshot) = action
}
