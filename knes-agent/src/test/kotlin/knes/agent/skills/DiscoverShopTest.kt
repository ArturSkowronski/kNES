package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import java.nio.file.Files

class DiscoverShopTest : FunSpec({

    test("classifies weapon shop and persists landmark with kind=weapon") {
        val ram = mapOf(
            "currentMapId" to 7, "smPlayerX" to 8, "smPlayerY" to 5,
            "screenState" to 0x00,
        )
        val toolset = ScriptedShopToolset(List(20) { ram })
        val tmp = Files.createTempFile("discover-shop-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)
        val vision = FakeHaikuConsult(
            shopClassifications = listOf(
                HaikuConsult.ShopClassification(
                    "weapon",
                    items = listOf("Rapier" to 10, "Hammer" to 10, "Knife" to 5, "Staff" to 5),
                    costUsd = 0.005,
                ),
            ),
        )
        val skill = DiscoverShop(toolset, landmarks, vision)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe true
        r.message shouldContain "Classified"
        r.message shouldContain "weapon"
        val saved = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
        saved shouldHaveSize 1
        saved[0].note shouldContain "kind=weapon"
        saved[0].mapId shouldBe 7
    }

    test("returns ClassifyFailed when vision returns unknown — no landmark write") {
        val ram = mapOf("currentMapId" to 7, "smPlayerX" to 8, "smPlayerY" to 5, "screenState" to 0)
        val toolset = ScriptedShopToolset(List(20) { ram })
        val tmp = Files.createTempFile("discover-shop-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)
        val vision = FakeHaikuConsult(
            shopClassifications = listOf(
                HaikuConsult.ShopClassification("unknown", emptyList(), 0.0),
            ),
        )
        val skill = DiscoverShop(toolset, landmarks, vision)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe false
        r.message shouldContain "ClassifyFailed"
        landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER) shouldHaveSize 0
    }

    test("returns NotInBuilding when currentMapId=0") {
        val ram = mapOf("currentMapId" to 0, "smPlayerX" to 0, "smPlayerY" to 0, "screenState" to 0)
        val toolset = ScriptedShopToolset(listOf(ram))
        val tmp = Files.createTempFile("discover-shop-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)
        val skill = DiscoverShop(toolset, landmarks, FakeHaikuConsult())

        val r = skill.invoke(emptyMap())
        r.ok shouldBe false
        r.message shouldContain "NotInBuilding"
        landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER) shouldHaveSize 0
    }
})

private class ScriptedShopToolset(
    private val ramSequence: List<Map<String, Int>>,
) : EmulatorToolset(EmulatorSession()) {
    private var idx = 0

    override fun getState(): StateSnapshot {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StateSnapshot(frame = idx, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    }

    override fun tap(
        button: String,
        count: Int,
        pressFrames: Int,
        gapFrames: Int,
        screenshot: Boolean,
    ): StepResult {
        idx = (idx + 1).coerceAtMost(ramSequence.size - 1)
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StepResult(
            frame = idx,
            ram = ram,
            heldButtons = emptyList(),
            screenshot = if (screenshot) "FAKE_BASE64_PNG" else null,
        )
    }
}
