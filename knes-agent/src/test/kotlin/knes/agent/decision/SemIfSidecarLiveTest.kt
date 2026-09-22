package knes.agent.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import knes.agent.goals.Goals
import knes.agent.goals.GoalSelector
import knes.agent.goals.WorldSnapshot
import knes.agent.runtime.Phase
import knes.agent.runtime.PlanStep

/**
 * The real thing: a SemIf process, a real checkpoint, a real readout.
 *
 * Skipped unless `SEMIF_PYTHON` names an interpreter with `semif_phase1` in it, because
 * that needs a multi-gigabyte checkpoint and, for the MLX backend, Apple Silicon — the
 * same reason the ROM-dependent tests skip without `roms/`. Run it with:
 *
 *     SEMIF_PYTHON=~/GitHub/SemIf/.venv/bin/python SEMIF_SRC=~/GitHub/SemIf/src \
 *     SEMIF_BITS=4 ./gradlew :knes-agent:test --tests '*SemIfSidecarLive*'
 */
class SemIfSidecarLiveTest : FunSpec({

    val configured = !System.getenv("SEMIF_PYTHON").isNullOrBlank()

    test("a real SemIf process ranks the options it was given").config(enabled = configured) {
        SemIfProcess(DecisionModels.semIfCommand()).start().use { model ->
            model.name shouldContain "semif/"
            val choice = Choice(
                id = "live-1",
                state = "phase: Town\nparty tile in this map: 18,14\ngold: 400\n" +
                    "on screen: the weapon shop counter is two tiles north of the party",
                question = GoalSelector.DEFAULT_QUESTION,
                options = listOf(
                    Option("step_north", "Walk one tile north, toward the weapon shop counter."),
                    Option("leave_town", "Walk south out of the town gate onto the overworld."),
                    Option("fight_battle", "Attack with every character until the battle is over."),
                ),
            )
            val ranking = model.choose(choice)
            ranking.scores.map { it.first }.toSet() shouldBe choice.options.map { it.id }.toSet()
            ranking.scores.sumOf { it.second } shouldBeGreaterThan 0.99
            // Not asserting which option wins — that is the model's judgement, and pinning
            // it would turn a checkpoint change into a red build. What is asserted is the
            // part the agent depends on: the answer is one of the declared options.
            choice.options.map { it.id } shouldContain ranking.best
            // The one thing the party is definitely not doing is fighting: there is no battle.
            ranking.best shouldNotBe "fight_battle"
        }
    }

    test("the goal selector drives a real model end to end").config(enabled = configured) {
        SemIfProcess(DecisionModels.semIfCommand()).start().use { model ->
            val selector = GoalSelector(Goals.of("ff1"), model)
            val selection = selector.select(
                WorldSnapshot(
                    turn = 12,
                    phase = Phase.Town,
                    ram = mapOf("smPlayerX" to 18, "smPlayerY" to 14, "gold" to 400),
                    planStep = PlanStep(3, "walk to the weapon shop counter", "walkTo", mapOf("x" to "18", "y" to "12")),
                    milestone = "buy_weapons",
                    recentTurns = listOf(
                        knes.agent.goals.TurnEffect("Ok", moved = true),
                        knes.agent.goals.TurnEffect("Ok", moved = true),
                    ),
                ),
            )
            selection shouldNotBe null
            // Whatever it picked, it picked something the Executor can dispatch — which is
            // the property the whole design exists for.
            knes.agent.agents.ExecutorAgent.DISPATCHABLE shouldContain selection!!.action.tool
        }
    }
})
