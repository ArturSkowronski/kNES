package knes.agent.runtime

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import kotlin.io.path.exists
import kotlin.io.path.readText

class Memory(val run: RunDirectory) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    var campaign: Campaign = loadOrInitCampaign()
        private set

    var currentPlan: Plan? = loadPlanIfExists()
        private set

    fun saveCampaign() {
        atomicWrite(run.campaignJson, json.encodeToString(Campaign.serializer(), campaign))
    }

    fun setPlan(plan: Plan) {
        currentPlan = plan
        atomicWrite(run.currentPlanJson, json.encodeToString(Plan.serializer(), plan))
    }

    fun appendTurn(log: TurnLog) {
        val out = json.encodeToString(TurnLog.serializer(), log)
        atomicWrite(run.turnDecisionFile(log.turn), out)
        campaign.lastTurn = log.turn
        saveCampaign()
    }

    fun appendReviewLine(line: String) {
        Files.writeString(
            run.reviewJsonl, line + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    private fun loadOrInitCampaign(): Campaign =
        if (run.campaignJson.exists()) {
            json.decodeFromString(Campaign.serializer(), run.campaignJson.readText())
        } else {
            Campaign(
                startedAt = OffsetDateTime.now().toString(),
                scope = "coneria_buy_equip_grind",
                milestones = mutableListOf(
                    Milestone(id = "boot",          status = "in_progress"),
                    Milestone(id = "enter_coneria", status = "pending"),
                    // Event-type checkpoint: party reaches the Coneria
                    // weapon-shop counter tile. Latches once and is NOT
                    // re-verified (party will naturally leave the tile
                    // during the buy menu — we don't want regression).
                    Milestone(id = "enter_weapon_shop", status = "pending"),
                    // Split-out checkpoint: ≥1 char holds ≥1 weapon. This
                    // separates "we entered the shop and a buy succeeded"
                    // from "weapons are equipped" — the Advisor replans on
                    // each advance and the audit-hysteresis counter resets,
                    // so the equipping phase doesn't suffer noise from
                    // mid-buy navigation observations. (Reverses 2026-05-12
                    // merge, but with a tighter predicate: arm_party still
                    // requires ≥2 EQUIPPED so a single buy can't falsely
                    // satisfy the armed-up goal.)
                    Milestone(id = "buy_weapons",   status = "pending"),
                    Milestone(id = "arm_party",     status = "pending"),
                    Milestone(id = "exit_coneria", status = "pending"),
                    Milestone(id = "grind",         status = "pending"),
                ),
            ).also { c ->
                atomicWrite(run.campaignJson, json.encodeToString(Campaign.serializer(), c))
            }
        }

    private fun loadPlanIfExists(): Plan? =
        if (run.currentPlanJson.exists()) json.decodeFromString(
            Plan.serializer(), run.currentPlanJson.readText()
        ) else null

    private fun atomicWrite(path: Path, content: String) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
