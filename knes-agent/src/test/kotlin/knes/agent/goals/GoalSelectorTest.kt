package knes.agent.goals

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import knes.agent.decision.Choice
import knes.agent.decision.DecisionModel
import knes.agent.decision.DeclaredOrder
import knes.agent.decision.Ranking
import knes.agent.runtime.Phase
import knes.agent.runtime.PlanStep

/** Picks whichever option it was told to, and remembers what it was shown. */
private class FakeModel(private val pick: String) : DecisionModel {
    override val name = "fake"
    var lastChoice: Choice? = null
    override suspend fun choose(choice: Choice): Ranking {
        lastChoice = choice
        return Ranking.of(choice.id, name, choice.options.map { it.id to if (it.id == pick) 1.0 else 0.0 })
    }
}

private fun goal(id: String, priority: Int, applies: Boolean = true) = SimpleGoal(
    id = id,
    priority = priority,
    description = "do $id",
    action = GoalAction("sequence", mapOf("buttons" to id)),
    applies = { applies },
)

private fun world(
    phase: Phase = Phase.Town,
    ram: Map<String, Int> = mapOf("smPlayerX" to 18, "smPlayerY" to 14),
    planStep: PlanStep? = null,
    outcomes: List<TurnEffect> = emptyList(),
) = WorldSnapshot(
    turn = 7, phase = phase, ram = ram, planStep = planStep,
    milestone = "buy_weapons", recentTurns = outcomes,
)

class GoalSelectorTest : FunSpec({

    test("only the goals that can run are put on the menu") {
        val model = FakeModel("b")
        val selector = GoalSelector(listOf(goal("a", 1, applies = false), goal("b", 2), goal("c", 3)), model)
        selector.select(world())?.goal?.id shouldBe "b"
        model.lastChoice!!.options.map { it.id } shouldBe listOf("b", "c")
    }

    test("when nothing applies the selector declines, and the caller keeps its old path") {
        val selector = GoalSelector(listOf(goal("a", 1, applies = false)), FakeModel("a"))
        selector.select(world()).shouldBeNull()
    }

    test("one applicable goal skips the model — there is nothing to decide") {
        val model = FakeModel("never asked")
        val selection = GoalSelector(listOf(goal("a", 1), goal("b", 2, applies = false)), model).select(world())
        selection!!.goal.id shouldBe "a"
        model.lastChoice.shouldBeNull()
        selection.ranking.model shouldBe "only-applicable"
    }

    test("more goals than the readout has answer letters: the lowest priorities are shown") {
        val many = (1..20).map { goal("g$it", it) }
        val model = FakeModel("g1")
        GoalSelector(many, model).select(world())
        model.lastChoice!!.options.size shouldBe Choice.MAX_OPTIONS
        model.lastChoice!!.options.map { it.id } shouldBe (1..16).map { "g$it" }
    }

    test("the winner's own action is what comes back, so the model never writes arguments") {
        val selection = GoalSelector(listOf(goal("a", 1), goal("b", 2)), FakeModel("b")).select(world())
        selection!!.action shouldBe GoalAction("sequence", mapOf("buttons" to "b"))
    }

    test("with no model at all, the lowest priority number wins — Minecraft's own rule") {
        val selector = GoalSelector(listOf(goal("late", 30), goal("early", 5), goal("mid", 10)), DeclaredOrder)
        selector.select(world())!!.goal.id shouldBe "early"
    }

    test("duplicate goal ids are rejected — the winner would be ambiguous") {
        shouldThrow<IllegalArgumentException> { GoalSelector(listOf(goal("a", 1), goal("a", 2)), DeclaredOrder) }
    }

    test("a selector with no goals can never act, so it is rejected at construction") {
        shouldThrow<IllegalArgumentException> { GoalSelector(emptyList(), DeclaredOrder) }
    }

    test("the state the model reads names the phase, the tile and what the plan wanted") {
        val selector = GoalSelector(listOf(goal("a", 1), goal("b", 2)), FakeModel("a"))
        val described = selector.describe(
            world(
                phase = Phase.Indoors,
                ram = mapOf("smPlayerX" to 18, "smPlayerY" to 14),
                planStep = PlanStep(3, "walk to the weapon counter", "walkTo", mapOf("x" to "18", "y" to "12")),
                outcomes = listOf(TurnEffect("Ok", newGround = true), TurnEffect("Fail", newGround = false)),
            ),
        )
        described shouldContain "phase: Indoors"
        described shouldContain "18,14"
        described shouldContain "walk to the weapon counter"
        described shouldContain "Ok (somewhere new), Fail"
    }

    test("what the game itself counts comes from the profile, not from this class") {
        // Gold is Final Fantasy's; lives are Mario's. Naming either here is how the state
        // ends up half full of nulls whichever game is running.
        val selector = GoalSelector(
            listOf(goal("a", 1), goal("b", 2)), FakeModel("a"),
            gameState = { ram -> ram["gold"]?.let { listOf("gold: $it") }.orEmpty() },
        )
        selector.describe(world(ram = mapOf("gold" to 400))) shouldContain "gold: 400"
        selector.describe(world(ram = emptyMap())).contains("gold") shouldBe false
    }

    test("the state leaves out RAM fields the digest did not carry, rather than inventing zeroes") {
        val described = GoalSelector(listOf(goal("a", 1)), DeclaredOrder).describe(world(ram = emptyMap()))
        described shouldContain "phase: Town"
        described.contains("gold:") shouldBe false
    }

    test("a selection carries what was considered, so a run can be read back") {
        val selection = GoalSelector(listOf(goal("a", 1), goal("b", 2), goal("c", 3)), FakeModel("c")).select(world())
        selection!!.considered.map { it.id } shouldBe listOf("a", "b", "c")
        selection.ranking shouldNotBe null
    }
})

/**
 * Giving up on everything is not an option the selector may take.
 *
 * After a game over, Super Mario Bros sits on its title screen and the only goal that
 * applies is the one that presses START. It took several turns to land, stalled itself out
 * under its own retry limit, and left an empty menu — twenty turns went to a chat-model
 * fallback that a reactive run does not have.
 */
class EmptyMenuTest : FunSpec({

    fun stalling(id: String, limit: Int) = object : Goal {
        override val id = id
        override val priority = 0
        override fun canUse(world: WorldSnapshot) = world.stalledOn(id) < limit
        override fun describe(world: WorldSnapshot) = "press on"
        override fun act(world: WorldSnapshot) = GoalAction("sequence", mapOf("buttons" to "START"))
    }

    val worn = List(4) { TurnEffect("Ok", newGround = false, action = "only") }

    test("the last goal standing stays on the menu however long it has been failing") {
        val selector = GoalSelector(listOf(stalling("only", 3)), DeclaredOrder)
        selector.select(world(outcomes = worn))!!.goal.id shouldBe "only"
    }

    test("a goal still stalls while something else is left to try") {
        val selector = GoalSelector(listOf(stalling("only", 3), goal("other", 9)), DeclaredOrder)
        selector.select(world(outcomes = worn))!!.goal.id shouldBe "other"
    }

    test("nothing applicable at all is still a decline — that is not the stall rule's doing") {
        val selector = GoalSelector(listOf(goal("a", 1, applies = false)), DeclaredOrder)
        selector.select(world()).shouldBeNull()
    }
})
