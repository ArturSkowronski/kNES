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
        knes.agent.v2.runtime.Log.advisor("plan(${plan.steps.size} steps) ← $reason", turn)
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
            - Town-overlay landmarks (mapId=0, has localX/localY) — once mapflags.bit0=1 (phase=Town),
              walkTo args become TOWN-LOCAL (smPlayerX/smPlayerY 0-31). walkTo ends Ok when adjacent.
              The Executor drives any subsequent shop/inn dialog via raw `sequence` taps.
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

            RECOMMENDED Coneria shopping list — buy a DIVERSE set so the
            equip phase never gets blocked by class mismatch:

              shop slot 2 (Nunchucks) × 1   — MANDATORY (BlackBelt's only
                                              non-Staff option; without
                                              this the BlackBelt cannot
                                              equip anything except a Staff)
              shop slot 3 (Rapier)    × 2   — covers Fighter, Thief, RedMage
              shop slot 4 (Iron Hammer) × 1 — primary for Fighter or RedMage

            Total: 4 weapons, cost = 40G (well under starting 400G).

            After purchase, equip pairing for default party:
              charSlot 0 (Fighter)   → Iron Hammer  (or Rapier as backup)
              charSlot 1 (Thief)     → Rapier
              charSlot 2 (BlackBelt) → Nunchucks    (only option)
              charSlot 3 (RedMage)   → Rapier       (or Hammer as backup)

            RULE: never buy 2 copies of the SAME weapon if that weapon
            has narrow class compatibility. Two Rapiers is fine (Fighter
            + Thief or Fighter + RedMage can both use them). Two Knives
            would lock the BlackBelt out entirely. Two Hammers would
            lock out the Thief. Aim for at least one weapon per class
            need; THEN duplicate the cheap broadly-compatible ones if
            budget allows.

            Plan templates (THREE milestones now — enter_weapon_shop →
            buy_weapons → arm_party):

            enter_weapon_shop (party at sm=(11,11), phase=Town, mid=0):
              [0] walkTo({"x":"11","y":"11"})
                  description: "Walk to sm=(11,11) — the tile directly
                  SOUTH of the Coneria weapon shopkeeper. Coord is an
                  anchor — actually navigate by VISION: stay on the main
                  paved path, head NORTH, look for the 'WEAPON' sign and
                  a sword/club sprite on the counter. Skip dead-end side-
                  branches that terminate at the INN (purple 'INN' sign,
                  west of trunk) or at ARMOR. Stop when adjacent-south of
                  the WEAPON keeper, facing N. NEVER tap Right or Down off
                  the southern edge row (smY ≥ 24) — that exits to
                  Overworld. Milestone latches when the party stands on
                  sm=(11,11)."

            buy_weapons (≥1 char holds any weapon):
              [0] interactAt({"x":"11","y":"10"})
                  description: "Tap A on the WEAPON shopkeeper to open the
                  shop dialog, then drive the buy menu via raw `sequence`
                  taps (no buyAtShop tool).

                  *** MANDATORY SHOPPING LIST (40G total, do NOT deviate) ***
                  This exact basket is required because the equip phase
                  silently deadlocks if any character has only
                  wrong-class items in inventory. Two Knives blocks the
                  BlackBelt; two Hammers blocks the Thief. The list
                  below ensures EVERY default-party char (Fighter / Thief /
                  BlackBelt / RedMage) has at least one weapon they can
                  equip.

                    1. shop slot 2 (Wooden Nunchucks)  for charSlot 2 (BlackBelt)  — MANDATORY
                    2. shop slot 4 (Iron Hammer)       for charSlot 0 (Fighter)
                    3. shop slot 3 (Rapier)            for charSlot 1 (Thief)
                    4. shop slot 3 (Rapier)            for charSlot 3 (RedMage)

                  Total: 4 items, 40G. Buy in the order above (Nunchucks
                  FIRST so the BlackBelt is guaranteed covered even if
                  budget or menu state goes sideways later). For each
                  purchase: Down×N to highlight the shop slot → A →
                  Down×N to highlight the target charSlot → A → A on
                  the Yes confirmation. Watch the gold counter drop and
                  the partyWeaponDigest line update each turn — that's
                  the source of truth for what's in inventory.

                  When all 4 chars hold their assigned weapon, tap B
                  several times to exit. Cursor stays on this plan step
                  through the entire buy phase (sequence ≠ interactAt,
                  no false advance). The buy_weapons milestone latches
                  on the FIRST successful purchase (any weapon byte > 0);
                  the Advisor will replan to arm_party as soon as that
                  fires, but DO NOT stop buying — the agent should keep
                  shopping the list until ALL 4 pairs are bought before
                  exiting the dialog."

            arm_party (≥2 chars have a weapon equipped — bit7 set):
              [0] intentTool="armCharsViaMenu"  intentArgs={}
                  description: "Equipping is done VIA RAW SEQUENCE TAPS
                  by the Executor (see EQUIPPING PLAYBOOK in the Executor
                  system prompt). Cursor stays on this step until the
                  arm_party milestone latches at ≥2 equipped chars; the
                  Advisor will then replan to exit_coneria."
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

        Output schema (STRICT — every step uses one of the tools below; intentArgs values are STRINGS):
        {"steps":[
          {"index":0,"description":"...","intentTool":"<tool>","intentArgs":{"<key>":"<value>"}},
          ...
        ]}

        TOOLS — pick exactly one per step, match arg keys precisely:
          - boot              intentArgs: {}                                  (title→party→overworld; FIRST step of fresh campaign)
          - walkTo            intentArgs: {"x":"<int>","y":"<int>"}           (overworld OR indoor; phase auto-detected)
          - interactAt        intentArgs: {"x":"<int>","y":"<int>"}           (walkTo then A — NPC/chest/door)
          - useMenu           intentArgs: {"path":"<menu-path>"}              (FIELD menu only — see grammar below)
          - restAtInn         intentArgs: {"innMapId":"<int>"}
          - battleFightAll    intentArgs: {}
          - armCharsViaMenu   intentArgs: {}                                  (SENTINEL — not a real tool. Parks cursor on the
                                                                              multi-turn "equip via raw taps" phase for arm_party.
                                                                              Cursor advances only when the milestone latches.)

        Shopping and equipping run through raw `sequence` taps from the
        Executor (not as Advisor steps). The Advisor authors at most
        `walkTo` to position, then `interactAt` to open the shop dialog,
        then `armCharsViaMenu` to mark the equipping phase.

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
