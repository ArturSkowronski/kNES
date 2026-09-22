package knes.agent.decision

/**
 * One answer the model is allowed to give.
 *
 * The id is what the caller gets back and switches on; the description is the only
 * thing the model reads. Neither may be blank — an option the model cannot tell apart
 * from its neighbours is worse than no option at all.
 */
data class Option(val id: String, val description: String) {
    init {
        require(id.isNotBlank()) { "an option needs a non-blank id" }
        require(description.isNotBlank()) { "option '$id' needs a description the model can read" }
    }
}

/**
 * A decision whose answers are written down before the model is asked.
 *
 * This is the whole point of the System-One shape: the caller declares the options, the
 * model ranks them, and an answer outside the list is impossible — not unlikely, not
 * repaired on the way in, impossible. Everything the Executor used to get wrong by
 * generating JSON (a tool that does not exist, a coordinate typed as a number where a
 * string was expected, a response with no JSON object in it at all) cannot be expressed
 * here.
 *
 * The field names mirror SemIf's row format exactly — `{id, state, question, options}`
 * with `{id, description}` options — so a [Choice] serialises straight onto the wire
 * with no translation layer, and the same row runs through SemIf's own CLI.
 *
 * The 2..16 bound is SemIf's, not ours: it maps each option onto a single answer token,
 * and there are sixteen letters in its answer alphabet. One option is not a decision.
 */
data class Choice(
    val id: String,
    val state: String,
    val question: String,
    val options: List<Option>,
    /**
     * The screen, when the model can look at it.
     *
     * The pinned checkpoint is a vision-language model, and the same readout works with a
     * frame as the evidence instead of a paragraph about it — at 256x240 it costs about
     * 190 ms against 100 ms for text alone. It is the difference between an agent that
     * reads a RAM digest and one that can see a pipe in front of it. Ignored by backends
     * that only have the text half of the model loaded.
     */
    val imageB64: String? = null,
) {
    init {
        require(id.isNotBlank()) { "a choice needs a non-blank id" }
        require(state.isNotBlank()) { "choice '$id' needs the state the decision is about" }
        require(question.isNotBlank()) { "choice '$id' needs a question" }
        require(options.size in MIN_OPTIONS..MAX_OPTIONS) {
            "choice '$id' declares ${options.size} options; the readout takes $MIN_OPTIONS..$MAX_OPTIONS"
        }
        val ids = options.map { it.id }
        require(ids.size == ids.toSet().size) {
            "choice '$id' repeats an option id: ${ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
    }

    companion object {
        const val MIN_OPTIONS = 2

        /** SemIf's ceiling: one answer letter per option, sixteen letters. */
        const val MAX_OPTIONS = 16
    }
}

/**
 * How the model ranked the declared options.
 *
 * [scores] is sorted best-first and holds every option, so a caller can look past the
 * winner — which is what [margin] is for. The numbers are conditional option scores, not
 * calibrated confidence: SemIf says so in every result it returns, and a 0.79 here means
 * "this option carried most of the mass among these four", not "79% likely correct".
 */
data class Ranking(
    val choiceId: String,
    val model: String,
    val scores: List<Pair<String, Double>>,
) {
    init {
        require(scores.isNotEmpty()) { "ranking '$choiceId' came back with no scores" }
        require(scores.zipWithNext().all { (a, b) -> a.second >= b.second }) {
            "ranking '$choiceId' is not sorted best-first: $scores"
        }
    }

    val best: String get() = scores.first().first

    val confidence: Double get() = scores.first().second

    /** How far clear the winner ran. A near-zero margin means the model is guessing. */
    val margin: Double get() = confidence - (scores.getOrNull(1)?.second ?: 0.0)

    /**
     * One line for the turn log: `a 0.79 (next b 0.09) via semif/mlx/Qwen3.5-4B`.
     *
     * Root locale, not the default: this line is read back out of run logs and compared
     * across machines, and a Polish JVM would write the decimal comma.
     */
    fun summary(): String {
        val next = scores.getOrNull(1)
        val tail = if (next == null) "" else " (next ${next.first} ${fixed(next.second)})"
        return "$best ${fixed(confidence)}$tail via $model"
    }

    private fun fixed(value: Double) = String.format(java.util.Locale.ROOT, "%.2f", value)

    companion object {
        /** Sorts, so callers hand over whatever order the backend used. */
        fun of(choiceId: String, model: String, scores: List<Pair<String, Double>>) =
            Ranking(choiceId, model, scores.sortedByDescending { it.second })
    }
}

/**
 * Something that ranks declared options.
 *
 * Deliberately narrower than [knes.agent.llm.ChatLlm]: no prompt, no token budget, no
 * free text coming back. A local SemIf process and a hosted Jev endpoint both fit, and
 * so does [DeclaredOrder], which needs no model at all.
 */
interface DecisionModel : AutoCloseable {
    /** What to write in the log next to a decision. */
    val name: String

    suspend fun choose(choice: Choice): Ranking

    override fun close() = Unit
}

/**
 * The no-model model: the first declared option wins.
 *
 * Minecraft's own rule when nothing smarter is available — its `GoalSelector` runs the
 * lowest-priority-number goal that says it can run, and that is exactly what this gives
 * you once the selector has sorted its options by priority. It keeps the agent playable
 * with no Python, no GPU and no API key, and it makes the selector's behaviour testable
 * without stubbing a network.
 *
 * Scores decay geometrically so the declared order survives into [Ranking.scores]. They
 * are ordering, not belief, and [name] says so.
 */
object DeclaredOrder : DecisionModel {
    override val name = "declared-order"

    override suspend fun choose(choice: Choice): Ranking {
        val weights = choice.options.indices.map { 1.0 / (1 shl it) }
        val total = weights.sum()
        return Ranking.of(choice.id, name, choice.options.mapIndexed { i, o -> o.id to weights[i] / total })
    }
}
