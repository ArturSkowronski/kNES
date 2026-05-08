package knes.agent.skills

import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import kotlin.math.abs
import kotlin.math.sign

/**
 * Spec 5 POC v2: deterministic walk from Coneria town spawn to weapon shop
 * shopkeeper.
 *
 * v1 (failed): hardcoded N+W tap sequence — party hit a wall at smPlayer(7,30)
 * without finding the weapon shop door.
 *
 * v2 strategy:
 *   1. Walk N until party Y stops decreasing (hit a building's south wall).
 *   2. Try one more N step in case the current X aligns with the door.
 *   3. Sweep horizontal positions (-3..+5 from origin) along the wall, trying
 *      a single N tap at each. Whichever X is the door tile triggers a mapId
 *      change.
 *   4. Once mapId changes, walk N a few steps to face the shopkeeper sprite
 *      inside the sub-shop.
 *   5. Persist the discovered sub-shop mapId on the cached weapon-shop
 *      landmark so BuyAtShop can match `landmark.mapId == currentMapId`.
 *
 * Tap timing: pressFrames=12, gapFrames=8 — empirically required for FF1 to
 * register a full 1-tile movement (v1 used 6/6 which was too short and yielded
 * partial moves).
 */
class EnterConeriaWeaponShop(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
) : Skill {
    override val id = "enter_coneria_weapon_shop"
    override val description = "POC: walk Coneria spawn → weapon shopkeeper, crossing mapId boundary."

    private val pressFrames = 12
    private val gapFrames = 8
    private val settleFrames = 8

    /** Run 16 confirmed mapId=24 reached via 16 N-taps was Coneria CASTLE entrance,
     *  not weapon shop. Castle gate sits at top center of plaza; shop doors are
     *  *between* spawn and castle. Walk only ~7 N steps to mid-plaza, then sweep
     *  wider X range for shop doors. */
    private val maxNorthTaps = 7

    /** Sweep offsets up to ±8 — Coneria buildings span the whole plaza width. */
    private val sweepOffsets = listOf(0, -1, +1, -2, +2, -3, +3, -4, +4, -5, +5, -6, +6, -7, +7, -8, +8)

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val pre = toolset.getState().ram
        val preMapId = pre["currentMapId"] ?: 0
        if (preMapId != 8) {
            return fail("NotInTownOverlay: currentMapId=$preMapId (expected 8)", pre)
        }

        // Phase 1: walk N until Y stops decreasing (= south wall of some building).
        var prevY = pre["smPlayerY"] ?: 0
        var sameYcount = 0
        var northSteps = 0
        for (i in 0 until maxNorthTaps) {
            tapMove("Up")
            northSteps++
            val ram = toolset.getState().ram
            val mid = ram["currentMapId"] ?: 0
            if (mid != preMapId && mid != 0) {
                return onEntered(mid, ram, "wall-hit-aligned", northSteps, sweepDone = 0)
            }
            val curY = ram["smPlayerY"] ?: 0
            if (curY == prevY) {
                sameYcount++
                if (sameYcount >= 2) break
            } else {
                sameYcount = 0
                prevY = curY
            }
        }

        // Phase 2: at wall. Try one more N (may be door at this X).
        tapMove("Up")
        var ram = toolset.getState().ram
        var mid = ram["currentMapId"] ?: 0
        if (mid != preMapId && mid != 0) {
            return onEntered(mid, ram, "first-try-N", northSteps + 1, sweepDone = 0)
        }

        // Phase 3: sweep horizontal positions along the wall, trying N at each.
        val originX = ram["smPlayerX"] ?: 0
        var lastSweepX = originX
        for (off in sweepOffsets.drop(1)) {
            val targetX = originX + off
            val deltaToTarget = targetX - lastSweepX
            val dir = if (deltaToTarget > 0) "Right" else "Left"
            repeat(abs(deltaToTarget)) {
                tapMove(dir)
                ram = toolset.getState().ram
                mid = ram["currentMapId"] ?: 0
                if (mid != preMapId && mid != 0) {
                    return onEntered(mid, ram, "sweep-while-walking-$dir", northSteps + 1,
                        sweepDone = sweepOffsets.indexOf(off))
                }
            }
            lastSweepX = targetX
            tapMove("Up")
            ram = toolset.getState().ram
            mid = ram["currentMapId"] ?: 0
            if (mid != preMapId && mid != 0) {
                return onEntered(mid, ram, "sweep-N-at-offset=$off",
                    northSteps + 1, sweepDone = sweepOffsets.indexOf(off))
            }
        }

        return fail(
            "DidNotEnterBuilding: walkedN=$northSteps swept ${sweepOffsets.size} offsets, " +
                "mapId still ${ram["currentMapId"]}, party=${ram["smPlayerX"]},${ram["smPlayerY"]}",
            ram,
        )
    }

    private suspend fun onEntered(
        newMapId: Int,
        entryRam: Map<String, Int>,
        via: String,
        northSteps: Int,
        sweepDone: Int,
    ): SkillResult {
        // Phase 4: walk N inside sub-shop until party Y stops decreasing
        // (= keeper sprite blocking forward movement). Run 12 showed 6 steps
        // wasn't enough — party landed at (12,14) far below keeper, and the
        // first BuyAtShop "Down" cursor tap walked the party back out the
        // south door, dropping mapId back to 8 mid-purchase loop.
        var prevY = entryRam["smPlayerY"] ?: 0
        var sameYcount = 0
        var subShopWalkSteps = 0
        for (i in 0 until 14) {
            tapMove("Up")
            subShopWalkSteps++
            val ram = toolset.getState().ram
            val curY = ram["smPlayerY"] ?: 0
            if (curY == prevY) {
                sameYcount++
                if (sameYcount >= 2) break
            } else {
                sameYcount = 0
                prevY = curY
            }
        }
        val post = toolset.getState().ram
        val postMapId = post["currentMapId"] ?: newMapId
        val postX = post["smPlayerX"] ?: 0
        val postY = post["smPlayerY"] ?: 0

        // Phase 5: refresh cached weapon-shop landmark with discovered mapId.
        val cached = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
            .firstOrNull { it.note.contains("kind=weapon") }
        if (cached != null) {
            val updated = cached.copy(
                mapId = postMapId,
                localX = postX,
                localY = postY,
                visited = true,
                note = cached.note + "; entered_via=$via",
            )
            landmarks.recordIfNew(updated)
            landmarks.save()
        }

        return SkillResult(
            ok = true,
            message = "Entered: via=$via northSteps=$northSteps sweepDone=$sweepDone " +
                "subShopWalkSteps=$subShopWalkSteps subShopMapId=$postMapId localXY=($postX,$postY)",
            ramAfter = post,
        )
    }

    private suspend fun tapMove(dir: String) {
        toolset.tap(button = dir, count = 1, pressFrames = pressFrames, gapFrames = gapFrames)
        toolset.step(buttons = emptyList(), frames = settleFrames)
    }

    private fun fail(message: String, ram: Map<String, Int>) =
        SkillResult(ok = false, message = message, ramAfter = ram)
}
