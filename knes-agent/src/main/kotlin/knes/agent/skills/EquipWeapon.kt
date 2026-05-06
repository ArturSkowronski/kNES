package knes.agent.skills

import knes.agent.runtime.StrategyContext
import knes.agent.tools.EmulatorToolset

/**
 * Open FF1 field menu, navigate EQUIP -> char -> weapon slot, toggle equipped flag.
 * Idempotent: returns AlreadyEquipped if high bit already set on entry.
 *
 * Args:
 *   charSlot   - 1..4
 *   weaponSlot - 0..3 (which inventory slot to toggle)
 *
 * Outcomes: Equipped, AlreadyEquipped, MenuStuck, WeaponNotInSlot.
 *
 * Field menu opens via B button on FF1 NES overworld (canonical binding).
 * EQUIP is the third menu item; WEAPON is the default sub-tab inside.
 *
 * Pre-condition: party on overworld (currentMapId == 0). Caller is responsible
 * for ensuring overworld state before invoking. Skill exits with B-mash to leave
 * the field menu cleanly on both success and timeout paths.
 */
class EquipWeapon(private val toolset: EmulatorToolset) : Skill {
    override val id = "equip_weapon"
    override val description = "Equip a specific weapon slot for a specific char via field menu."

    // FF1 field-menu nav latency: ~5-10 frames per cursor move + dialog frames.
    // 60 = generous cap covering pessimistic menu render lag + B-mash recovery.
    private val maxTaps = 60
    // After equip-flag detected (success) or not detected (timeout), B-mash to
    // unwind from EQUIP submenu -> field menu -> overworld. Used on both paths.
    private val recoveryBTaps = 5

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val charSlot = args["charSlot"]?.toIntOrNull()
            ?: return SkillResult(false, "Bad args: charSlot missing/invalid", ramAfter = emptyMap())
        val weaponSlot = args["weaponSlot"]?.toIntOrNull()
            ?: return SkillResult(false, "Bad args: weaponSlot missing/invalid", ramAfter = emptyMap())

        val pre = toolset.getState().ram
        val preByte = StrategyContext.weaponSlot(pre, charSlot, weaponSlot)
        if (StrategyContext.weaponId(preByte) == 0) {
            return SkillResult(false,
                "WeaponNotInSlot: char=$charSlot slot=$weaponSlot byte=0",
                ramAfter = pre)
        }
        if (StrategyContext.isEquipped(preByte)) {
            return SkillResult(true,
                "AlreadyEquipped: char=$charSlot slot=$weaponSlot byte=$preByte",
                ramAfter = pre)
        }

        // Open field menu (B), nav to EQUIP (index 2), select char, select weapon slot.
        toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 30)
        // Cursor down to EQUIP (2 down moves from default cursor position 0).
        repeat(2) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // Inside EQUIP: cursor down (charSlot - 1) to reach target char.
        repeat(charSlot - 1) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // WEAPON sub-tab is default; cursor down to target weapon slot.
        repeat(weaponSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)

        // Watch RAM for high-bit set (equipped flag).
        var taps = 0
        while (taps < maxTaps) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
            taps++
            val ram = toolset.getState().ram
            val curByte = StrategyContext.weaponSlot(ram, charSlot, weaponSlot)
            if (StrategyContext.isEquipped(curByte)) {
                // Close menu: B-mash back to overworld.
                repeat(recoveryBTaps) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 20) }
                return SkillResult(true,
                    "Equipped: char=$charSlot slot=$weaponSlot byte ${preByte}->$curByte after $taps taps",
                    ramAfter = ram)
            }
        }
        // Recovery: B-mash regardless of menu state.
        repeat(recoveryBTaps) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 20) }
        return SkillResult(false,
            "MenuStuck: $maxTaps taps without equipped-flag transition for char=$charSlot slot=$weaponSlot",
            ramAfter = toolset.getState().ram)
    }
}
