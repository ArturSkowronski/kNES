package knes.agent.v2.agents

import knes.agent.llm.AnthropicSession
import knes.agent.v2.llm.HaikuClient
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
 * Per-turn tool picker. Plan-hint dispatch is the default fast path; when the
 * plan exhausts or recent steps keep failing, Sonnet decides what to do next —
 * informed by a Haiku scene description (Option A: vision pre-processed by
 * Haiku, Sonnet reads the text digest).
 */
class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val sonnet: SonnetClient,
    private val haiku: HaikuClient,
    private val tools: ToolSurface,
    private val memory: V2Memory,
) {
    private val recentOutcomes = ArrayDeque<String>(4)
    private var lastPlanCreatedAt: Int = -1

    suspend fun act(screenshotB64: String, ramDigest: String): ExecutorDecision {
        val started = System.currentTimeMillis()
        val plan = memory.currentPlan
        val planCreatedAt = plan?.createdAtTurn ?: -1
        if (planCreatedAt != lastPlanCreatedAt) {
            recentOutcomes.clear()
            lastPlanCreatedAt = planCreatedAt
        }
        val step = plan?.steps?.getOrNull(plan.cursor)

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

    /**
     * Vision-informed tool decision when plan-hint isn't usable (cursor past
     * step, intentTool missing, or 2+ recent fails). Haiku describes the
     * screen → Sonnet picks one tool. On parse / network failure, falls back
     * to plan tail so the emulator keeps running frames.
     */
    private suspend fun askLlm(plan: Plan?, screenshotB64: String, ramDigest: String): Triple<String, Map<String, String>, String> {
        return try {
            val scene = haiku.describeScene(screenshotB64)
            val raw = sonnet.decideTool(EXECUTOR_SYSTEM_PROMPT, buildSonnetUserText(plan, scene, ramDigest))
            parseToolDecision(raw)
        } catch (e: Exception) {
            System.err.println("[v2.executor] askLlm error: ${e.message?.take(160)} — falling back to plan tail")
            val tail = plan?.steps?.lastOrNull()
            if (tail?.intentTool != null) Triple(tail.intentTool, tail.intentArgs ?: emptyMap(), "fallback to plan tail (askLlm exception)")
            else Triple("useMenu", mapOf("path" to "main/exit"), "fallback no-op (no plan, askLlm exception)")
        }
    }

    private fun buildSonnetUserText(plan: Plan?, scene: String, ramDigest: String): String {
        val planSummary = if (plan == null) "(no plan)" else {
            val cur = plan.steps.getOrNull(plan.cursor)
            val tail = plan.steps.lastOrNull()
            buildString {
                append("milestone=${plan.milestone} cursor=${plan.cursor}/${plan.steps.size}\n")
                if (cur != null) append("current step [${cur.index}]: ${cur.intentTool}(${cur.intentArgs ?: emptyMap()}) — ${cur.description}\n")
                if (tail != null && tail.index != cur?.index) append("final step [${tail.index}]: ${tail.intentTool}(${tail.intentArgs ?: emptyMap()}) — ${tail.description}\n")
            }
        }
        val recent = if (recentOutcomes.isEmpty()) "(none)" else recentOutcomes.joinToString(",")
        return """
            Scene (from Haiku vision):
            $scene

            RAM digest:
            $ramDigest

            Current plan context:
            $planSummary

            Recent executor outcomes (last 4): $recent

            Pick ONE tool to execute next turn. Output ONLY JSON:
            {"tool":"<one of: boot|walkTo|interactAt|useMenu|buyAtShop|equipWeapon|restAtInn|battleFightAll>","args":{"<key>":"<value>"},"reasoning":"<≤80 chars why>"}

            Hard rules:
            - For walkTo / interactAt: args = {"x":"<int>","y":"<int>"} (overworld coords)
            - useMenu: args = {"path":"<menu-path>"} — path grammar: main/<item|equip|magic|status|exit>[/charN][/weapon|armor][/0-3] or shop/<buy|sell|exit>[/N][/charN]
            - buyAtShop: args = {"items":"3,2,1,0","charSlots":"0,1,2,3"} (comma lists same length)
            - equipWeapon: args = {"charSlot":"<0-3>","weaponSlot":"<0-3>"}
            - restAtInn: args = {"innMapId":"<int>"}
            - boot / battleFightAll: args = {}
            - Pick battleFightAll ONLY if scene overlay = battle.
            - If the scene shows party-near-shopkeeper (vp distance ≤ 1), pick buyAtShop.
            - If shopkeeper visible but party far, pick walkTo / interactAt at world coords from RAM (worldX,worldY).
            - DO NOT explain. JSON only.
        """.trimIndent()
    }

    private fun parseToolDecision(raw: String): Triple<String, Map<String, String>, String> {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in sonnet response: ${raw.take(200)}" }
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ToolWire.serializer(), raw.substring(start, end + 1))
        return Triple(parsed.tool, parsed.args ?: emptyMap(), "sonnet+haiku: ${parsed.reasoning?.take(80) ?: ""}")
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

    @kotlinx.serialization.Serializable
    private data class ToolWire(
        val tool: String,
        val args: Map<String, String>? = null,
        val reasoning: String? = null,
    )

    companion object {
        private val EXECUTOR_SYSTEM_PROMPT = """
            You are the per-turn tool picker for an FF1 NES playing agent. Read the
            Haiku scene description, RAM digest, current plan, and recent outcomes,
            then pick exactly ONE tool to execute next turn. Respond with JSON only.
            The plan was authored by a strategic Advisor (Gemini 2.5 Pro) — prefer
            following it unless the scene clearly contradicts (e.g. plan says "buy"
            but scene shows battle overlay → fight first).
        """.trimIndent()
    }
}
