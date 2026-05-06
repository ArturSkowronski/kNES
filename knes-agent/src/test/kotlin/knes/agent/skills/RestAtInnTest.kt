package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession

class RestAtInnTest : FunSpec({

    test("returns Rested when gold drops and HP returns to max after dialog taps") {
        val pre = mapOf(
            "currentMapId" to 8, "worldX" to 5, "worldY" to 3,
            "char1_hpLow" to 15, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,  // 400
            "screenState" to 0x00,
        )
        val post = pre.toMutableMap().apply {
            put("char1_hpLow", 20)
            put("goldLow", 0x72); put("goldMid", 0x01)  // 370
        }
        val ramSeq = listOf(pre, pre, post, post, post)
        val toolset = ScriptedRestToolset(ramSeq)
        val skill = RestAtInn(toolset)
        val r = skill.invoke(mapOf("innInteriorMapId" to "8"))
        r.ok shouldBe true
        r.message shouldContain "Rested"
    }

    test("returns InnNotFound when not inside the inn (currentMapId mismatch)") {
        val outside = mapOf(
            "currentMapId" to 0, "worldX" to 5, "worldY" to 3,
            "char1_hpLow" to 5, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "goldLow" to 5, "goldMid" to 0, "goldHigh" to 0,
            "screenState" to 0x00,
        )
        val toolset = ScriptedRestToolset(listOf(outside))
        val skill = RestAtInn(toolset)
        val r = skill.invoke(mapOf("innInteriorMapId" to "8"))
        r.ok shouldBe false
        r.message shouldContain "InnNotFound"
    }

    test("returns InnNotFound after 30 taps without HP/gold change") {
        val stuck = mapOf(
            "currentMapId" to 8, "worldX" to 5, "worldY" to 3,
            "char1_hpLow" to 15, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,
            "screenState" to 0x00,
        )
        val toolset = ScriptedRestToolset(List(40) { stuck })
        val skill = RestAtInn(toolset)
        val r = skill.invoke(mapOf("innInteriorMapId" to "8"))
        r.ok shouldBe false
        r.message shouldContain "InnNotFound"
    }

    test("returns Rested immediately when party is already at full HP inside inn") {
        val full = mapOf(
            "currentMapId" to 8, "worldX" to 5, "worldY" to 3,
            "char1_hpLow" to 20, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "char2_hpLow" to 20, "char2_hpHigh" to 0,
            "char2_maxHpLow" to 20, "char2_maxHpHigh" to 0,
            "char3_hpLow" to 20, "char3_hpHigh" to 0,
            "char3_maxHpLow" to 20, "char3_maxHpHigh" to 0,
            "char4_hpLow" to 20, "char4_hpHigh" to 0,
            "char4_maxHpLow" to 20, "char4_maxHpHigh" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,
            "screenState" to 0x00,
        )
        val toolset = ScriptedRestToolset(List(10) { full })
        val skill = RestAtInn(toolset)
        val r = skill.invoke(mapOf("innInteriorMapId" to "8"))
        r.ok shouldBe true
        r.message shouldContain "already at full HP"
    }
})

/**
 * Test double that replays a fixed sequence of RAM snapshots.
 * Each call to tap() advances the index, then getState() returns the current entry.
 *
 * StateSnapshot fields: frame, ram, cpu, heldButtons
 * StepResult fields:    frame, ram, heldButtons, screenshot
 */
private class ScriptedRestToolset(
    private val ramSequence: List<Map<String, Int>>
) : EmulatorToolset(EmulatorSession()) {
    private var idx = 0

    override fun tap(
        button: String,
        count: Int,
        pressFrames: Int,
        gapFrames: Int,
        screenshot: Boolean,
    ): StepResult {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        idx++
        return StepResult(frame = idx, ram = ram, heldButtons = emptyList(), screenshot = null)
    }

    override fun getState(): StateSnapshot {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StateSnapshot(frame = idx, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    }
}
