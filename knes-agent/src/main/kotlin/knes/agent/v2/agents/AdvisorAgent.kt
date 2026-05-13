package knes.agent.v2.agents

import knes.agent.perception.LandmarkMemory
import knes.agent.runtime.LandmarkContext
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.runtime.MilestonePredicates
import knes.agent.v2.runtime.Phase
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
    suspend fun plan(
        reason: String,
        screenshotB64: String,
        turn: Int,
        phase: Phase? = null,
        ram: Map<String, Int>? = null,
    ) {
        run.markActive("advisor", turn)
        val prompt = buildPrompt(reason, phase, ram)
        val raw = gemini.generate(prompt, imageB64 = screenshotB64)
        runCatching {
            run.promptFile(turn, "advisor").toFile().writeText(
                "=== PROMPT (Gemini Pro 3.1) ===\n$prompt\n\n=== RESPONSE ===\n$raw"
            )
        }
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
        run.markIdle()
    }

    private fun landmarksDigest(): String {
        val lm = landmarks ?: return ""
        val raw = LandmarkContext.render(lm) ?: return ""
        // FILTER: misleading preseed entries. ~/.knes/ff1-landmarks.json has
        // an NPC_SHOPKEEPER landmark at mapId=0 local(11,10) — that tile is
        // actually the street tile SOUTH of the weapon shop door, not the
        // shopkeeper (who lives inside the building interior, mapId>0).
        // Strip those lines from the digest so the Advisor doesn't anchor
        // its plan on a coordinate that can't be reached without the agent
        // first entering the building. (v1 keeper-approach reads the raw
        // file separately and already accounts for the offset.)
        val rendered = raw.lineSequence()
            .filterNot { it.contains("NPC_SHOPKEEPER", true) && it.contains("preseed", true) }
            .joinToString("\n")
        // Append concrete guidance so the planner stops emitting raw walkTo(x,y)
        // toward in-town NPCs (Smoke 0 T2-T7 trace): interior-coord landmarks
        // (mapId>=0 with localX/localY) are only reachable via interactAt at
        // those local coords once the party is in the matching map/town.
        return """
            $rendered

            Landmark usage:
            - Overworld landmarks (worldX,worldY) — valid targets for walkTo / interactAt while phase=Overworld.
              walkTo args are overworld world coords (worldX,worldY space).
            - Town-overlay landmarks (mapId=0, has localX/localY) — the party first crosses a TOWN_ENTRY
              tile via walkTo on the overworld; once mapflags.bit0=1 (phase=Town), walkTo args become
              TOWN-LOCAL coords (smPlayerX/smPlayerY space). E.g. walkTo(11,10) in Town walks the party
              to local tile (11,10) using Haiku vision per step. Ends in Ok when party is adjacent.
              After walking adjacent, call buyAtShop.
            - True interior landmarks (mapId>0) — interactAt with local coords once inside that mapId.
        """.trimIndent()
    }

    private fun currentMilestone(): String =
        memory.campaign.milestones.firstOrNull { it.status == "in_progress" }?.id
            ?: memory.campaign.milestones.firstOrNull { it.status == "pending" }?.id
            ?: "boot"

    /**
     * Renders the live RAM signals the Advisor needs to choose phase-appropriate
     * coords. Smoke (2026-05-12) showed the Advisor authoring `walkTo(11,10)`
     * (town-local) as step 0 while phase=Overworld — the Executor then
     * dispatched walkOverworldTo(11,10) which walked NW into Coneria Castle.
     * Telling the Advisor the *current* phase + coord-space lets it compose
     * a step that matches what the Executor will do.
     */
    private fun stateDigest(phase: Phase?, ram: Map<String, Int>?): String {
        if (phase == null || ram == null) return "(state unavailable — picking step 0 must include explicit coord-space)"
        val mapId = ram["currentMapId"] ?: 0
        val mf = ram["mapflags"] ?: 0
        val sx = ram["smPlayerX"] ?: 0
        val sy = ram["smPlayerY"] ?: 0
        val wx = ram["worldX"] ?: 0
        val wy = ram["worldY"] ?: 0
        val gold = ((ram["goldHigh"] ?: 0) shl 16) or ((ram["goldMid"] ?: 0) shl 8) or (ram["goldLow"] ?: 0)
        val coordHint = when (phase) {
            Phase.Overworld -> "walkTo args MUST be world coords (worldX/worldY ~80-240). DO NOT pass small numbers like (11,10)."
            Phase.Town      -> "walkTo args MUST be town-local coords (smPlayerX/smPlayerY 0-31). e.g. (11,10) for the shopkeeper."
            Phase.Indoors   -> "walkTo dispatches exitInterior — your first step from here should be walkTo to leave this map back to Overworld, then re-enter the correct destination."
            else            -> "phase ${phase} — see TOOLS list for valid args."
        }
        val party = MilestonePredicates.partyWeaponDigest(ram)
        val buyDone = (1..4).count { MilestonePredicates.charHoldsAny(it, ram) }
        val equipDone = (1..4).count { MilestonePredicates.charHasEquipped(it, ram) }
        return """
            Current state (live):
              phase=$phase  currentMapId=$mapId  mapflags=$mf
              sm=($sx,$sy)  world=($wx,$wy)  gold=$gold
              party weapons: $party
              progress: $buyDone/4 chars hold a weapon, $equipDone/4 equipped
              hint: $coordHint

            Coneria weapon shop — known item layout (from preseed landmark note
            `items=staff:5,dagger:5,nunchuck:10,rapier:10,hammer:10`):
              shop slot 0 = Wooden Staff   (5G)  → BlackMage, WhiteMage, RedMage, BlackBelt
              shop slot 1 = Small Knife    (5G)  → Fighter, Thief, BlackMage, RedMage
              shop slot 2 = Wooden Nunchucks (10G) → BlackBelt only
              shop slot 3 = Rapier         (10G) → Fighter, Thief, RedMage
              shop slot 4 = Iron Hammer    (10G) → Fighter, WhiteMage, RedMage

            Default party order: charSlot 0=Fighter, 1=Thief, 2=BlackBelt, 3=RedMage.

            RECOMMENDED arm_party assignment for the default party in Coneria:
              charSlot 0 (Fighter)   → buy shop slot 4 (Iron Hammer)   then equip
              charSlot 1 (Thief)     → buy shop slot 3 (Rapier)        then equip
              charSlot 2 (BlackBelt) → buy shop slot 2 (Nunchucks)     then equip
              charSlot 3 (RedMage)   → buy shop slot 3 (Rapier)        then equip
            Total cost = 40G (well under starting 400G).

            One-shot plan template for arm_party:
              [0] walkTo({"x":"11","y":"10"})  — to weapon shop counter
              [1] buyAtShop({"items":"4,3,2,3","charSlots":"0,1,2,3"})  — bundle
              [2..5] equipWeapon for each charSlot in turn

            If buyAtShop returns a per-pair FAIL "WrongClass: char=N itemSlot=K",
            the item is incompatible with THAT class only. Re-plan with the same
            item assigned to a DIFFERENT charSlot that still has no weapon. Do
            not abandon the item.
        """.trimIndent()
    }

    private fun buildPrompt(reason: String, phase: Phase?, ram: Map<String, Int>?): String = """
        You are the FF1 strategic advisor. Produce a NUMBERED plan (max 8 steps)
        to advance the current milestone. Respond ONLY with JSON.

        Current milestone: ${currentMilestone()}
        Trigger reason: $reason
        Campaign so far: ${memory.campaign.milestones.joinToString { "${it.id}=${it.status}" }}
        Recent plans: ${memory.campaign.plans.takeLast(3).joinToString("\n") { "T${it.turn}: ${it.summary}" }}

        ${stateDigest(phase, ram)}

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
