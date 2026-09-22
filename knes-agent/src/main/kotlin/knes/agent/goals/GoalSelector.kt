package knes.agent.goals

import knes.agent.decision.Choice
import knes.agent.decision.DecisionModel
import knes.agent.decision.Option
import knes.agent.decision.Ranking

/** What the selector settled on, with the reasoning kept for the turn log. */
data class Selection(
    val goal: Goal,
    val action: GoalAction,
    val ranking: Ranking,
    /** Everything that said it could run, in the order the model was shown them. */
    val considered: List<Goal>,
)

/**
 * Minecraft's `GoalSelector`, with a model where the priority comparison used to be.
 *
 * Every turn: ask each goal whether it applies, hand what is left to a
 * [DecisionModel] as declared options, and run whichever it ranks first. A goal that
 * did not say [Goal.canUse] is not an option, so the selector cannot pick a tool that
 * makes no sense for the phase the game is actually in.
 *
 * Two cases skip the model, both because there is nothing to decide: nothing applies —
 * the caller falls back to whatever it did before — and exactly one thing applies, which
 * also keeps us inside SemIf's two-option floor.
 */
class GoalSelector(
    goals: List<Goal>,
    private val model: DecisionModel,
    /** What this game asks. Per game, because "the party" was being asked in front of Mario. */
    private val question: String = DEFAULT_QUESTION,
    /**
     * The game-specific lines of state, from the profile.
     *
     * The turn, the phase and the position are true of any game; gold, lives and whether
     * the player is airborne are not, and naming them all here would leave half the state
     * reading "null" whichever game happened to be running.
     */
    private val gameState: (Map<String, Int>) -> List<String> = { emptyList() },
) {
    /**
     * Sorted once, at construction. Priority decides what the model *sees* when more
     * than sixteen goals apply, and decides outright under
     * [knes.agent.decision.DeclaredOrder].
     */
    private val goals: List<Goal> = goals.sortedBy { it.priority }

    init {
        require(goals.isNotEmpty()) { "a selector with no goals can never act" }
        val ids = goals.map { it.id }
        require(ids.size == ids.toSet().size) {
            "goal ids must be unique: ${ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
    }

    suspend fun select(world: WorldSnapshot): Selection? {
        val applicable = goals.filter { it.canUse(world) }.ifEmpty { unstalled(world) }
        if (applicable.isEmpty()) return null
        // The readout maps one answer token per option and has sixteen of them. When more
        // goals apply than that, the lowest priority numbers are the ones worth ranking.
        val shown = applicable.take(Choice.MAX_OPTIONS)
        if (shown.size == 1) {
            val only = shown.single()
            return Selection(only, only.act(world), Ranking.of(only.id, "only-applicable", listOf(only.id to 1.0)), shown)
        }
        val choice = Choice(
            id = "turn-${world.turn}",
            state = describe(world),
            question = question,
            options = shown.map { Option(it.id, it.describe(world)) },
            imageB64 = world.screenB64,
        )
        val ranking = model.choose(choice)
        // The model can only answer with an id it was given, and parseRanking already
        // checks the reply is about this decision — so the lookup cannot miss.
        val winner = shown.first { it.id == ranking.best }
        return Selection(winner, winner.act(world), ranking, shown)
    }

    /**
     * Everything that would apply if the recent past were forgotten.
     *
     * The stall rule must never empty the menu. After a game over, Super Mario Bros sits on
     * its title screen and the only goal that applies is the one that presses START — which
     * took several turns to land, stalled itself out, and left nothing at all to choose
     * from. Twenty turns then went to the chat-model fallback, which in a reactive run is
     * not configured, so each one was a rejected no-op.
     *
     * Forgetting is the right answer rather than exempting a goal by name: if nothing is
     * left, whatever the history said is no longer useful.
     */
    private fun unstalled(world: WorldSnapshot): List<Goal> =
        goals.filter { it.canUse(world.copy(recentTurns = emptyList())) }

    /**
     * The state the model reads.
     *
     * Plain lines rather than the raw 120-field RAM digest: the readout has a token
     * budget like anything else, and a cursor position two characters wide is as easy for
     * it to lose in that line as it is for a chat model.
     */
    internal fun describe(world: WorldSnapshot): String = buildString {
        appendLine("turn: ${world.turn}")
        appendLine("phase: ${world.phase}")
        appendLine("milestone in progress: ${world.milestone}")
        appendLine("player position: ${world.sm.first},${world.sm.second}")
        world.world?.let { appendLine("position on the overworld: ${it.first},${it.second}") }
        gameState(world.ram).forEach { appendLine(it) }
        if (world.map.isNotEmpty()) {
            appendLine("what is around the player, one character a tile — # solid, . open air, M the player, E an enemy:")
            world.map.forEach { appendLine(it) }
        }
        val step = world.planStep
        appendLine(
            if (step == null) "the plan has no step for this turn"
            else "the plan suggests: ${step.intentTool}(${step.intentArgs ?: emptyMap()}) — ${step.description}",
        )
        // The stall rule needs a long memory; the model does not. Twenty-four entries of
        // "walk_right: Ok (somewhere new)" is the longest line in the prompt and the least
        // informative — the reference Jev harness sends the last action and its outcome.
        val shown = world.recentTurns.takeLast(SHOWN_TURNS)
        if (shown.isNotEmpty()) {
            appendLine("the last ${shown.size} turns went: ${shown.joinToString(", ")}")
        }
        if (world.scene.isNotBlank()) appendLine("on screen: ${world.scene}")
    }.trim()

    companion object {
        /**
         * Deliberately not "the party".
         *
         * That was Final Fantasy's word, and it was being asked in front of a picture of
         * Mario — a decision model reading the screen should not be told it is looking at
         * something else.
         */
        const val DEFAULT_QUESTION = "Which of these should the player do on this turn?"

        /** How much of the history the model is shown. The stall rule keeps far more. */
        const val SHOWN_TURNS = 6
    }
}
