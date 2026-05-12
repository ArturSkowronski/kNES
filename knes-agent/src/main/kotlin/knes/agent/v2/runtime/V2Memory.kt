package knes.agent.v2.runtime

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import kotlin.io.path.exists
import kotlin.io.path.readText

class V2Memory(val run: V2RunDirectory) {
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
                    Milestone(id = "buy_weapons",   status = "pending"),
                    Milestone(id = "equip_weapons", status = "pending"),
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
