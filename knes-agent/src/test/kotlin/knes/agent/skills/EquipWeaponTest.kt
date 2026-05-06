package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession

class EquipWeaponTest : FunSpec({
    test("Equipped when high bit transitions on target slot") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0x10,  // owned, not equipped
        )
        val mid = pre  // menu nav; same RAM
        val post = pre.toMutableMap().apply {
            put("char1_weapon0", 0x90)  // equipped flag set
        }
        val toolset = ScriptedEquipToolset(listOf(pre) + List(20) { mid } + listOf(post, post, post, post, post))
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe true
        r.message shouldContain "Equipped"
    }

    test("AlreadyEquipped when high bit already set at entry") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0x90,
        )
        val toolset = ScriptedEquipToolset(listOf(pre))
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe true
        r.message shouldContain "AlreadyEquipped"
        toolset.tapsIssued shouldBe 0
    }

    test("WeaponNotInSlot when slot byte is 0") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0,
        )
        val toolset = ScriptedEquipToolset(listOf(pre))
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe false
        r.message shouldContain "WeaponNotInSlot"
        toolset.tapsIssued shouldBe 0
    }

    test("MenuStuck when equipped flag never sets after maxTaps") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0x10,
        )
        val toolset = ScriptedEquipToolset(List(80) { pre })
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe false
        r.message shouldContain "MenuStuck"
    }

    test("Equipped exercises charSlot=3 weaponSlot=2 cursor loops") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char3_weapon2" to 0x12,  // owned, not equipped
        )
        val post = pre.toMutableMap().apply {
            put("char3_weapon2", 0x92)  // equipped flag set
        }
        val toolset = ScriptedEquipToolset(listOf(pre) + List(20) { pre } + listOf(post, post, post, post, post))
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "3", "weaponSlot" to "2"))
        r.ok shouldBe true
        r.message shouldContain "Equipped"
        r.message shouldContain "char=3"
        r.message shouldContain "slot=2"
        // tapsIssued is non-zero (menu nav + watch-loop + B-mash recovery).
        // Sanity floor: 1 (B menu open) + 2 (down to EQUIP) + 1 (A) + 2 (down to char3) + 1 (A)
        // + 2 (down to weapon2) + 1 (A) = 10 nav taps before watch loop.
        (toolset.tapsIssued > 10) shouldBe true
    }
})

private class ScriptedEquipToolset(
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
