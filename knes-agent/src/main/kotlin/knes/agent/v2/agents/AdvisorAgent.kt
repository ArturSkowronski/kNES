package knes.agent.v2.agents

import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.runtime.Plan
import knes.agent.v2.runtime.PlanEntry
import knes.agent.v2.runtime.PlanStep
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory

/**
 * Advisor writes a numbered plan into current_plan.json and appends a
 * PlanEntry to campaign.json. Called on T=0, stuck-signal, and phase
 * boundaries. ~2% of total LLM calls.
 */
class AdvisorAgent(
    private val gemini: GeminiPro31Client,
    private val memory: V2Memory,
    private val run: V2RunDirectory,
) {
    suspend fun plan(reason: String, screenshotB64: String, turn: Int) {
        val prompt = buildPrompt(reason)
        val raw = gemini.generate(prompt, imageB64 = screenshotB64)
        val plan = parsePlan(raw, milestone = currentMilestone(), turn = turn)
        memory.setPlan(plan)
        memory.campaign.plans += PlanEntry(
            turn = turn, by = "advisor",
            summary = plan.steps.firstOrNull()?.description ?: "(no steps)",
            snapshot = "snapshots/turn-%05d.png".format(turn),
            reason = reason,
        )
        memory.saveCampaign()
        System.err.println("[v2.advisor] turn=$turn reason=$reason steps=${plan.steps.size}")
    }

    private fun currentMilestone(): String =
        memory.campaign.milestones.firstOrNull { it.status == "in_progress" }?.id
            ?: memory.campaign.milestones.firstOrNull { it.status == "pending" }?.id
            ?: "boot"

    private fun buildPrompt(reason: String): String = """
        You are the FF1 strategic advisor. Produce a NUMBERED plan (max 8 steps)
        to advance the current milestone. Respond ONLY with JSON.

        Current milestone: ${currentMilestone()}
        Trigger reason: $reason
        Campaign so far: ${memory.campaign.milestones.joinToString { "${it.id}=${it.status}" }}
        Recent plans: ${memory.campaign.plans.takeLast(3).joinToString("\n") { "T${it.turn}: ${it.summary}" }}

        Output schema:
        {"steps":[
          {"index":0,"description":"...","intentTool":"walkTo|interactAt|useMenu|buyAtShop|equipWeapon|restAtInn|battleFightAll","intentArgs":{"x":"15","y":"22"}},
          ...
        ]}

        Available tools: walkTo(x,y), interactAt(x,y), useMenu(path), buyAtShop(items,charSlots),
        equipWeapon(charSlot,weaponSlot), restAtInn(innMapId), battleFightAll().
    """.trimIndent()

    private fun parsePlan(raw: String, milestone: String, turn: Int): Plan {
        val jsonText = extractJsonObject(raw)
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(PlanWire.serializer(), jsonText)
        return Plan(
            createdAtTurn = turn,
            milestone = milestone,
            steps = parsed.steps.mapIndexed { i, s ->
                PlanStep(i, s.description, s.intentTool, s.intentArgs)
            },
        )
    }

    /**
     * Extracts a top-level {…} JSON object. Tolerates markdown fences
     * (```json …```) and surrounding prose; first `{` to last `}` wins.
     */
    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in response: ${raw.take(200)}" }
        return raw.substring(start, end + 1)
    }

    @kotlinx.serialization.Serializable
    private data class PlanWire(val steps: List<StepWire>)
    @kotlinx.serialization.Serializable
    private data class StepWire(
        val description: String,
        val intentTool: String? = null,
        val intentArgs: Map<String, String>? = null,
    )
}
