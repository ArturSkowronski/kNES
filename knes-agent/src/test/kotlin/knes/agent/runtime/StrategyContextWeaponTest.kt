package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StrategyContextWeaponTest : FunSpec({
    test("weaponSlot reads char N slot M from ram") {
        val ram = mapOf("char1_weapon0" to 0x10, "char1_weapon2" to 0x90, "char3_weapon1" to 0x05)
        StrategyContext.weaponSlot(ram, 1, 0) shouldBe 0x10
        StrategyContext.weaponSlot(ram, 1, 2) shouldBe 0x90
        StrategyContext.weaponSlot(ram, 3, 1) shouldBe 0x05
        StrategyContext.weaponSlot(ram, 1, 3) shouldBe 0  // missing key → 0
    }
    test("weaponId masks low 7 bits") {
        StrategyContext.weaponId(0x00) shouldBe 0
        StrategyContext.weaponId(0x10) shouldBe 0x10
        StrategyContext.weaponId(0x80) shouldBe 0
        StrategyContext.weaponId(0x90) shouldBe 0x10
        StrategyContext.weaponId(0xFF) shouldBe 0x7F
    }
    test("isEquipped checks high bit") {
        StrategyContext.isEquipped(0x00) shouldBe false
        StrategyContext.isEquipped(0x10) shouldBe false
        StrategyContext.isEquipped(0x80) shouldBe true
        StrategyContext.isEquipped(0x90) shouldBe true
    }
    test("anyWeaponEquipped scans all 4 slots for char") {
        val ramArmed = mapOf("char1_weapon0" to 0x10, "char1_weapon1" to 0, "char1_weapon2" to 0x90, "char1_weapon3" to 0)
        StrategyContext.anyWeaponEquipped(ramArmed, 1) shouldBe true
        val ramUnarmed = mapOf("char2_weapon0" to 0x10, "char2_weapon1" to 0, "char2_weapon2" to 0x05, "char2_weapon3" to 0)
        StrategyContext.anyWeaponEquipped(ramUnarmed, 2) shouldBe false
        val ramEmpty = mapOf<String, Int>()
        StrategyContext.anyWeaponEquipped(ramEmpty, 1) shouldBe false
    }
})
