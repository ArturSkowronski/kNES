package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession

class GrindLoopTest : FunSpec({

    test("returns EncounteredBattle when screenState transitions to 0x68 mid-walk") {
        val ramSeq = listOf(
            mapOf("worldX" to 157, "worldY" to 158, "screenState" to 0x00, "currentMapId" to 0),
            mapOf("worldX" to 157, "worldY" to 157, "screenState" to 0x00, "currentMapId" to 0),
            mapOf("worldX" to 157, "worldY" to 156, "screenState" to 0x68, "currentMapId" to 0),
        )
        val toolset = ScriptedToolset(ramSeq)
        val skill = GrindLoop(toolset)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe true
        r.message shouldContain "EncounteredBattle"
    }

    test("returns NoEncounter after maxStepsWithoutEncounter") {
        val noEncounterRam = mapOf("worldX" to 157, "worldY" to 158, "screenState" to 0x00, "currentMapId" to 0)
        val toolset = ScriptedToolset(List(20) { noEncounterRam })
        val skill = GrindLoop(toolset)

        val r = skill.invoke(mapOf("maxStepsWithoutEncounter" to "6"))
        r.ok shouldBe true
        r.message shouldContain "NoEncounter"
    }

    test("returns WanderedOff when worldX/Y drifts > 2x corridor radius") {
        val driftRam = mapOf("worldX" to 157, "worldY" to 200, "screenState" to 0x00, "currentMapId" to 0)
        val toolset = ScriptedToolset(List(10) { driftRam })
        val skill = GrindLoop(toolset)

        val r = skill.invoke(mapOf("anchorX" to "157", "anchorY" to "158", "corridorRadius" to "3"))
        r.ok shouldBe false
        r.message shouldContain "WanderedOff"
    }

    test("returns Blocked when currentMapId becomes nonzero (entered interior unexpectedly)") {
        val ramSeq = listOf(
            mapOf("worldX" to 157, "worldY" to 158, "screenState" to 0x00, "currentMapId" to 0),
            mapOf("worldX" to 157, "worldY" to 157, "screenState" to 0x00, "currentMapId" to 5),
        )
        val toolset = ScriptedToolset(ramSeq)
        val skill = GrindLoop(toolset)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe false
        r.message shouldContain "Blocked"
    }

    test("returns EncounteredBattle when screenState is 0x63 (PostBattle)") {
        val ramSeq = listOf(
            mapOf("worldX" to 157, "worldY" to 158, "screenState" to 0x00, "currentMapId" to 0),
            mapOf("worldX" to 157, "worldY" to 157, "screenState" to 0x63, "currentMapId" to 0),
        )
        val toolset = ScriptedToolset(ramSeq)
        val skill = GrindLoop(toolset)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe true
        r.message shouldContain "EncounteredBattle"
        r.message shouldContain "0x63"
    }
})

/**
 * Test double that replays a fixed sequence of RAM snapshots.
 * Each call to tap() advances the index, then getState() returns the current entry.
 *
 * StateSnapshot fields: frame, ram, cpu, heldButtons
 * StepResult fields:    frame, ram, heldButtons, screenshot
 */
private class ScriptedToolset(
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
