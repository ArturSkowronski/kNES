package knes.agent.goals

import knes.agent.agents.ExecutorAgent
import knes.agent.tools.results.AgentConfig
import knes.agent.tools.results.GoalSpec

/**
 * The goals a game declares, turned into things the selector can ask.
 *
 * Nothing here is written per game: `profiles/<id>.json` says what the goals are, in the
 * same file that already says which phase the RAM means. A goal list in Kotlin is a game
 * constant living in runtime code, and Super Mario Bros was what showed it — the cartridge
 * arrived to find `boot`, `walkTo` and Final Fantasy's shop warnings waiting for it.
 *
 * What the runtime adds is the two things a profile cannot express, because they need
 * history rather than a snapshot of RAM: whether a goal has stopped achieving anything, and
 * whether the Advisor's current plan step is worth dispatching.
 */
object Goals {

    /** Empty for a game whose profile declares no goals; the chat model then keeps the turn. */
    fun of(profileId: String?): List<Goal> =
        AgentConfig.of(profileId)?.goals?.map(::fromSpec) ?: emptyList()

    internal fun fromSpec(spec: GoalSpec): Goal =
        if (spec.followsPlan) PlanStepGoal(spec) else DeclaredGoal(spec)
}

/**
 * A goal that is entirely data: the profile says when it applies and what it presses.
 *
 * The stall rule is the runtime's part. Minecraft asks a running goal `canContinueToUse()`
 * every tick and stops it when the answer is no; ceasing to be an option is the only way a
 * goal can say no here, and a snapshot of RAM cannot tell that it has been failing.
 */
internal class DeclaredGoal(private val spec: GoalSpec) : Goal {
    override val id = spec.id
    override val priority = spec.priority

    override fun canUse(world: WorldSnapshot): Boolean {
        if (world.stalledOn(id) >= spec.retryLimit) return false
        if (spec.applies(world.phase.name, world.ram)) return true
        // A menu the phase classifier does not recognise looks, from outside, exactly like a
        // screen that keeps changing while the player never does.
        val stuckFor = spec.alsoWhenStuckFor ?: return false
        return world.turnsWithoutProgress >= stuckFor
    }

    override fun describe(world: WorldSnapshot) = spec.description

    override fun act(world: WorldSnapshot) = GoalAction(spec.tool, spec.args)
}

/**
 * Do what the Advisor's plan says next.
 *
 * The plan is a suggestion, which is why it is one option among several rather than a fast
 * path around the decision — a plan written for the overworld has walked the party
 * north-west into Coneria Castle before now. It drops off the menu when its tool is not
 * dispatchable, so a plan naming something that does not exist costs nothing instead of a
 * turn; and when the profile guards that tool to a phase the game is not in, because the
 * opening plan always starts with `boot` and the turn loop has already booted.
 */
internal class PlanStepGoal(private val spec: GoalSpec) : Goal {
    override val id = spec.id
    override val priority = spec.priority

    override fun canUse(world: WorldSnapshot): Boolean {
        val tool = world.planStep?.intentTool ?: return false
        if (tool !in ExecutorAgent.DISPATCHABLE) return false
        if (!spec.guardAllows(tool, world.phase.name)) return false
        return world.turnsWithoutProgress < spec.retryLimit
    }

    override fun describe(world: WorldSnapshot): String {
        val step = world.planStep ?: return spec.description
        return "${spec.description} ${step.description.trim().trimEnd('.')} " +
            "(${step.intentTool} ${step.intentArgs ?: emptyMap()})."
    }

    override fun act(world: WorldSnapshot): GoalAction {
        val step = world.planStep ?: error("${spec.id} acted without a plan step")
        return GoalAction(
            step.intentTool ?: error("plan step ${step.index} has no tool"),
            step.intentArgs ?: emptyMap(),
        )
    }
}
