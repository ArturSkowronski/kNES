package knes.agent.goals

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.agents.ExecutorAgent
import knes.agent.decision.Choice
import knes.agent.decision.DeclaredOrder
import knes.agent.runtime.Phase
import knes.agent.runtime.PlanStep

private fun world(phase: Phase, planStep: PlanStep? = null, outcomes: List<TurnEffect> = emptyList()) = WorldSnapshot(
    turn = 1, phase = phase, ram = emptyMap(), planStep = planStep,
    milestone = "boot", recentTurns = outcomes,
)

private fun stuck(n: Int) = List(n) { TurnEffect("Fail", moved = false) }

private fun applicable(phase: Phase, planStep: PlanStep? = null, outcomes: List<TurnEffect> = emptyList()) =
    Ff1Goals.all().filter { it.canUse(world(phase, planStep, outcomes)) }.map { it.id }

private val WALK = PlanStep(0, "walk to the counter", "walkTo", mapOf("x" to "1", "y" to "2"))

class Ff1GoalsTest : FunSpec({

    test("every goal dispatches a tool the Executor actually knows") {
        val w = world(Phase.Town, PlanStep(0, "walk", "walkTo", mapOf("x" to "1", "y" to "2")))
        Ff1Goals.all().filter { it.canUse(w) }.forEach { goal ->
            ExecutorAgent.DISPATCHABLE shouldContain goal.act(w).tool
        }
    }

    test("goal ids are unique, so a selector can be built from the whole set") {
        val ids = Ff1Goals.all().map { it.id }
        ids.size shouldBe ids.toSet().size
    }

    test("at most sixteen apply at once, so nothing is silently dropped from the menu") {
        Phase.entries.forEach { phase ->
            val n = applicable(phase, PlanStep(0, "walk", "walkTo", mapOf("x" to "1", "y" to "2"))).size
            (n <= Choice.MAX_OPTIONS) shouldBe true
        }
    }

    test("on the title screen there is one thing to do") {
        applicable(Phase.Boot) shouldBe listOf("boot")
    }

    test("in a battle the party fights and does not wander off") {
        applicable(Phase.Battle) shouldBe listOf("fight_battle")
    }

    test("walking is offered on a map and nowhere else") {
        applicable(Phase.Town) shouldContain "step_north"
        applicable(Phase.Overworld) shouldContain "step_east"
        applicable(Phase.Battle) shouldNotContain "step_north"
        applicable(Phase.MenuStuck) shouldNotContain "step_north"
    }

    test("a menu can always be backed out of — the way out of MenuStuck") {
        applicable(Phase.MenuStuck) shouldContain "back_out_of_menu"
    }

    test("pressing A is not offered on the overworld, where there is nobody to talk to") {
        applicable(Phase.Overworld) shouldNotContain "press_a"
        applicable(Phase.Town) shouldContain "press_a"
    }

    test("the A option carries the shop warning, because one blind A once sold the party's weapons") {
        val goal = Ff1Goals.all().single { it.id == "press_a" }
        goal.describe(world(Phase.Town)).uppercase() shouldContain "SALE"
    }

    test("the plan is an option only when it names a tool that can be dispatched") {
        applicable(Phase.Town, PlanStep(0, "arm the party", "armCharsViaMenu")) shouldNotContain "follow_plan_step"
        applicable(Phase.Town, PlanStep(0, "walk", "walkTo", mapOf("x" to "1"))) shouldContain "follow_plan_step"
        applicable(Phase.Town, null) shouldNotContain "follow_plan_step"
    }

    test("a plan step that keeps failing comes off the menu, so a run is not spent on it") {
        applicable(Phase.Overworld, WALK, stuck(2)) shouldContain "follow_plan_step"
        applicable(Phase.Overworld, WALK, stuck(3)) shouldNotContain "follow_plan_step"
    }

    test("a turn that moved the party puts the plan back on the menu") {
        applicable(Phase.Overworld, WALK, stuck(3) + TurnEffect("Ok", moved = true)) shouldContain "follow_plan_step"
    }

    test("an Ok that moved nothing does not count as progress — it is how the first smoke run cycled") {
        applicable(Phase.Overworld, WALK, stuck(2) + TurnEffect("Ok", moved = false)) shouldNotContain "follow_plan_step"
    }

    test("something is always left to do once the plan has been taken off the menu") {
        Phase.entries.filter { it != Phase.Unknown && it != Phase.CartographerExplore }.forEach { phase ->
            applicable(phase, WALK, stuck(3)).isNotEmpty() shouldBe true
        }
    }

    test("the plan option acts on the step's own tool and arguments") {
        val goal = Ff1Goals.all().single { it.id == "follow_plan_step" }
        val w = world(Phase.Town, PlanStep(2, "walk to the counter", "walkTo", mapOf("x" to "18", "y" to "12")))
        goal.act(w) shouldBe GoalAction("walkTo", mapOf("x" to "18", "y" to "12"))
        goal.describe(w) shouldContain "walk to the counter"
    }
})

private fun tapped(goal: String, n: Int) = List(n) { TurnEffect("Ok", moved = false, action = goal) }

class GoalStallTest : FunSpec({

    test("a goal that keeps changing nothing takes itself off the menu") {
        applicable(Phase.Overworld, outcomes = tapped("step_north", 2)) shouldContain "step_north"
        applicable(Phase.Overworld, outcomes = tapped("step_north", 3)) shouldNotContain "step_north"
    }

    test("stalling on one direction leaves the others on the menu") {
        val left = applicable(Phase.Overworld, outcomes = tapped("step_north", 3))
        left shouldContain "step_east"
        left shouldContain "step_south"
    }

    test("a turn that moved the party puts the direction back") {
        val history = tapped("step_north", 3) + TurnEffect("Ok", moved = true, action = "step_north")
        applicable(Phase.Overworld, outcomes = history) shouldContain "step_north"
    }

    test("alternating between two useless goals silences both, instead of resetting each other") {
        val history = listOf("step_north", "step_east", "step_north", "step_east", "step_north", "step_east")
            .map { TurnEffect("Ok", moved = false, action = it) }
        val left = applicable(Phase.Overworld, outcomes = history)
        left shouldNotContain "step_north"
        left shouldNotContain "step_east"
        left shouldContain "step_south"
    }

    test("when every goal that applies has fallen silent the selector declines, and the chat model takes the turn") {
        // Two goals, so the whole menu fits inside the turns the Executor remembers.
        val two = Ff1Goals.all().filter { it.id == "step_north" || it.id == "step_east" }
        val history = two.flatMap { tapped(it.id, Ff1Goals.RETRY_LIMIT) }
        GoalSelector(two, DeclaredOrder).select(world(Phase.Overworld, null, history)).shouldBeNull()
    }
})

class PlanPhaseGuardTest : FunSpec({

    test("the plan cannot boot a game that is already booted, which once cost a whole run") {
        val boot = PlanStep(0, "press start to leave the title", "boot")
        applicable(Phase.Boot, boot) shouldContain "follow_plan_step"
        applicable(Phase.Overworld, boot) shouldNotContain "follow_plan_step"
        applicable(Phase.Town, boot) shouldNotContain "follow_plan_step"
    }

    test("the plan cannot fight a battle that is not happening") {
        val fight = PlanStep(0, "fight", "battleFightAll")
        applicable(Phase.Battle, fight) shouldContain "follow_plan_step"
        applicable(Phase.Town, fight) shouldNotContain "follow_plan_step"
    }

    test("tools with no phase of their own are left to the plan") {
        applicable(Phase.Overworld, WALK) shouldContain "follow_plan_step"
    }

    test("B is offered once nothing has moved for a while, even where the phase says no menu") {
        applicable(Phase.Overworld) shouldNotContain "back_out_of_menu"
        applicable(Phase.Overworld, outcomes = tapped("step_north", 3)) shouldContain "back_out_of_menu"
    }
})
