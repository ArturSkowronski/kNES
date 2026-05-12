package knes.agent.v2.agents

import knes.agent.llm.AnthropicSession
import knes.agent.v2.llm.SonnetClient
import knes.agent.v2.runtime.Plan
import knes.agent.v2.runtime.PlanStep
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.tools.ToolOutcome
import knes.agent.v2.tools.ToolSurface

data class ExecutorDecision(
    val tool: String,
    val args: Map<String, String>,
    val reasoning: String,
    val outcome: ToolOutcome,
    val ms: Long,
)

/**
 * Per-turn tool picker. Given current plan + observation (screenshot + RAM
 * digest), Sonnet 4.6 picks the next tool and arguments. ToolSurface invokes
 * it; we return the outcome for memory.appendTurn.
 *
 * Implementation: for the first cut, prefer the plan's intentTool/intentArgs
 * verbatim (trust Advisor). If plan cursor exhausted OR intentTool null OR
 * last 2 outcomes were not Ok, ask Sonnet via a structured prompt (placeholder
 * fallback for now).
 */
class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val sonnet: SonnetClient,
    private val tools: ToolSurface,
    private val memory: V2Memory,
) {
    private val recentOutcomes = ArrayDeque<String>(4)

    suspend fun act(screenshotB64: String, ramDigest: String): ExecutorDecision {
        val started = System.currentTimeMillis()
        val plan = memory.currentPlan
        val step = plan?.steps?.getOrNull(plan.cursor)

        // Plan exhausted (cursor past last step) — signal Reject directly so the
        // watchdog ticks and the Advisor replans. The previous "fallback to
        // plan tail" repeated the last step forever, masking progress: Smoke 1
        // v3 spent 266/300 turns hammering useMenu(shop/exit) via this path.
        if (plan != null && step == null) {
            val outcome = ToolOutcome.Reject("plan exhausted at cursor=${plan.cursor}/${plan.steps.size} — awaiting advisor replan")
            recentOutcomes.addLast(outcome.javaClass.simpleName)
            if (recentOutcomes.size > 4) recentOutcomes.removeFirst()
            return ExecutorDecision(
                tool = "(none)", args = emptyMap(),
                reasoning = "plan exhausted — Reject to trigger replan",
                outcome = outcome,
                ms = System.currentTimeMillis() - started,
            )
        }

        val (tool, args, reasoning) =
            if (shouldUsePlanHint(step)) {
                Triple(step!!.intentTool!!, step.intentArgs ?: emptyMap(),
                    "plan step ${step.index}: ${step.description}")
            } else {
                askLlm(plan, screenshotB64, ramDigest)
            }

        val outcome = dispatch(tool, args)
        recentOutcomes.addLast(outcome.javaClass.simpleName)
        if (recentOutcomes.size > 4) recentOutcomes.removeFirst()
        if (outcome is ToolOutcome.Ok && plan != null) advancePlan(plan)

        return ExecutorDecision(tool, args, reasoning, outcome, System.currentTimeMillis() - started)
    }

    private fun shouldUsePlanHint(step: PlanStep?): Boolean {
        if (step == null || step.intentTool == null) return false
        val recentFails = recentOutcomes.takeLast(2).count { it != "Ok" }
        return recentFails < 2
    }

    private suspend fun askLlm(plan: Plan?, screenshotB64: String, ramDigest: String): Triple<String, Map<String, String>, String> {
        // TODO(C3-followup): plug a Koog tool registry so Sonnet can call tools natively.
        // Until then: return the "(none)" sentinel so dispatch Rejects. The prior
        // "fallback to plan tail" caused Sonnet to repeat the plan's last step
        // forever once the recent-failure threshold was hit (Smoke 1 v3 evidence).
        return Triple("(none)", emptyMap(), "askLlm not yet wired — Reject to trigger advisor replan")
    }

    private suspend fun dispatch(tool: String, args: Map<String, String>): ToolOutcome = when (tool) {
        "boot"            -> tools.boot()
        "walkTo"          -> tools.walkTo(args.getValue("x").toInt(), args.getValue("y").toInt())
        "interactAt"      -> tools.interactAt(args.getValue("x").toInt(), args.getValue("y").toInt())
        "useMenu"         -> tools.useMenu(args.getValue("path"))
        "buyAtShop"       -> tools.buyAtShop(
            args.getValue("items").split(",").map { it.toInt() },
            args.getValue("charSlots").split(",").map { it.toInt() },
        )
        "equipWeapon"     -> tools.equipWeapon(args.getValue("charSlot").toInt(), args.getValue("weaponSlot").toInt())
        "restAtInn"       -> tools.restAtInn(args.getValue("innMapId"))
        "battleFightAll"  -> tools.battleFightAll()
        else              -> ToolOutcome.Reject("unknown tool: $tool")
    }

    private fun advancePlan(plan: Plan) {
        val advanced = plan.copy(cursor = (plan.cursor + 1).coerceAtMost(plan.steps.size))
        memory.setPlan(advanced)
    }
}
