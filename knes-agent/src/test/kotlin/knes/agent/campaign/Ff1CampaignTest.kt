package knes.agent.campaign

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.runtime.Phase

private fun weapons(vararg slots: Pair<String, Int>) = slots.toMap()

class Ff1CampaignTest : StringSpec({

    "boot latches as soon as the party exists" {
        Ff1Campaign.isSatisfied("boot", Phase.Boot, emptyMap()) shouldBe false
        Ff1Campaign.isSatisfied("boot", Phase.Overworld, emptyMap()) shouldBe true
    }

    "enter_coneria needs the party inside town, not on the entry row" {
        Ff1Campaign.isSatisfied("enter_coneria", Phase.Town, mapOf("smPlayerY" to 20)) shouldBe true
        Ff1Campaign.isSatisfied("enter_coneria", Phase.Town, mapOf("smPlayerY" to 25)) shouldBe true
        Ff1Campaign.isSatisfied("enter_coneria", Phase.Town, mapOf("smPlayerY" to 26)) shouldBe false
        Ff1Campaign.isSatisfied("enter_coneria", Phase.Overworld, mapOf("smPlayerY" to 20)) shouldBe false
    }

    "enter_weapon_shop needs the exact shop tile" {
        val onTile = mapOf("smPlayerX" to 11, "smPlayerY" to 11)
        Ff1Campaign.isSatisfied("enter_weapon_shop", Phase.Town, onTile) shouldBe true
        Ff1Campaign.isSatisfied("enter_weapon_shop", Phase.Town, mapOf("smPlayerX" to 11, "smPlayerY" to 12)) shouldBe false
        Ff1Campaign.isSatisfied("enter_weapon_shop", Phase.Indoors, onTile) shouldBe false
    }

    "buy_weapons latches on the first weapon anyone carries" {
        Ff1Campaign.isSatisfied("buy_weapons", Phase.Town, emptyMap()) shouldBe false
        Ff1Campaign.isSatisfied("buy_weapons", Phase.Town, weapons("char3_weapon2" to 0x03)) shouldBe true
    }

    "arm_party needs two equipped, and carrying is not equipping" {
        // bit7 clear — carried only
        val carried = weapons("char1_weapon0" to 0x03, "char2_weapon0" to 0x04)
        Ff1Campaign.isSatisfied("arm_party", Phase.Town, carried) shouldBe false

        val oneEquipped = weapons("char1_weapon0" to 0x83, "char2_weapon0" to 0x04)
        Ff1Campaign.isSatisfied("arm_party", Phase.Town, oneEquipped) shouldBe false

        val twoEquipped = weapons("char1_weapon0" to 0x83, "char2_weapon0" to 0x84)
        Ff1Campaign.isSatisfied("arm_party", Phase.Town, twoEquipped) shouldBe true
    }

    "exit_coneria only counts once the party has been inside" {
        Ff1Campaign.isSatisfied("exit_coneria", Phase.Overworld, emptyMap()) shouldBe false
        Ff1Campaign.isSatisfied(
            "exit_coneria", Phase.Overworld, emptyMap(), mapOf("enter_coneria" to true)
        ) shouldBe true
        Ff1Campaign.isSatisfied(
            "exit_coneria", Phase.Town, emptyMap(), mapOf("enter_coneria" to true)
        ) shouldBe false
    }

    "grind latches on any experience at all" {
        Ff1Campaign.isSatisfied("grind", Phase.Overworld, emptyMap()) shouldBe false
        Ff1Campaign.isSatisfied("grind", Phase.Overworld, mapOf("char4_xpHigh" to 1)) shouldBe true
    }

    "an unknown milestone never latches" {
        Ff1Campaign.isSatisfied("defeat_chaos", Phase.Overworld, emptyMap()) shouldBe false
    }

    "holding and equipping are counted per character, not per slot" {
        // One character with two equipped weapons is still one armed character.
        val ram = weapons("char1_weapon0" to 0x83, "char1_weapon1" to 0x84)
        Ff1Campaign.countHolding(ram) shouldBe 1
        Ff1Campaign.countEquipped(ram) shouldBe 1
    }

    "gold is a three-byte little-endian counter" {
        Ff1Campaign.gold(mapOf("goldLow" to 0x10, "goldMid" to 0x02, "goldHigh" to 0x01)) shouldBe 0x010210
        Ff1Campaign.gold(emptyMap()) shouldBe 0
    }

    "the party digest names the class and marks equipped weapons" {
        val digest = Ff1Campaign.partyDigest(
            mapOf("char1_class" to 0, "char1_weapon0" to 0x83, "char1_weapon1" to 0x04)
        )
        digest shouldContain "char1:Fighter held=[3*,4]"
        digest shouldContain "char4:? held=[]"
    }

    "event-type milestones are the ones the Reviewer must not re-verify" {
        Ff1Campaign.eventTypeMilestones shouldBe setOf("enter_coneria", "enter_weapon_shop")
    }

    "a game with no campaign never latches anything" {
        val campaign = Campaign.of("smb")
        campaign shouldBe NoCampaign
        campaign.isSatisfied("boot", Phase.Overworld, emptyMap()) shouldBe false
        campaign.countEquipped(mapOf("char1_weapon0" to 0x83)) shouldBe 0
        campaign.gold(mapOf("goldLow" to 5)) shouldBe 0
    }

    "the FF1 campaign is selected by profile id, case insensitively" {
        Campaign.of("ff1") shouldBe Ff1Campaign
        Campaign.of("FF1") shouldBe Ff1Campaign
        Campaign.of(null) shouldBe NoCampaign
    }

    "a weapon byte splits into an equipped flag and an item id" {
        Ff1Campaign.weaponId(0x83) shouldBe 3
        Ff1Campaign.weaponId(0x03) shouldBe 3
        Ff1Campaign.isEquipped(0x83) shouldBe true
        Ff1Campaign.isEquipped(0x03) shouldBe false
    }

    "weaponSlot reads an empty slot as zero" {
        val ram = mapOf("char2_weapon1" to 0x84)
        Ff1Campaign.weaponSlot(ram, 2, 1) shouldBe 0x84
        Ff1Campaign.weaponSlot(ram, 2, 0) shouldBe 0
        Ff1Campaign.weaponSlot(emptyMap(), 1, 0) shouldBe 0
    }

    "minHpPct reports the worst-off member" {
        val ram = mapOf(
            "char1_hpLow" to 30, "char1_hpHigh" to 0, "char1_maxHpLow" to 30, "char1_maxHpHigh" to 0,
            "char2_hpLow" to 5, "char2_hpHigh" to 0, "char2_maxHpLow" to 20, "char2_maxHpHigh" to 0,
        )
        Ff1Campaign.minHpPct(ram) shouldBe 25
    }

    "minHpPct reads HP as 16-bit little-endian" {
        val ram = mapOf(
            "char1_hpLow" to 0x00, "char1_hpHigh" to 0x01,
            "char1_maxHpLow" to 0x00, "char1_maxHpHigh" to 0x02,
        )
        Ff1Campaign.minHpPct(ram) shouldBe 50
    }

    "no readable HP reports full health rather than a false alarm" {
        Ff1Campaign.minHpPct(emptyMap()) shouldBe 100
        // A max of zero is not a dead party, it is an unreadable one.
        Ff1Campaign.minHpPct(
            mapOf("char1_hpLow" to 0, "char1_hpHigh" to 0, "char1_maxHpLow" to 0, "char1_maxHpHigh" to 0)
        ) shouldBe 100
    }
})
