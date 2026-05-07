package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import java.nio.file.Files

/**
 * White-box tests for OutfitBootPhase entry guards. Asserts the three skip-paths
 * without involving real shop discovery or skill invocations (those are tested
 * in their own skill-level tests + the e2e test).
 */
class OutfitBootPhaseTest : FunSpec({
    test("skip when OutfitState flag matches savestate hash") {
        val tmpOutfit = Files.createTempFile("boot-skip-flag-", ".json").toFile().apply { deleteOnExit() }
        val state = OutfitState(file = tmpOutfit)
        state.markBought("hash-A", listOf(1, 2, 3, 4), 32, listOf("weapon@7-(8,5)"))
        val ram = mapOf("currentMapId" to 0, "char1_weapon0" to 0)
        val toolset = ScriptedToolset(List(5) { ram })
        val tmpLand = Files.createTempFile("boot-skip-land-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpLand)
        val trace = mutableListOf<String>()

        val result = OutfitBootPhase(toolset, landmarks, OutfitState(file = tmpOutfit),
            savestateHash = "hash-A",
            trace = { kind, msg -> trace += "$kind:$msg" }).run()

        result.skipped shouldBe true
        result.reason shouldContain "already-bought"
        toolset.tapsIssued shouldBe 0
    }

    test("skip when live RAM shows all 4 chars equipped") {
        val tmpOutfit = Files.createTempFile("boot-ram-", ".json").toFile().apply { deleteOnExit() }
        val ram = mapOf(
            "currentMapId" to 0,
            "char1_weapon0" to 0x90, "char2_weapon0" to 0x91,
            "char3_weapon0" to 0x92, "char4_weapon0" to 0x93,
        )
        val toolset = ScriptedToolset(listOf(ram))
        val tmpLand = Files.createTempFile("boot-ram-land-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpLand)
        val trace = mutableListOf<String>()

        val result = OutfitBootPhase(toolset, landmarks, OutfitState(file = tmpOutfit),
            savestateHash = "hash-B",
            trace = { kind, msg -> trace += "$kind:$msg" }).run()

        result.skipped shouldBe true
        result.reason shouldContain "ram shows all 4"
        toolset.tapsIssued shouldBe 0
        // Marks the flag for next time
        OutfitState(file = tmpOutfit).weaponsBoughtFor("hash-B") shouldBe true
    }

    test("falls back gracefully when no TOWN_ENTRY landmark exists") {
        val tmpOutfit = Files.createTempFile("boot-no-town-", ".json").toFile().apply { deleteOnExit() }
        val ram = mapOf(
            "currentMapId" to 0,
            "char1_weapon0" to 0, "char2_weapon0" to 0,
            "char3_weapon0" to 0, "char4_weapon0" to 0,
        )
        val toolset = ScriptedToolset(listOf(ram))
        val tmpLand = Files.createTempFile("boot-no-town-land-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpLand)  // empty
        val trace = mutableListOf<String>()

        val result = OutfitBootPhase(toolset, landmarks, OutfitState(file = tmpOutfit),
            savestateHash = "hash-C",
            trace = { kind, msg -> trace += "$kind:$msg" }).run()

        result.skipped shouldBe false
        result.reason shouldContain "no_town_entry"
        toolset.tapsIssued shouldBe 0
        trace.any { it.contains("boot_outfit_summary") } shouldBe true
    }
})

private class ScriptedToolset(
    private val ramSequence: List<Map<String, Int>>
) : EmulatorToolset(EmulatorSession()) {
    private var idx = 0
    var tapsIssued: Int = 0; private set
    override fun getState(): StateSnapshot {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StateSnapshot(frame = idx, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    }
    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int, screenshot: Boolean): StepResult {
        tapsIssued++
        idx = (idx + 1).coerceAtMost(ramSequence.size - 1)
        return StepResult(frame = idx, ram = ramSequence.getOrElse(idx) { ramSequence.last() },
            heldButtons = emptyList(), screenshot = null)
    }
}
