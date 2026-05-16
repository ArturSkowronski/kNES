package knes.agent.runtime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Files

class MemoryTest : StringSpec({
    "campaign + plan + turn-log survive write + reopen" {
        val tmpRoot = Files.createTempDirectory("v2-mem-test")
        val run = RunDirectory(tmpRoot).also { it.ensure() }

        val m1 = Memory(run)
        // First-open auto-seeds the coneria_buy_equip_grind milestones; clear them so
        // this test exercises a clean append + reopen round-trip.
        m1.campaign.milestones.clear()
        m1.campaign.milestones += Milestone(id = "boot", status = "done", turnStart = 1, turnEnd = 47)
        m1.campaign.plans += PlanEntry(turn = 48, by = "advisor", summary = "head to (15,22)", snapshot = "snapshots/turn-00048.png")
        m1.saveCampaign()

        val plan = Plan(
            createdAtTurn = 48,
            milestone = "buy_weapons",
            steps = listOf(PlanStep(0, "walk to weapon shop", "walkTo", mapOf("x" to "15", "y" to "22"))),
        )
        m1.setPlan(plan)

        val turnLog = TurnLog(
            turn = 49, frame = 1234L, phase = "Overworld",
            ram = mapOf("worldX" to 14, "worldY" to 21),
            snapshot = "snapshots/turn-00049.png",
            executor = ExecutorTrace("claude-sonnet-4-6", "walkTo", mapOf("x" to "15", "y" to "22"), "step 0", "ok", null, 200),
            watchdog = WatchdogTrace(0, 5),
        )
        m1.appendTurn(turnLog)

        // Reopen
        val m2 = Memory(RunDirectory(tmpRoot))
        m2.campaign.milestones.map { it.id } shouldContainExactly listOf("boot")
        m2.campaign.lastTurn shouldBe 49
        m2.currentPlan?.steps?.size shouldBe 1
        m2.currentPlan?.steps?.first()?.intentArgs?.get("x") shouldBe "15"
    }
})
