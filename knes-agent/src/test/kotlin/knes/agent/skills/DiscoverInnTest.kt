package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import java.nio.file.Files

class DiscoverInnTest : FunSpec({

    test("returns Rested AND persists NPC_INNKEEPER landmark when heal validates") {
        val pre = mapOf(
            "currentMapId" to 12, "worldX" to 7, "worldY" to 4,
            "char1_hpLow" to 10, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,  // 400
        )
        val post = pre.toMutableMap().apply {
            put("char1_hpLow", 20)
            put("goldLow", 0x72); put("goldMid", 0x01)  // 370 → cost 30
        }
        val toolset = ScriptedDiscoverToolset(listOf(pre, pre, post, post, post))
        val tmpFile = Files.createTempFile("discover-inn-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpFile)
        val skill = DiscoverInn(toolset, landmarks)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe true
        r.message shouldContain "Rested"
        landmarks.all().filter { it.kind == LandmarkKind.NPC_INNKEEPER } shouldHaveSize 1
        val saved = landmarks.findInnkeeper()!!
        saved.mapId shouldBe 12
        saved.localX shouldBe 7
        saved.localY shouldBe 4
        saved.note shouldContain "cost=30"
    }

    test("returns WrongBuilding after 30 taps without heal — does NOT persist") {
        val stuck = mapOf(
            "currentMapId" to 9, "worldX" to 5, "worldY" to 3,
            "char1_hpLow" to 15, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,
        )
        val toolset = ScriptedDiscoverToolset(List(40) { stuck })
        val tmpFile = Files.createTempFile("discover-inn-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpFile)
        val skill = DiscoverInn(toolset, landmarks)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe false
        r.message shouldContain "WrongBuilding"
        landmarks.all().filter { it.kind == LandmarkKind.NPC_INNKEEPER } shouldHaveSize 0
    }

    test("returns NotInBuilding when currentMapId is 0 (still on overworld)") {
        val outside = mapOf(
            "currentMapId" to 0, "worldX" to 152, "worldY" to 159,
            "char1_hpLow" to 5, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,
        )
        val toolset = ScriptedDiscoverToolset(listOf(outside))
        val tmpFile = Files.createTempFile("discover-inn-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpFile)
        val skill = DiscoverInn(toolset, landmarks)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe false
        r.message shouldContain "NotInBuilding"
        landmarks.all() shouldHaveSize 0
    }
})

private class ScriptedDiscoverToolset(
    private val ramSequence: List<Map<String, Int>>
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
        return StepResult(frame = idx, ram = ram, heldButtons = emptyList(), screenshot = null)
    }
}
