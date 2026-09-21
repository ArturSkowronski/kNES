package knes.debug

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ProfileSemanticsTest : FunSpec({

    test("FF1 profile ships semantics") {
        val ff1 = ProfileSemantics.get("ff1")!!
        ff1.version shouldBe 1
        ff1.phases shouldNotBe emptyList<PhaseRule>()
        ff1.landmarks shouldNotBe emptyList<LandmarkRule>()
    }

    test("profile id lookup is case insensitive") {
        ProfileSemantics.get("FF1") shouldBe ProfileSemantics.get("ff1")
    }

    test("unknown profile has no semantics") {
        ProfileSemantics.get("no-such-game") shouldBe null
    }

    test("FF1 phase rules classify the snapshots the agent sees") {
        val ff1 = ProfileSemantics.get("ff1")!!

        ff1.phaseFor(emptyMap()) shouldBe "Boot"
        ff1.phaseFor(mapOf("char1_hpLow" to 0, "worldX" to 0)) shouldBe "Boot"
        ff1.phaseFor(mapOf("char1_hpLow" to 35, "screenState" to 0x68)) shouldBe "Battle"
        ff1.phaseFor(mapOf("char1_hpLow" to 35, "menuState" to 3)) shouldBe "MenuStuck"
        ff1.phaseFor(mapOf("char1_hpLow" to 35, "currentMapId" to 0, "mapflags" to 0)) shouldBe "Overworld"
        ff1.phaseFor(mapOf("char1_hpLow" to 35, "currentMapId" to 0, "mapflags" to 1)) shouldBe "Town"
        ff1.phaseFor(mapOf("char1_hpLow" to 35, "currentMapId" to 62, "mapflags" to 1)) shouldBe "Indoors"
        ff1.phaseFor(mapOf("char1_hpLow" to 35)) shouldBe "Unknown"
    }

    test("Coneria landmark needs both the phase and the world anchor") {
        val ff1 = ProfileSemantics.get("ff1")!!
        val atConeria = mapOf("worldX" to 146, "worldY" to 158)

        ff1.landmarkFor("Town", atConeria)!!.id shouldBe "ff1.coneria"
        ff1.landmarkFor("Overworld", atConeria)!!.id shouldBe "ff1.coneria_region"
        ff1.landmarkFor("Indoors", atConeria)!!.id shouldBe "ff1.coneria_interior"
        ff1.landmarkFor("Boot", atConeria) shouldBe null
        ff1.landmarkFor("Town", mapOf("worldX" to 10, "worldY" to 10)) shouldBe null
        ff1.landmarkFor("Town", emptyMap()) shouldBe null
        ff1.landmarkFor("Town", atConeria)!!.confidence shouldBeGreaterThan 0.5
    }

    test("SMB semantics prove the rules are not FF1-only") {
        val smb = ProfileSemantics.get("smb")!!
        smb.phaseFor(mapOf("gameState" to 0)) shouldBe "Boot"
        smb.phaseFor(mapOf("gameState" to 1)) shouldBe "Overworld"
        smb.position.localX(mapOf("playerX" to 72)) shouldBe 72
        smb.landmarks shouldBe emptyList()
    }

    test("position mapping falls back through the field list in order") {
        val position = PositionMapping(localXFields = listOf("smPlayerX", "localX"))
        position.localX(mapOf("smPlayerX" to 11, "localX" to 4)) shouldBe 11
        position.localX(mapOf("localX" to 4)) shouldBe 4
        position.localX(emptyMap()) shouldBe null
    }

    test("a missing RAM field fails a condition unless a default is given") {
        RamCondition(field = "mapflags", bitClear = 1).matches(emptyMap()) shouldBe false
        RamCondition(field = "mapflags", bitClear = 1, default = 0).matches(emptyMap()) shouldBe true
    }

    test("all bounds in a condition must hold") {
        val condition = RamCondition(field = "hp", atLeast = 10, atMost = 20)
        condition.matches(mapOf("hp" to 15)) shouldBe true
        condition.matches(mapOf("hp" to 9)) shouldBe false
        condition.matches(mapOf("hp" to 21)) shouldBe false
    }

    test("a rule with no conditions never matches") {
        PhaseRule(phase = "Battle").matches(mapOf("anything" to 1)) shouldBe false
    }

    test("registered semantics override the bundled profile") {
        val original = ProfileSemantics.get("smb")
        try {
            ProfileSemantics.register(
                "smb",
                ProfileSemantics(phases = listOf(PhaseRule("Battle", listOf(RamCondition("lives", atLeast = 1)))))
            )
            ProfileSemantics.get("smb")!!.phaseFor(mapOf("lives" to 3)) shouldBe "Battle"
        } finally {
            ProfileSemantics.register("smb", original)
        }
    }
})
