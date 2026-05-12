package knes.agent.v2.agents

import knes.agent.perception.LandmarkMemory
import knes.agent.runtime.LandmarkContext
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
    private val landmarks: LandmarkMemory? = null,
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

    private fun landmarksDigest(): String {
        val lm = landmarks ?: return ""
        val rendered = LandmarkContext.render(lm) ?: return ""
        // Append concrete guidance so the planner stops emitting raw walkTo(x,y)
        // toward in-town NPCs (Smoke 0 T2-T7 trace): interior-coord landmarks
        // (mapId>=0 with localX/localY) are only reachable via interactAt at
        // those local coords once the party is in the matching map/town.
        return """
            $rendered

            Landmark usage:
            - Overworld landmarks (worldX,worldY) — valid targets for walkTo / interactAt while phase=Overworld.
            - Town-overlay landmarks (mapId=0, has localX/localY) — the party must first cross a TOWN_ENTRY
              tile via walkTo on the overworld; once mapflags.bit0=1, the party is in town overlay. Town
              walkTo is currently unimplemented (returns Reject); the next planned tool call once inside
              town should be buyAtShop (which probes the shopkeeper directly) or interactAt at the local
              coords if the party already happens to be adjacent.
            - True interior landmarks (mapId>0) — interactAt with local coords once inside that mapId.
        """.trimIndent()
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

        ${landmarksDigest()}

        Output schema (STRICT — every step uses one of the seven tools below; intentArgs values are STRINGS):
        {"steps":[
          {"index":0,"description":"...","intentTool":"<tool>","intentArgs":{"<key>":"<value>"}},
          ...
        ]}

        TOOLS — pick exactly one per step, match arg keys precisely:
          - boot                          intentArgs: {}                                         (title→party creation→overworld; use for the FIRST step of a fresh campaign)
          - walkTo                        intentArgs: {"x":"<int>","y":"<int>"}                  (overworld OR indoor; phase auto-detected)
          - interactAt                    intentArgs: {"x":"<int>","y":"<int>"}                  (walkTo then A — NPC/chest/door)
          - useMenu                       intentArgs: {"path":"<menu-path>"}                     (FIELD menu only — see grammar below; NOT for title screen)
          - buyAtShop                     intentArgs: {"items":"0,1,2,3","charSlots":"0,1,2,3"}  (comma-joined int lists, equal length)
          - equipWeapon                   intentArgs: {"charSlot":"<0-3>","weaponSlot":"<0-3>"}
          - restAtInn                     intentArgs: {"innMapId":"<int>"}
          - battleFightAll                intentArgs: {}

        useMenu path grammar (use ONLY these tokens — never raw labels like "NEW GAME" / "FIGHTER"):
          main/<item|magic|equip|status|exit>[/char1|char2|char3|char4][/weapon|armor][/<0-3>]
          shop/<buy|sell|exit>[/<0-7>][/char1|char2|char3|char4]
        Example: main/equip/char1/weapon/0

        For a FRESH campaign (Boot phase), step 0 MUST be {"intentTool":"boot","intentArgs":{}}.
        Plans for Boot phase should NOT use useMenu — boot() handles the entire title→class-selection→overworld flow.
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
