package knes.agent.goals

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import knes.agent.agents.ExecutorAgent
import knes.agent.decision.Choice
import knes.agent.decision.DeclaredOrder
import knes.agent.runtime.Phase
import knes.agent.runtime.PlanStep
import knes.agent.tools.results.AgentConfig

private fun world(
    phase: Phase,
    ram: Map<String, Int> = emptyMap(),
    planStep: PlanStep? = null,
    outcomes: List<TurnEffect> = emptyList(),
) = WorldSnapshot(
    turn = 1, phase = phase, ram = ram, planStep = planStep,
    milestone = "(none)", recentTurns = outcomes,
)

private fun applicable(profile: String, world: WorldSnapshot) =
    Goals.of(profile).filter { it.canUse(world) }.map { it.id }

private fun stuck(goal: String, n: Int) = List(n) { TurnEffect("Ok", newGround = false, action = goal) }

private val WALK = PlanStep(0, "walk to the counter", "walkTo", mapOf("x" to "11", "y" to "11"))

/**
 * The goals come from `profiles/<id>.json`, so these run against what actually ships.
 *
 * They used to run against two Kotlin objects, which is what the tests were really
 * checking: that a game constant had been typed correctly into runtime code.
 */
class ProfileGoalsTest : FunSpec({

    test("every game that declares goals dispatches tools the Executor knows") {
        listOf("ff1", "smb").forEach { profile ->
            val goals = Goals.of(profile)
            goals.isEmpty() shouldBe false
            goals.forEach { goal ->
                val w = world(Phase.Town, mapOf("gameState" to 1), WALK)
                val tool = if (goal is PlanStepGoal) "walkTo" else goal.act(w).tool
                ExecutorAgent.DISPATCHABLE shouldContain tool
            }
        }
    }

    test("goal ids are unique and the set fits on one menu") {
        listOf("ff1", "smb").forEach { profile ->
            val ids = Goals.of(profile).map { it.id }
            ids.size shouldBe ids.toSet().size
            (ids.size <= Choice.MAX_OPTIONS) shouldBe true
        }
    }

    test("a game nobody has written goals for gets none, not somebody else's") {
        Goals.of("zelda") shouldBe emptyList()
        Goals.of(null) shouldBe emptyList()
        AgentConfig.of("zelda").shouldBeNull()
    }

    test("each game asks its own question, and does not borrow another's words") {
        val ff1 = AgentConfig.of("ff1")!!.question
        val smb = AgentConfig.of("smb")!!.question
        ff1 shouldContainText "party"
        smb shouldContainText "Mario"
        (ff1 == smb) shouldBe false
    }

    test("each game chooses how large a frame the model looks at") {
        AgentConfig.of("ff1")!!.frame shouldBe "256x240"
        // A gap in Mario's floor two tiles ahead is a handful of pixels at native size.
        AgentConfig.of("smb")!!.frame shouldBe "512x480"
    }

    test("the state lines name only the fields the game actually has") {
        val ff1 = AgentConfig.of("ff1")!!.stateLines(mapOf("goldLow" to 144, "goldMid" to 1, "menuCursor" to 2))
        ff1 shouldContain "gold: 400"
        ff1.any { it.contains("lives") } shouldBe false

        val smb = AgentConfig.of("smb")!!.stateLines(mapOf("lives" to 2, "coins" to 7))
        smb shouldContain "lives left: 2"
        smb.any { it.contains("gold") } shouldBe false
    }

    test("a byte worth spelling out is given as a sentence, not a digit") {
        val config = AgentConfig.of("smb")!!
        config.stateLines(mapOf("playerFloatState" to 0)) shouldContain "Mario is standing on solid ground"
        config.stateLines(mapOf("playerFloatState" to 1)) shouldContain "Mario is in the air"
    }
})

class Ff1ProfileGoalsTest : FunSpec({

    test("on the title screen there is one thing to do") {
        applicable("ff1", world(Phase.Boot)) shouldBe listOf("boot")
    }

    test("in a battle the party fights and does not wander off") {
        applicable("ff1", world(Phase.Battle)) shouldBe listOf("fight_battle")
    }

    test("walking is offered on a map and nowhere else") {
        applicable("ff1", world(Phase.Town)) shouldContain "step_north"
        applicable("ff1", world(Phase.Overworld)) shouldContain "step_east"
        applicable("ff1", world(Phase.Battle)) shouldNotContain "step_north"
    }

    test("pressing A is not offered on the overworld, where there is nobody to talk to") {
        applicable("ff1", world(Phase.Overworld)) shouldNotContain "press_a"
        applicable("ff1", world(Phase.Town)) shouldContain "press_a"
    }

    test("the A option carries the shop warning, because one blind A sold the party's weapons") {
        val goal = Goals.of("ff1").single { it.id == "press_a" }
        goal.describe(world(Phase.Town)).uppercase() shouldContainText "SALE"
    }

    test("B is offered once nothing has moved for a while, even where the phase says no menu") {
        applicable("ff1", world(Phase.Overworld)) shouldNotContain "back_out_of_menu"
        applicable("ff1", world(Phase.Overworld, outcomes = stuck("step_north", 3))) shouldContain "back_out_of_menu"
    }

    test("the plan is an option only when it names a tool that can be dispatched") {
        applicable("ff1", world(Phase.Town, planStep = PlanStep(0, "arm", "armCharsViaMenu"))) shouldNotContain "follow_plan_step"
        applicable("ff1", world(Phase.Town, planStep = WALK)) shouldContain "follow_plan_step"
        applicable("ff1", world(Phase.Town)) shouldNotContain "follow_plan_step"
    }

    test("the plan cannot boot a game that is already booted, which once cost a whole run") {
        val boot = PlanStep(0, "press start", "boot")
        applicable("ff1", world(Phase.Boot, planStep = boot)) shouldContain "follow_plan_step"
        applicable("ff1", world(Phase.Overworld, planStep = boot)) shouldNotContain "follow_plan_step"
    }

    test("the plan cannot fight a battle that is not happening") {
        val fight = PlanStep(0, "fight", "battleFightAll")
        applicable("ff1", world(Phase.Battle, planStep = fight)) shouldContain "follow_plan_step"
        applicable("ff1", world(Phase.Town, planStep = fight)) shouldNotContain "follow_plan_step"
    }

    test("the plan option acts on the step's own tool and arguments") {
        val goal = Goals.of("ff1").single { it.id == "follow_plan_step" }
        val w = world(Phase.Town, planStep = WALK)
        goal.act(w) shouldBe GoalAction("walkTo", mapOf("x" to "11", "y" to "11"))
        goal.describe(w) shouldContainText "walk to the counter"
    }

    test("a goal that keeps changing nothing takes itself off the menu") {
        applicable("ff1", world(Phase.Overworld, outcomes = stuck("step_north", 2))) shouldContain "step_north"
        applicable("ff1", world(Phase.Overworld, outcomes = stuck("step_north", 3))) shouldNotContain "step_north"
    }

    test("stalling on one direction leaves the others on the menu") {
        val left = applicable("ff1", world(Phase.Overworld, outcomes = stuck("step_north", 3)))
        left shouldContain "step_east"
        left shouldContain "step_south"
    }

    test("a turn that reached somewhere new puts the direction back") {
        val history = stuck("step_north", 3) + TurnEffect("Ok", newGround = true, action = "step_north")
        applicable("ff1", world(Phase.Overworld, outcomes = history)) shouldContain "step_north"
    }

    test("when every goal has fallen silent the selector forgets, rather than giving up the turn") {
        // The stall rule may not empty the menu: an agent with nothing to choose from hands
        // the turn to a chat model that a reactive run does not have.
        val two = Goals.of("ff1").filter { it.id == "step_north" || it.id == "step_east" }
        val history = two.flatMap { stuck(it.id, 3) }
        val selection = GoalSelector(two, DeclaredOrder).select(world(Phase.Overworld, outcomes = history))
        selection!!.goal.id shouldBe "step_north"
    }
})

class SmbProfileGoalsTest : FunSpec({

    val playing = mapOf("gameState" to 1, "playerFloatState" to 0, "lives" to 2)
    val airborne = mapOf("gameState" to 1, "playerFloatState" to 1, "lives" to 2)

    test("on the title screen the only thing to do is start the game") {
        applicable("smb", world(Phase.Boot, mapOf("gameState" to 0))) shouldBe listOf("start_game")
    }

    test("once the level is running, START is gone and the moves are there") {
        val moves = applicable("smb", world(Phase.Overworld, playing))
        moves shouldNotContain "start_game"
        moves shouldContain "run_right"
        moves shouldContain "jump_right"
        moves shouldContain "wait"
    }

    test("gameState 0 means the attract-mode demo, whatever the phase classifier said") {
        applicable("smb", world(Phase.Overworld, mapOf("gameState" to 0))).isEmpty() shouldBe true
    }

    test("there is no double jump, so a jump in mid-air is not on the menu") {
        val air = applicable("smb", world(Phase.Overworld, airborne))
        air shouldNotContain "jump_right"
        air shouldNotContain "jump_up"
        // Air control still works, so the directions stay.
        air shouldContain "walk_right"
    }

    test("every move holds buttons for a span, because the length of a press is the decision") {
        Goals.of("smb").forEach { goal ->
            val action = goal.act(world(Phase.Overworld, playing))
            action.tool shouldBe "hold"
            (action.args.getValue("frames").toInt() > 0) shouldBe true
        }
    }

    test("a jump is held longer than a step — that is what makes it a jump") {
        val w = world(Phase.Overworld, playing)
        val step = Goals.of("smb").single { it.id == "run_right" }.act(w)
        val jump = Goals.of("smb").single { it.id == "jump_right" }.act(w)
        (jump.args.getValue("frames").toInt() > step.args.getValue("frames").toInt()) shouldBe true
        jump.args.getValue("buttons") shouldContainText "A"
    }

    test("waiting presses nothing at all, which is a real option in a game that moves on its own") {
        Goals.of("smb").single { it.id == "wait" }.act(world(Phase.Overworld, playing))
            .args.getValue("buttons") shouldBe ""
    }

    test("Mario gives up on a goal later than the party does — his world moves on its own") {
        applicable("smb", world(Phase.Overworld, playing, outcomes = stuck("run_right", 3))) shouldContain "run_right"
        applicable("smb", world(Phase.Overworld, playing, outcomes = stuck("run_right", 6))) shouldNotContain "run_right"
    }
})

class MultiByteStateTest : FunSpec({

    test("a number spread over several bytes is put back together before the model sees it") {
        // Final Fantasy keeps gold in three addresses. `goldLow: 144` says nothing about
        // whether the party can afford a sword.
        val lines = AgentConfig.of("ff1")!!.stateLines(
            mapOf("goldLow" to 144, "goldMid" to 1, "goldHigh" to 0),
        )
        lines shouldContain "gold: 400"
    }

    test("the low byte alone is still a number, not a crash") {
        AgentConfig.of("ff1")!!.stateLines(mapOf("goldLow" to 40)) shouldContain "gold: 40"
    }

    test("no low byte, no line — the game may not have this number at all") {
        AgentConfig.of("ff1")!!.stateLines(emptyMap()).any { it.startsWith("gold") } shouldBe false
    }
})
