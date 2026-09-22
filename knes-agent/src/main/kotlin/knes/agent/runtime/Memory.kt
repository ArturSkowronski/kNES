package knes.agent.runtime

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import kotlin.io.path.exists
import kotlin.io.path.readText

class Memory(
    val run: RunDirectory,
    /**
     * Which game's goal list a fresh run starts with.
     *
     * The list used to be written out here, so every run opened with Final Fantasy's
     * milestones whatever it was playing — a Super Mario Bros run began with
     * `buy_weapons` pending.
     */
    val campaignRules: knes.agent.campaign.Campaign = knes.agent.campaign.NoCampaign,
) {
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
                scope = campaignRules.scope,
                milestones = campaignRules.initialMilestones
                    .mapIndexed { i, id -> Milestone(id = id, status = if (i == 0) "in_progress" else "pending") }
                    .toMutableList(),
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
