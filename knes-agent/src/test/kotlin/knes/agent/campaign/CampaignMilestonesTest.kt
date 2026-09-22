package knes.agent.campaign

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import knes.agent.runtime.Memory
import knes.agent.runtime.RunDirectory
import kotlin.io.path.createTempDirectory

/**
 * A run starts with the goal list of the game it is playing.
 *
 * The list used to live in [Memory], where every run shared it: a Super Mario Bros run
 * opened with `buy_weapons` pending and `boot` in progress, and the quest window in the
 * viewer showed Final Fantasy's campaign over Mario's screen.
 */
class CampaignMilestonesTest : FunSpec({

    fun freshMemory(campaign: Campaign): Memory {
        val run = RunDirectory(createTempDirectory("knes-campaign")).also { it.ensure() }
        return Memory(run, campaign)
    }

    test("a Final Fantasy run opens on its own campaign, first milestone in progress") {
        val ms = freshMemory(Ff1Campaign).campaign.milestones
        ms.map { it.id } shouldBe Ff1Campaign.initialMilestones
        ms.first().status shouldBe "in_progress"
        ms.drop(1).map { it.status }.toSet() shouldBe setOf("pending")
    }

    test("the milestones a run starts with are the ones the campaign names") {
        Ff1Campaign.initialMilestones shouldContain "buy_weapons"
        Ff1Campaign.initialMilestones shouldContain "arm_party"
    }

    test("a game with no campaign written for it starts with no goals, not someone else's") {
        val ms = freshMemory(NoCampaign).campaign.milestones
        ms.map { it.id } shouldBe emptyList()
    }

    test("Campaign.of picks the campaign by profile, and Mario has none yet") {
        Campaign.of("ff1") shouldBe Ff1Campaign
        Campaign.of("smb") shouldBe NoCampaign
        Campaign.of(null) shouldBe NoCampaign
    }

    test("the scope recorded in the run names the campaign, not a constant") {
        freshMemory(Ff1Campaign).campaign.scope shouldBe Ff1Campaign.scope
        freshMemory(NoCampaign).campaign.scope shouldBe "none"
    }
})
