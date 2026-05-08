package knes.agent.skills

import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset

/**
 * Spec 5 POC: deterministic walk from Coneria town spawn to the weapon shop
 * shopkeeper, crossing the building boundary (mapId change).
 *
 * Hardcoded for Coneria FF1 NES layout — building 3 on Mike's RPG Center map
 * (middle row, west side of central plaza). Replaces the explorer's failed
 * attempts to find a shopkeeper directly in mapId=8 (town overlay): the
 * shopkeeper actually lives in a sub-shop mapId reached by entering the
 * building's south door.
 *
 * Sequence (post-spawn, party at smPlayer ≈ (12, 35)):
 *   1. Walk N until party crosses building boundary (mapId changes from 8).
 *      Coneria weapon shop entrance is roughly N+W of spawn; we issue a
 *      best-effort tap pattern and let RAM signal entry.
 *   2. After mapId change, walk N a few steps to face the shopkeeper sprite
 *      (FF1 shop layouts position the keeper north of the entry tile).
 *   3. Update the cached NPC_SHOPKEEPER landmark with the discovered mapId
 *      so BuyAtShop's `landmark.mapId == currentMapId` precondition holds.
 *
 * This skill is intentionally narrow: Coneria-only, weapon-shop-only. It is
 * a stepping stone toward a generic interior nav (Spec 6+).
 */
class EnterConeriaWeaponShop(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
) : Skill {
    override val id = "enter_coneria_weapon_shop"
    override val description = "POC: walk from Coneria spawn to weapon shopkeeper."

    /** Max steps in the outer-walk phase before declaring stuck. */
    private val maxOuterWalkSteps = 30

    /** Settle frames after each tap so RAM coords + mapId stabilize. */
    private val settleFrames = 6

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val pre = toolset.getState().ram
        val preMapId = pre["currentMapId"] ?: 0
        if (preMapId != 8) {
            return SkillResult(
                ok = false,
                message = "NotInTownOverlay: currentMapId=$preMapId (expected 8)",
                ramAfter = pre,
            )
        }

        // Phase 1: walk N+W to weapon shop door, taking RAM mapId change as signal.
        // Pattern derived from Mike's RPG Center map: weapon shop is north-northwest
        // of spawn. Try a generous tap sequence covering the typical path.
        val pattern = buildList {
            repeat(7) { add("Up") }
            repeat(3) { add("Left") }
            repeat(4) { add("Up") }
            repeat(2) { add("Left") }
            repeat(4) { add("Up") }
        }

        var entered = false
        var enteredMapId = preMapId
        var stepsBeforeEntry = 0
        for ((i, dir) in pattern.withIndex()) {
            if (i >= maxOuterWalkSteps) break
            toolset.tap(button = dir, count = 1, pressFrames = 6, gapFrames = 6)
            toolset.step(buttons = emptyList(), frames = settleFrames)
            val mid = toolset.getState().ram["currentMapId"] ?: 0
            if (mid != preMapId && mid != 0) {
                entered = true
                enteredMapId = mid
                stepsBeforeEntry = i + 1
                break
            }
        }

        if (!entered) {
            val ram = toolset.getState().ram
            return SkillResult(
                ok = false,
                message = "DidNotEnterBuilding: ${pattern.size} taps issued, mapId still ${ram["currentMapId"]}",
                ramAfter = ram,
            )
        }

        // Phase 2: in sub-shop, walk N a few steps to face the shopkeeper.
        // FF1 weapon shops typically position the keeper 4-6 tiles N of the door.
        repeat(6) {
            toolset.tap(button = "Up", count = 1, pressFrames = 6, gapFrames = 6)
            toolset.step(buttons = emptyList(), frames = settleFrames)
        }

        val post = toolset.getState().ram
        val postMapId = post["currentMapId"] ?: 0
        val postLocalX = post["smPlayerX"] ?: post["localX"] ?: 0
        val postLocalY = post["smPlayerY"] ?: post["localY"] ?: 0

        // Phase 3: refresh the cached weapon-shop landmark with the actual sub-shop
        // mapId so BuyAtShop's preflight (landmark.mapId == currentMapId) matches.
        val cached = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
            .firstOrNull { it.note.contains("kind=weapon") }
        if (cached != null && cached.mapId != postMapId) {
            val updated = cached.copy(
                mapId = postMapId,
                localX = postLocalX,
                localY = postLocalY,
                visited = true,
                note = cached.note + "; entered_via=EnterConeriaWeaponShop",
            )
            landmarks.recordIfNew(updated)
            landmarks.save()
        }

        return SkillResult(
            ok = true,
            message = "Entered: stepsToEntry=$stepsBeforeEntry subShopMapId=$postMapId localXY=($postLocalX,$postLocalY)",
            ramAfter = post,
        )
    }
}
