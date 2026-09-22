package knes.agent.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import knes.agent.decision.Choice
import knes.agent.decision.DecisionModel
import knes.agent.decision.Ranking
import knes.agent.goals.Ff1Goals
import knes.agent.goals.GoalSelector
import knes.agent.llm.ChatLlm
import knes.agent.llm.HaikuClient
import knes.agent.llm.SonnetClient
import knes.agent.runtime.Memory
import knes.agent.runtime.Phase
import knes.agent.runtime.Plan
import knes.agent.runtime.PlanStep
import knes.agent.runtime.RunDirectory
import knes.agent.tools.ToolOutcome
import knes.agent.tools.ToolSurface
import kotlin.io.path.createTempDirectory

private class UnusedChat : ChatLlm {
    override val providerName = "unused"
    override val fastModel = "unused"
    override val strongModel = "unused"
    override suspend fun generate(
        model: String, systemPrompt: String, userText: String, imageB64: String?, maxTokens: Int,
    ): String = error("the goal selector decides every turn in this test")
    override fun close() = Unit
}

/** Answers Ok with the message the real town walk produces — which carries no coordinates. */
private class SilentToolSurface : ToolSurface {
    override suspend fun boot() = ToolOutcome.Ok("Reached overworld after 2 taps")
    override suspend fun walkTo(x: Int, y: Int) =
        ToolOutcome.Ok("townWalk: reached EXACT ($x,$y) in 19 steps (recoveries=0)")
    override suspend fun interactAt(x: Int, y: Int) = ToolOutcome.Ok("interactAt done")
    override suspend fun useMenu(path: String) = ToolOutcome.Ok("useMenu done")
    override suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>) = ToolOutcome.Ok()
    override suspend fun equipWeapon(charSlot: Int, weaponSlot: Int) = ToolOutcome.Ok()
    override suspend fun restAtInn(innMapId: String) = ToolOutcome.Ok()
    override suspend fun battleFightAll() = ToolOutcome.Ok()
    override suspend fun approachSprite(kind: String) = ToolOutcome.Ok()
    override suspend fun sequence(buttons: List<String>) = ToolOutcome.Ok("sequence: tapped ${buttons.size} buttons")
    override suspend fun hold(buttons: List<String>, frames: Int) =
        ToolOutcome.Ok("hold: ${buttons.joinToString("+")} for $frames frames")
}

/** Always picks the plan, and keeps the state it was shown so the test can read it back. */
private class PlanPicker : DecisionModel {
    override val name = "plan-picker"
    val states = mutableListOf<String>()
    override suspend fun choose(choice: Choice): Ranking {
        states += choice.state
        return Ranking.of(choice.id, name, choice.options.map { it.id to if (it.id == "follow_plan_step") 1.0 else 0.0 })
    }
}

private fun digest(smX: Int, smY: Int, worldX: Int, worldY: Int) =
    "smPlayerX=$smX,smPlayerY=$smY,worldX=$worldX,worldY=$worldY,gold=400,menuCursor=0,screenState=0"

/**
 * A turn's effect is measured from where the party stands next turn, not from the tool's
 * own message.
 *
 * `walkTo` reports "townWalk: reached EXACT (11,11) in 19 steps" — no `sm=`, no `world=`,
 * nothing a regex over the message can compare. Reading movement out of that message alone
 * makes every successful town walk look like a turn that changed nothing, and the goals
 * that give up when nothing is happening then give up on a step that is working. A real
 * run did exactly that: the plan was taken off the menu two turns after it walked the
 * party across Coneria.
 */
class GoalTurnEffectTest : FunSpec({

    test("a walk whose message carries no coordinates still counts as movement") {
        val run = RunDirectory(createTempDirectory("knes-goal-effect")).also { it.ensure() }
        val memory = Memory(run)
        memory.setPlan(
            Plan(
                createdAtTurn = 0,
                milestone = "enter_weapon_shop",
                steps = listOf(PlanStep(0, "walk to the weapon counter", "walkTo", mapOf("x" to "11", "y" to "11"))),
            ),
        )
        val picker = PlanPicker()
        val chat = UnusedChat()
        val executor = ExecutorAgent(
            SonnetClient(chat), HaikuClient(chat), SilentToolSurface(), memory, run,
            goals = GoalSelector(Ff1Goals.all(), picker),
            phaseProvider = { Phase.Town },
        )

        executor.act(screenshotB64 = "", ramDigest = digest(16, 23, 147, 155), turn = 1)
        // The party is somewhere else now, which is the only honest evidence the walk worked.
        executor.act(screenshotB64 = "", ramDigest = digest(11, 11, 147, 155), turn = 2)

        picker.states.last() shouldContain "follow_plan_step: Ok (moved)"
        picker.states.last() shouldNotContain "nothing moved"

        run.root.toFile().deleteRecursively()
    }

    test("a walk that ended where it started is still no movement") {
        val run = RunDirectory(createTempDirectory("knes-goal-effect")).also { it.ensure() }
        val memory = Memory(run)
        memory.setPlan(
            Plan(0, "enter_weapon_shop", listOf(PlanStep(0, "walk", "walkTo", mapOf("x" to "11", "y" to "11")))),
        )
        val picker = PlanPicker()
        val chat = UnusedChat()
        val executor = ExecutorAgent(
            SonnetClient(chat), HaikuClient(chat), SilentToolSurface(), memory, run,
            goals = GoalSelector(Ff1Goals.all(), picker),
            phaseProvider = { Phase.Town },
        )

        executor.act(screenshotB64 = "", ramDigest = digest(16, 23, 147, 155), turn = 1)
        executor.act(screenshotB64 = "", ramDigest = digest(16, 23, 147, 155), turn = 2)

        picker.states.last() shouldContain "follow_plan_step: Ok (nothing moved)"

        run.root.toFile().deleteRecursively()
    }
})
