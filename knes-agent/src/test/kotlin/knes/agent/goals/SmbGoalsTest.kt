package knes.agent.goals

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import knes.agent.agents.ExecutorAgent
import knes.agent.decision.Choice
import knes.agent.runtime.Phase

private fun mario(
    phase: Phase = Phase.Overworld,
    gameState: Int = 1,
    outcomes: List<TurnEffect> = emptyList(),
) = WorldSnapshot(
    turn = 1,
    phase = phase,
    ram = mapOf("gameState" to gameState, "playerX" to 40, "playerY" to 176, "lives" to 2, "coins" to 0),
    planStep = null,
    milestone = "(none)",
    recentTurns = outcomes,
    position = 40 to 176,
)

private fun applicable(world: WorldSnapshot) = SmbGoals.all().filter { it.canUse(world) }.map { it.id }

class SmbGoalsTest : FunSpec({

    test("every goal dispatches a tool the Executor knows") {
        val world = mario()
        SmbGoals.all().forEach { ExecutorAgent.DISPATCHABLE shouldContain it.act(world).tool }
    }

    test("goal ids are unique and the whole set fits on one menu") {
        val ids = SmbGoals.all().map { it.id }
        ids.size shouldBe ids.toSet().size
        (ids.size <= Choice.MAX_OPTIONS) shouldBe true
    }

    test("on the title screen the only thing to do is start the game") {
        applicable(mario(phase = Phase.Boot, gameState = 0)) shouldBe listOf("start_game")
    }

    test("once the level is running, START is gone and the moves are there") {
        val playing = applicable(mario())
        playing shouldNotContain "start_game"
        playing shouldContain "run_right"
        playing shouldContain "jump_right"
        playing shouldContain "wait"
    }

    test("gameState 0 means the attract-mode demo, whatever the phase classifier said") {
        // The profile only separates Boot from everything else, so a goal that presses a
        // direction checks the byte rather than trusting the phase.
        applicable(mario(phase = Phase.Overworld, gameState = 0)).isEmpty() shouldBe true
    }

    test("every move holds buttons for a span, because the length of a press is the decision") {
        SmbGoals.all().forEach { goal ->
            val action = goal.act(mario())
            action.tool shouldBe "hold"
            (action.args.getValue("frames").toInt() > 0) shouldBe true
        }
    }

    test("a jump is held longer than a step — that is what makes it a jump") {
        val step = SmbGoals.all().single { it.id == "run_right" }.act(mario())
        val jump = SmbGoals.all().single { it.id == "jump_right" }.act(mario())
        (jump.args.getValue("frames").toInt() > step.args.getValue("frames").toInt()) shouldBe true
        jump.args.getValue("buttons") shouldContainText "A"
    }

    test("waiting presses nothing at all, which is a real option in a game that moves on its own") {
        SmbGoals.all().single { it.id == "wait" }.act(mario()).args.getValue("buttons") shouldBe ""
    }

    test("a goal that achieves nothing for long enough comes off the menu, as in Final Fantasy") {
        val stuck = List(SmbGoals.RETRY_LIMIT) { TurnEffect("Ok", moved = false, action = "run_right") }
        applicable(mario(outcomes = stuck)) shouldNotContain "run_right"
        applicable(mario(outcomes = stuck)) shouldContain "jump_right"
    }

    test("Mario gives up later than the party does — his world moves on its own") {
        (SmbGoals.RETRY_LIMIT > Ff1Goals.RETRY_LIMIT) shouldBe true
    }
})

class GoalsByProfileTest : FunSpec({

    test("each profile brings its own goals") {
        Goals.of("ff1").map { it.id } shouldContain "press_a"
        Goals.of("smb").map { it.id } shouldContain "jump_right"
        Goals.of("SMB").map { it.id } shouldContain "jump_right"
    }

    test("a game nobody has written goals for gets none, and the chat model keeps deciding") {
        Goals.of("zelda") shouldBe emptyList()
        Goals.of(null) shouldBe emptyList()
    }

    test("the supported list and the actual sets agree") {
        Goals.SUPPORTED.forEach { (Goals.of(it).isNotEmpty()) shouldBe true }
    }
})
