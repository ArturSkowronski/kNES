package knes.agent.goals

import knes.agent.agents.ExecutorAgent
import knes.agent.runtime.Phase

/**
 * What the party is allowed to want, playing Final Fantasy 1.
 *
 * Small on purpose. Every goal here is applicable from [Phase] alone — which the active
 * profile's semantics already classify from RAM — or from the plan the Advisor wrote.
 * Nothing in this file guesses at FF1 internals the project has not established; the
 * shop row-to-item mapping and the EQUIP sub-header layout are still open questions and
 * a goal that pretended to know them would just be a confident way to be wrong.
 *
 * The priorities are the order to fall back through when no model ranks them, and they
 * read as a policy: finish what the game forces (boot, battle), get unstuck, do what the
 * plan said, then the raw taps.
 */
object Ff1Goals {

    /** Phases where the party is standing on a map and a direction press moves it. */
    private val ON_FOOT = setOf(Phase.Town, Phase.Indoors, Phase.Overworld)

    /** Phases where an A press talks, reads or confirms rather than moving. */
    private val INTERACTIVE = setOf(Phase.Town, Phase.Indoors, Phase.MenuStuck)

    /**
     * Tools that only make sense in one phase, whatever a plan says.
     *
     * The Advisor's opening plan always starts with `boot`, and the turn loop has already
     * booted by the time it runs. The chat-model Executor sees the screenshot and quietly
     * ignores that step; the selector took it at its word, pressed START on a game that
     * was already on the overworld, and opened the main menu. The phase classifier does
     * not call that menu a menu, so every later turn read as `Overworld` while Up and Down
     * moved a cursor and the party never moved again — twenty-four turns lost to one
     * misplaced tool. The dedicated goals already carry these guards; this is what stops
     * the plan from routing around them.
     */
    private val PHASE_GUARDS = mapOf(
        "boot" to setOf(Phase.Boot),
        "battleFightAll" to setOf(Phase.Battle),
    )

    /**
     * Turns a goal may spend changing nothing before it comes off the menu.
     *
     * Enough to ride out one wandering NPC, not enough to spend a run on one button.
     */
    const val RETRY_LIMIT = 3

    fun all(): List<Goal> = listOf(
        stallable(
            id = "boot",
            priority = 0,
            description = "Press through the title and intro screens until the party is standing on the map.",
            action = GoalAction("boot"),
            applies = { it.phase == Phase.Boot },
        ),
        stallable(
            id = "fight_battle",
            priority = 0,
            description = "Attack with every character until the battle is over.",
            action = GoalAction("battleFightAll"),
            applies = { it.phase == Phase.Battle },
        ),
        stallable(
            id = "back_out_of_menu",
            priority = 5,
            description = "Press B to close the menu or dialog that is open and get back to the map.",
            action = GoalAction("sequence", mapOf("buttons" to "B")),
            // Also offered when nothing has moved for a while, whatever the phase says. A
            // menu the classifier does not recognise looks exactly like this from the
            // outside: the screen keeps changing and the party never does.
            applies = { it.phase in INTERACTIVE || it.turnsWithoutProgress >= RETRY_LIMIT },
        ),
        FollowPlanStep,
        stallable(
            id = "press_a",
            priority = 20,
            // The warning is not decoration: one blind A at the Coneria weapon counter
            // sold every weapon the party had just bought and put the gold back up.
            description = "Press A to talk to whoever is in front of the party, or to confirm what the " +
                "open dialog is asking. In a shop this can confirm a SALE rather than a purchase, so " +
                "only pick it when the dialog has been read.",
            action = GoalAction("sequence", mapOf("buttons" to "A")),
            applies = { it.phase in INTERACTIVE },
        ),
        step("step_north", 30, "Up", "north"),
        step("step_south", 31, "Down", "south"),
        step("step_west", 32, "Left", "west"),
        step("step_east", 33, "Right", "east"),
    )

    /**
     * A single tile of movement.
     *
     * One tap rather than a run of them: FF1's town NPCs wander every frame, so a tile
     * that was blocked last turn may be open this one, and a four-tap run commits to a
     * route through a world that moved underneath it.
     */
    private fun step(id: String, priority: Int, button: String, compass: String) = stallable(
        id = id,
        priority = priority,
        description = "Walk one tile $compass.",
        action = GoalAction("sequence", mapOf("buttons" to button)),
        applies = { it.phase in ON_FOOT },
    )

    /**
     * A goal that takes itself off the menu once it has stopped achieving anything.
     *
     * Minecraft asks a running goal `canContinueToUse()` every tick and stops it when the
     * answer is no. A goal has no way to say no here except by ceasing to be an option, so
     * every goal in this file wraps its own `canUse` in that rule, and the model is forced
     * off a button that is achieving nothing rather than being trusted to notice. If
     * everything applicable falls silent the selector declines the turn and the chat-model
     * Executor takes it — nothing written down is working, so ask something that can think
     * of something that is not written down.
     */
    private fun stallable(
        id: String,
        priority: Int,
        description: String,
        action: GoalAction,
        applies: (WorldSnapshot) -> Boolean,
    ) = SimpleGoal(id, priority, description, action) { world ->
        applies(world) && world.stalledOn(id) < RETRY_LIMIT
    }

    /**
     * Do what the Advisor's plan says next.
     *
     * The plan is a suggestion, which is why it is one option among several rather than
     * a fast path around the decision — a plan written for the overworld has walked the
     * party north-west into Coneria Castle before now. The step only becomes an option
     * when its tool is one the Executor can actually dispatch, so a plan naming something
     * that does not exist quietly drops off the menu instead of costing a turn.
     *
     * And it stops being an option once [RETRY_LIMIT] turns running have changed nothing.
     * The first smoke run of the selector spent all twelve turns here: `walkTo(147,155)`
     * came back "no path within viewport" five times and the model still ranked the plan
     * at 0.94, because a plan step reads like the sensible thing to do no matter how the
     * last attempt went. Minecraft has the same problem and the same answer — a running
     * goal is asked `canContinueToUse()` every tick and is stopped when it says no.
     * Taking the step off the menu is how a goal says no here. The count resets when the
     * Advisor writes a new plan, so this bounds one plan's attempts, not the plan.
     */
    private object FollowPlanStep : Goal {
        override val id = "follow_plan_step"
        override val priority = 10

        /** Enough to ride out one wandering NPC, not enough to spend a run on one step. */
        const val RETRY_LIMIT = 3

        override fun canUse(world: WorldSnapshot): Boolean {
            val tool = world.planStep?.intentTool ?: return false
            if (tool !in ExecutorAgent.DISPATCHABLE) return false
            if (PHASE_GUARDS[tool]?.contains(world.phase) == false) return false
            return world.turnsWithoutProgress < RETRY_LIMIT
        }

        override fun describe(world: WorldSnapshot): String {
            val step = world.planStep ?: return "Do what the plan says next."
            return "Do what the plan says next: ${step.description.trim().trimEnd('.')} " +
                "(${step.intentTool} ${step.intentArgs ?: emptyMap()})."
        }

        override fun act(world: WorldSnapshot): GoalAction {
            val step = world.planStep ?: error("follow_plan_step acted without a plan step")
            return GoalAction(step.intentTool ?: error("plan step ${step.index} has no tool"), step.intentArgs ?: emptyMap())
        }
    }
}
