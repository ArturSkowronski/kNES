package knes.agent.v2.tools

import knes.agent.skills.EquipWeapon
import knes.agent.skills.ExitInterior
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.skills.RestAtInn
import knes.agent.skills.SkillResult
import knes.agent.skills.WalkOverworldTo
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.runtime.Phase

sealed class ToolOutcome {
    data class Ok(val message: String = "", val data: Map<String, String> = emptyMap()) : ToolOutcome()
    data class Fail(val message: String) : ToolOutcome()
    data class Reject(val reason: String) : ToolOutcome()
}

interface ToolSurface {
    suspend fun boot(): ToolOutcome
    suspend fun walkTo(x: Int, y: Int): ToolOutcome
    suspend fun interactAt(x: Int, y: Int): ToolOutcome
    suspend fun useMenu(path: String): ToolOutcome
    suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome
    suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome
    suspend fun restAtInn(innMapId: String): ToolOutcome
    suspend fun battleFightAll(): ToolOutcome
    suspend fun approachSprite(kind: String): ToolOutcome
    suspend fun sequence(buttons: List<String>): ToolOutcome
}

class DefaultToolSurface(
    private val toolset: EmulatorToolset,
    private val phaseProvider: () -> Phase,
    private val pressStartUntilOverworld: PressStartUntilOverworld,
    private val walkOverworld: WalkOverworldTo,
    private val exitInterior: ExitInterior,
    private val equipWeaponSkill: EquipWeapon,
    private val restAtInnSkill: RestAtInn,
    private val haiku: HaikuClient? = null,
    private val menuWalker: MenuWalker = MenuWalker(),
    private val livePngFile: java.nio.file.Path? = null,
) : ToolSurface {

    override suspend fun boot(): ToolOutcome = wrap(pressStartUntilOverworld.invoke(emptyMap()))

    override suspend fun walkTo(x: Int, y: Int): ToolOutcome = when (phaseProvider()) {
        Phase.Overworld -> wrap(walkOverworld.invoke(mapOf("targetX" to "$x", "targetY" to "$y", "maxSteps" to "32")))
            .also { if (it is ToolOutcome.Ok) settleMapflagsTransient() }
        Phase.Indoors   -> wrap(exitInterior.invoke(mapOf("maxSteps" to "64")))
            .also { if (it is ToolOutcome.Ok) settleMapflagsTransient() }
        // Town overlay (mapId=0, mapflags.bit0=1): in-town movement via
        // Haiku-vision per step + RAM verification. Caller passes target
        // *local* coords (smPlayerX/smPlayerY space), not overworld.
        // Coord-space sanity belongs in the Advisor prompt (state digest),
        // not here — runtime gate on coord magnitude blocked legitimate
        // walks (Smoke 2026-05-12 run #2 evidence) and a Reject without
        // movement is worse than a Fail mid-walk with a real diagnostic.
        Phase.Town      -> townWalkVision(x, y, maxSteps = 30)
        else -> ToolOutcome.Reject("walkTo not applicable in phase ${phaseProvider()}")
    }

    override suspend fun interactAt(x: Int, y: Int): ToolOutcome {
        val walk = walkTo(x, y)
        if (walk !is ToolOutcome.Ok) return walk
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
        return ToolOutcome.Ok("interactAt done")
    }

    override suspend fun useMenu(path: String): ToolOutcome {
        val taps = try { menuWalker.parse(path) }
                   catch (e: IllegalArgumentException) { return ToolOutcome.Reject(e.message ?: "bad path") }
        // RAM-diff gate: capture menu/screen state before and after taps. If
        // nothing changes, the path didn't match what was actually on screen
        // (e.g. shop/exit when no shop dialog open) — return Reject so the
        // watchdog can tick. Without this, MenuWalker happily emits button
        // taps for any well-formed path; Smoke 1 v3 evidence: 266/300 turns
        // looping useMenu(shop/exit) with screenState=menuCursor=menuHandX
        // =menuHandY=0 throughout, every call returning ok.
        val pre = snapshotMenuRam()
        for (t in taps) toolset.tap(button = t.button, count = t.count, pressFrames = 5, gapFrames = 12)
        val post = snapshotMenuRam()
        if (pre == post) {
            return ToolOutcome.Reject("useMenu had no effect — menu/screen state unchanged (pre=post=$pre); path '$path' likely doesn't match current screen")
        }
        return ToolOutcome.Ok("menu walked: $path")
    }

    /**
     * Wait for the mapflags bit1 transition transient to clear after a successful
     * walk. Pattern from ExitTownEmpirical.kt:104: mapflags bit1 = "dialog/menu
     * active in transition"; resolves within ~300 frames once the engine commits
     * to the new map/overlay. Without this, Smoke 1 v3/v4/v5 fired buyAtShop
     * immediately after walkTo while mapflags=2 (bit0=0, bit1=1) and BuyAtShop's
     * standard-map check failed with "NotInShop: mapflags.bit0=0, mapId=0".
     *
     * Polls in 60-frame chunks up to ~5 seconds wallclock. Idempotent if bit1
     * is already clear.
     */
    private suspend fun settleMapflagsTransient() {
        repeat(5) {
            val mf = toolset.getState().ram["mapflags"] ?: 0
            if ((mf and 0x02) == 0) return
            toolset.step(buttons = emptyList(), frames = 60)
        }
    }

    /**
     * Vision-driven in-town walk. Target (tx, ty) is interpreted as
     * smPlayerX/smPlayerY (town-local) coordinates.
     *
     * Resilience features (added 2026-05-12 to stabilise in-town walking):
     *  - **avoidCardinals**: per-invocation memory of (sm, cardinal) pairs that
     *    triggered a map transition. tryCardinals skips known-bad combos so we
     *    don't re-step on the same castle door tile.
     *  - **walk-back-in**: when a transition lands the party in Overworld (south
     *    town exit), tap opposite of last cardinal to reenter. Recovers without
     *    bouncing the whole call back to the Advisor.
     *  - **abort to Reject only on Indoors transitions** (castle/shop interior) —
     *    those need outside coordination to escape, not a same-loop recovery.
     */
    private suspend fun townWalkVision(tx: Int, ty: Int, maxSteps: Int): ToolOutcome {
        val haiku = this.haiku ?: return ToolOutcome.Reject(
            "townWalkVision: Haiku not wired (DefaultToolSurface haiku=null)"
        )
        val startMapId = toolset.getState().ram["currentMapId"] ?: 0
        val startMapflagsBit0 = (toolset.getState().ram["mapflags"] ?: 0) and 0x01
        val avoidCardinals = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()
        var steps = 0
        var consecutiveStuck = 0
        var lastCardinal: String? = null
        var lastSm: Pair<Int, Int>? = null
        var transitionsRecovered = 0
        val stuckLimit = 8
        while (steps < maxSteps) {
            val r = toolset.getState().ram
            val curMapId = r["currentMapId"] ?: 0
            val curMapflagsBit0 = (r["mapflags"] ?: 0) and 0x01
            if (curMapId != startMapId || curMapflagsBit0 != startMapflagsBit0) {
                // Transition mid-loop. Two cases:
                //   (a) Indoors (mapId>0) — castle / shop / inn interior. Cannot
                //       same-loop recover; the door is one-way and the party
                //       needs exitInterior. Record the fault and Reject.
                //   (b) Overworld (mapId=0, mapflags.bit0=0) — we stepped south
                //       (or any direction) out of the town overlay. Tap opposite
                //       of last cardinal to walk back in; settle; resume loop.
                val lc = lastCardinal
                val ls = lastSm
                if (lc != null && ls != null) {
                    avoidCardinals.getOrPut(ls) { mutableSetOf() }.add(lc)
                }
                if (curMapId > 0) {
                    return ToolOutcome.Reject(
                        "townWalk: stepped into interior mapId=$curMapId from sm=$ls via $lc " +
                        "(avoid recorded); call walkTo from Indoors to exit, then reapproach. " +
                        "transitionsRecovered=$transitionsRecovered"
                    )
                }
                if (lc != null) {
                    val back = oppositeOf(lc)
                    toolset.tap(button = back, count = 1, pressFrames = 12, gapFrames = 8)
                    toolset.step(buttons = emptyList(), frames = 60)
                    val r2 = toolset.getState().ram
                    val mfBit0Now = (r2["mapflags"] ?: 0) and 0x01
                    val mIdNow = r2["currentMapId"] ?: 0
                    if (mIdNow == startMapId && mfBit0Now == startMapflagsBit0) {
                        transitionsRecovered++
                        consecutiveStuck = 0
                        steps += 1
                        // After a recovery, clear lastCardinal/lastSm so we don't
                        // double-record the walk-back step as a fault.
                        lastCardinal = null
                        lastSm = null
                        continue
                    }
                }
                return ToolOutcome.Reject(
                    "townWalk: left town overlay mid-loop and walk-back failed " +
                    "(now mid=$curMapId/mfBit0=$curMapflagsBit0). transitionsRecovered=$transitionsRecovered"
                )
            }
            val sx = r["smPlayerX"] ?: 0
            val sy = r["smPlayerY"] ?: 0
            val dx = tx - sx
            val dy = ty - sy
            if (sx == tx && sy == ty)
                return ToolOutcome.Ok("townWalk: reached ($tx,$ty) in $steps steps (recoveries=$transitionsRecovered)")
            if (kotlin.math.abs(dx) + kotlin.math.abs(dy) == 1) {
                // ONE tile away. Try to step exactly onto target. This matters
                // for door tiles (shop/inn/castle entries) where adjacent is
                // not enough — engine fires the mapId transition only when the
                // party stands ON the door. For NPC targets (blocked), the
                // tap won't move and we accept adjacent as success.
                val finalDir = when {
                    dx ==  1 -> "Right"
                    dx == -1 -> "Left"
                    dy ==  1 -> "Down"
                    else     -> "Up"
                }
                toolset.tap(button = finalDir, count = 1, pressFrames = 12, gapFrames = 8)
                toolset.step(buttons = emptyList(), frames = 12)
                val r3 = toolset.getState().ram
                val nx = r3["smPlayerX"] ?: sx
                val ny = r3["smPlayerY"] ?: sy
                val mfNow = r3["mapflags"] ?: 0
                val midNow = r3["currentMapId"] ?: 0
                if (nx == tx && ny == ty) {
                    return ToolOutcome.Ok("townWalk: reached EXACT ($tx,$ty) in ${steps + 1} steps (recoveries=$transitionsRecovered)")
                }
                if (midNow != startMapId || (mfNow and 0x01) != startMapflagsBit0) {
                    // Step triggered a transition — likely entered a building.
                    // Don't try to walk-back; caller wants this (shop entry).
                    return ToolOutcome.Ok(
                        "townWalk: stepped into transition at ($tx,$ty) — now mid=$midNow/mf=$mfNow " +
                        "(recoveries=$transitionsRecovered)"
                    )
                }
                return ToolOutcome.Ok("townWalk: adjacent to ($tx,$ty) at sm=($nx,$ny) — blocked from exact step (recoveries=$transitionsRecovered)")
            }

            val b64 = toolset.getScreen().base64
            val dir = try {
                haiku.directionTo(b64, "town tile local($tx,$ty) — distance dx=$dx dy=$dy from party at local($sx,$sy)")
            } catch (e: Exception) {
                return ToolOutcome.Fail("townWalk: Haiku error after $steps steps: ${e.message?.take(80)}")
            }
            val primary = mapCardinal(dir) ?: dominantAxisCardinal(dx, dy)
                ?: return ToolOutcome.Fail("townWalk: no direction (Haiku=$dir, dx=$dx dy=$dy) after $steps steps")

            val forbidden = avoidCardinals[sx to sy] ?: emptySet()
            val tried = tryCardinals(primary, dx, dy, sx, sy, forbidden)
            steps += tried.tapsUsed
            // Track last attempt for transition-recovery bookkeeping.
            lastSm = sx to sy
            lastCardinal = tried.tried.lastOrNull()
            val r2 = toolset.getState().ram
            val sx2 = r2["smPlayerX"] ?: 0
            val sy2 = r2["smPlayerY"] ?: 0
            val moved = (sx2 != sx || sy2 != sy)
            if (!moved) {
                consecutiveStuck++
                if (consecutiveStuck >= stuckLimit)
                    return ToolOutcome.Fail("townWalk: stuck at sm=($sx,$sy) for $stuckLimit rounds toward ($tx,$ty); tried=${tried.tried.joinToString(",")} avoid=${forbidden}")
            } else {
                consecutiveStuck = 0
            }
        }
        val r = toolset.getState().ram
        return ToolOutcome.Fail("townWalk: maxSteps=$maxSteps reached, sm=(${r["smPlayerX"]},${r["smPlayerY"]}) target=($tx,$ty) recoveries=$transitionsRecovered")
    }

    private data class CardinalTryResult(val tried: List<String>, val tapsUsed: Int)

    /**
     * Try primary, then perpendiculars, then opposite — first one that moves
     * the party wins. Skip any cardinal in [forbidden] (per-invocation memory
     * of door-tile faults from the caller's avoidCardinals map).
     */
    private suspend fun tryCardinals(
        primary: String, dx: Int, dy: Int, sx: Int, sy: Int,
        forbidden: Set<String> = emptySet(),
    ): CardinalTryResult {
        val perps = perpendicularsOf(primary, dx, dy)
        val opp = oppositeOf(primary)
        val order = listOfNotNull(primary, perps.first, perps.second, opp)
            .distinct()
            .filter { it !in forbidden }
        val tried = mutableListOf<String>()
        var tapsUsed = 0
        for (btn in order) {
            toolset.tap(button = btn, count = 1, pressFrames = 5, gapFrames = 12)
            tapsUsed++
            tried += btn
            val r = toolset.getState().ram
            val nx = r["smPlayerX"] ?: 0
            val ny = r["smPlayerY"] ?: 0
            if (nx != sx || ny != sy) break
            // Early-out on transition mid-tap: if mapflags/mapId changed, stop
            // exploring more cardinals — the outer loop will record + recover.
            val mfNow = (r["mapflags"] ?: 0) and 0x01
            val midNow = r["currentMapId"] ?: 0
            if (mfNow == 0 || midNow > 0) break
        }
        return CardinalTryResult(tried, tapsUsed)
    }

    private fun dominantAxisCardinal(dx: Int, dy: Int): String? = when {
        kotlin.math.abs(dx) >= kotlin.math.abs(dy) && dx != 0 -> if (dx > 0) "Right" else "Left"
        dy != 0 -> if (dy > 0) "Down" else "Up"
        else -> null
    }

    private fun perpendicularsOf(primary: String, dx: Int, dy: Int): Pair<String, String> {
        // For a vertical primary (Up/Down), perpendiculars are Left/Right — biased
        // toward dx sign. Vice-versa for horizontal.
        return when (primary) {
            "Up", "Down" -> if (dx >= 0) "Right" to "Left" else "Left" to "Right"
            "Left", "Right" -> if (dy >= 0) "Down" to "Up" else "Up" to "Down"
            else -> "Up" to "Down"
        }
    }

    private fun oppositeOf(primary: String): String = when (primary) {
        "Up" -> "Down"; "Down" -> "Up"; "Left" -> "Right"; "Right" -> "Left"; else -> "Down"
    }

    private fun mapCardinal(dir: String): String? = when (dir.trim().uppercase().take(4)) {
        "N", "UP" -> "Up"
        "S", "DOWN" -> "Down"
        "E", "RIGHT" -> "Right"
        "W", "LEFT" -> "Left"
        else -> null
    }

    private suspend fun snapshotMenuRam(): List<Int> {
        val r = toolset.getState().ram
        return listOf(
            r["screenState"] ?: 0,
            r["menuCursor"] ?: 0,
            r["menuHandX"] ?: 0,
            r["menuHandY"] ?: 0,
        )
    }

    /**
     * Per-pair single-purchase state machine. Bypasses v1 BuyAtShop entirely
     * because that skill's intra-call cursor state caused multi-pair runs to
     * stuff all items onto Fighter (run 2026-05-13 evidence: items=[4,3,2,3]
     * for chars [0,1,2,3] ended with Fighter holding 4 weapons, others empty).
     *
     * This version forces:
     *   - dialog fully closed before each pair (B×6 + idle)
     *   - re-engage keeper from scratch (A on adjacent tile)
     *   - cursor navigation always from KNOWN-TOP (B-tap moves cursor up to
     *     top of current list in FF1 NES shop menus)
     *
     * Returns Bought / WrongClass / NoEngage / DismissCapExhausted.
     */
    private suspend fun buyOnePair(itemSlot: Int, forCharSlot: Int): Pair<Boolean, String> {
        val pre = toolset.getState().ram
        val preGold = knes.agent.runtime.StrategyContext.totalGold(pre)
        val preInvBytes = (0..3).map { knes.agent.runtime.StrategyContext.weaponSlot(pre, forCharSlot + 1, it) }
        // 1. Fully close any open dialog (B×6) + idle so we start "facing keeper, no menu".
        repeat(6) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 10) }
        toolset.step(buttons = emptyList(), frames = 24)
        // 2. Engage keeper: A to open BUY/SELL/EXIT menu.
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 24)
        // 3. Select BUY (top option — Up×2 from anywhere guarantees BUY row).
        repeat(2) { toolset.tap(button = "Up", count = 1, pressFrames = 3, gapFrames = 8) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // 4. Item list: scroll cursor to top (Up×8 is overkill, covers any shop size),
        //    then Down itemSlot times → A.
        repeat(8) { toolset.tap(button = "Up", count = 1, pressFrames = 3, gapFrames = 6) }
        repeat(itemSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 8) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // 5. FOR-WHOM: Up×4 to top, then Down forCharSlot times → A.
        repeat(4) { toolset.tap(button = "Up", count = 1, pressFrames = 3, gapFrames = 6) }
        repeat(forCharSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 8) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // 6. Confirm YES (default = YES highlighted).
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // 7. Dismiss A-tap loop watching for gold drop + inventory delta OR
        //    unchanged-frames (WrongClass).
        val maxDismiss = 30
        var dismiss = 0
        var unchanged = 0
        while (dismiss < maxDismiss) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
            dismiss++
            val ram = toolset.getState().ram
            val curGold = knes.agent.runtime.StrategyContext.totalGold(ram)
            val curInvBytes = (0..3).map { knes.agent.runtime.StrategyContext.weaponSlot(ram, forCharSlot + 1, it) }
            val invDelta = curInvBytes.zip(preInvBytes).any { (cur, pre) -> cur != pre && cur != 0 }
            if (curGold < preGold && invDelta) {
                repeat(6) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 10) }
                toolset.step(buttons = emptyList(), frames = 24)
                return true to "Bought: cost=${preGold - curGold} char=$forCharSlot itemSlot=$itemSlot"
            }
            if (curGold == preGold && curInvBytes == preInvBytes) {
                unchanged++
                if (unchanged >= 5) {
                    repeat(6) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 10) }
                    toolset.step(buttons = emptyList(), frames = 24)
                    return false to "WrongClass: char=$forCharSlot itemSlot=$itemSlot — no gold/inv change after $unchanged taps"
                }
            } else {
                unchanged = 0
            }
        }
        repeat(6) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 10) }
        return false to "DismissCapExhausted: char=$forCharSlot itemSlot=$itemSlot — $maxDismiss taps no confirmed gold drop"
    }

    override suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome {
        require(items.size == charSlots.size) { "items.size must equal charSlots.size" }
        // Phase pre-flight. BuyAtShop.invoke assumes party is facing the keeper
        // inside a town overlay; firing it from Overworld returns
        // "NotInShop: mapflags.bit0=0, mapId=0" (Smoke 1 v12 evidence: 17 such
        // calls in one run from Sonnet+haiku planning buy without walking in
        // first). Reject early with a clear hint so the next plan picks walkTo
        // to the shopkeeper's local coords before re-trying buyAtShop.
        val phase = phaseProvider()
        if (phase != Phase.Town) {
            return ToolOutcome.Reject(
                "buyAtShop only valid in Phase.Town (current: $phase). " +
                "Walk into town first via walkTo(<town-entry world coords>), then walkTo(<keeper local coords>), then buyAtShop."
            )
        }
        // v2 native implementation — buyOnePair runs its own state machine per
        // (item, char) tuple with full B-tap dialog reset and explicit Up-to-top
        // cursor re-zeroing. Replaces v1 BuyAtShop skill whose intra-call cursor
        // state stuffed all items onto Fighter when chained (run 2026-05-13).
        val results = mutableListOf<String>()
        var anyOk = false
        for ((i, c) in items.zip(charSlots)) {
            val (ok, msg) = buyOnePair(itemSlot = i, forCharSlot = c)
            results += if (ok) "OK i=$i c=$c" else "FAIL i=$i c=$c ($msg)"
            if (ok) anyOk = true
        }
        val joined = results.joinToString("; ")
        return if (anyOk) ToolOutcome.Ok(joined) else ToolOutcome.Fail("buyAtShop: no purchases succeeded — $joined")
    }

    override suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome {
        // v1 EquipWeapon skill is 1-indexed (charSlot 1..4); Sonnet/Gemini emit
        // 0-indexed (0..3). Convert. Mirror of buyAtShop off-by-one fix.
        val r = equipWeaponSkill.invoke(mapOf("charSlot" to "${charSlot + 1}", "weaponSlot" to "$weaponSlot"))
        return if (r.ok) ToolOutcome.Ok(r.message) else ToolOutcome.Fail(r.message)
    }

    override suspend fun restAtInn(innMapId: String): ToolOutcome {
        val r = restAtInnSkill.invoke(mapOf("innInteriorMapId" to innMapId))
        return if (r.ok) ToolOutcome.Ok(r.message) else ToolOutcome.Fail(r.message)
    }

    override suspend fun battleFightAll(): ToolOutcome {
        // Gate on Phase.Battle. The ff1.battle_fight_all profile action
        // returns ok=true with "Battle complete in 0 rounds" when no battle
        // is in progress (it just confirms the post-battle screen check
        // passed trivially). Smoke 1 evidence: Sonnet picked this 1484×
        // in a row in Town because it was the only tool returning ok, and
        // the watchdog never tripped (skillProgress=true reset the counter).
        // Rejecting here forces real failure to surface so the Advisor can
        // replan toward the actual buy/walk flow.
        val phase = phaseProvider()
        if (phase != Phase.Battle) {
            return ToolOutcome.Reject("battleFightAll only valid in Phase.Battle (current: $phase)")
        }
        val r = toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")
        return if (r.ok) ToolOutcome.Ok(r.message, r.data) else ToolOutcome.Fail(r.message)
    }

    /**
     * Final-approach helper: scan viewport for a sprite of [kind], then walk
     * party to (sprite.vpX, sprite.vpY+1) and face N — leaving party
     * adjacent-S of the keeper, the position [BuyAtShop] / interactAt expects.
     *
     * Mirrors v1 `AgentSession.runOutfitBootPhase:1162-1244`. The gap this
     * fills: townWalkVision gets party near the keeper (within viewport) but
     * Haiku's per-step cardinal picker can't reliably land the last 1-2 tiles
     * around NPC drift. Reading the keeper's *viewport* position once and
     * issuing direct dx/dy taps is the v1 trick.
     *
     * Reject conditions: not Town phase (Reject); Haiku missing; no matching
     * sprite in scan; sprite already at party tile (delta zero — Ok but no
     * facing adjustment beyond the final N-tap).
     */
    override suspend fun approachSprite(kind: String): ToolOutcome {
        val phase = phaseProvider()
        if (phase != Phase.Town) {
            return ToolOutcome.Reject(
                "approachSprite only valid in Phase.Town (current: $phase). " +
                "Walk into town first via walkTo(<town-entry world coords>)."
            )
        }
        val haiku = this.haiku ?: return ToolOutcome.Reject(
            "approachSprite: Haiku not wired (DefaultToolSurface haiku=null)"
        )
        val b64 = toolset.getScreen().base64
        val candidates = try {
            haiku.scanCandidates(b64)
        } catch (e: Exception) {
            return ToolOutcome.Fail("approachSprite: Haiku error: ${e.message?.take(120)}")
        }
        val wanted = kind.trim().lowercase()
        val target = candidates.firstOrNull { it.kind == wanted }
            ?: return ToolOutcome.Fail(
                "approachSprite: no sprite of kind='$wanted' visible " +
                "(saw: [${candidates.joinToString(",") { it.kind }}])"
            )

        // Party renders at viewport tile (8, 7). Walk so party stands at
        // (sx, sy+1) facing N — directly south of keeper.
        val dx = target.vpX - 8
        val dyTarget = (target.vpY + 1) - 7
        val xDir = if (dx < 0) "Left" else "Right"
        val yDir = if (dyTarget < 0) "Up" else "Down"
        repeat(kotlin.math.abs(dx)) {
            toolset.tap(button = xDir, count = 1, pressFrames = 12, gapFrames = 8)
            toolset.step(buttons = emptyList(), frames = 8)
        }
        repeat(kotlin.math.abs(dyTarget)) {
            toolset.tap(button = yDir, count = 1, pressFrames = 12, gapFrames = 8)
            toolset.step(buttons = emptyList(), frames = 8)
        }
        // Final N-tap so facing stays N (in case last move was horizontal
        // or a Down for descending toward keeper). Mirrors v1 behaviour
        // (FF1 NES facing is set by last directional input).
        toolset.tap(button = "Up", count = 1, pressFrames = 12, gapFrames = 8)
        toolset.step(buttons = emptyList(), frames = 8)
        return ToolOutcome.Ok(
            "approachSprite: positioned south of $wanted vp(${target.vpX},${target.vpY}) " +
            "dx=$dx dy=$dyTarget facing=N",
            mapOf("kind" to wanted, "vpX" to "${target.vpX}", "vpY" to "${target.vpY}"),
        )
    }

    /**
     * Raw button sequence — Gemini emits this directly when navigating tile-by-
     * tile or stepping through dialogs. Each button is one tap; we step a few
     * idle frames between to let the engine apply movement / advance scroll
     * before the next press. The whole sequence is committed in one ToolOutcome
     * (Ok with summary message) so the next turn picks up post-sequence state.
     */
    override suspend fun sequence(buttons: List<String>): ToolOutcome {
        if (buttons.isEmpty()) return ToolOutcome.Reject("sequence: empty buttons list")
        val normalized = buttons.map { normaliseButton(it) }
        val unknown = normalized.zip(buttons).filter { it.first == null }.map { it.second }
        if (unknown.isNotEmpty()) {
            return ToolOutcome.Reject("sequence: unknown buttons [${unknown.joinToString(",")}] — allowed: Up,Down,Left,Right,A,B,START,SELECT")
        }
        // Hard cap: 1 button per sequence. FF1 NES viewport is only 16×14 tiles
        // and NPCs/walls block frequently; sequences ≥2 overshot the target
        // before the LLM saw the result. With max=1 the Executor reassesses
        // after every single tap (Sonnet-every-turn already runs anyway, so
        // there's no extra LLM cost — just no overshoot).
        val MAX = 1
        val executed = normalized.filterNotNull().take(MAX)
        val truncated = (normalized.size - executed.size).coerceAtLeast(0)
        val pre = toolset.getState().ram
        for (b in executed) {
            toolset.tap(button = b, count = 1, pressFrames = 5, gapFrames = 8)
            // A / B / START open or close dialog/menu overlays that take
            // ~30 frames to render. If we only settle 6 frames the next-turn
            // snapshot lands mid-fade and Gemini sees a half-drawn dialog
            // while RAM still says "no menu" (Coneria shops/inn don't move
            // screenState/mapflags). Cardinal moves don't open overlays so
            // 6 frames is fine for them.
            val settleFrames = when (b) { "A", "B", "START", "SELECT" -> 30; else -> 6 }
            toolset.step(buttons = emptyList(), frames = settleFrames)
            // Per-button live screenshot for the viewer (overwrites snapshots/live.png).
            // Lets the viewer see motion at the button granularity instead of waiting
            // for the next per-turn snapshot dump.
            livePngFile?.let { path ->
                runCatching {
                    val b64 = toolset.getScreen().base64
                    val bytes = java.util.Base64.getDecoder().decode(b64)
                    java.nio.file.Files.write(path, bytes)
                }
            }
        }
        toolset.step(buttons = emptyList(), frames = 12)
        // Final live snapshot after settling.
        livePngFile?.let { path ->
            runCatching {
                val b64 = toolset.getScreen().base64
                val bytes = java.util.Base64.getDecoder().decode(b64)
                java.nio.file.Files.write(path, bytes)
            }
        }
        val post = toolset.getState().ram
        val sm = "(${post["smPlayerX"] ?: '?'},${post["smPlayerY"] ?: '?'})"
        val w  = "(${post["worldX"] ?: '?'},${post["worldY"] ?: '?'})"
        val truncNote = if (truncated > 0) " [truncated $truncated extra: max=$MAX/turn]" else ""
        return ToolOutcome.Ok(
            "sequence: tapped ${executed.size} buttons$truncNote; sm=$sm world=$w " +
            "mid=${post["currentMapId"] ?: '?'} mf=${post["mapflags"] ?: '?'}",
            mapOf(
                "preSm" to "(${pre["smPlayerX"]},${pre["smPlayerY"]})",
                "postSm" to sm,
            )
        )
    }

    private fun normaliseButton(b: String): String? = when (b.trim().uppercase()) {
        "UP", "U", "N"        -> "Up"
        "DOWN", "D", "S"      -> "Down"
        "LEFT", "L", "W"      -> "Left"
        "RIGHT", "R", "E"     -> "Right"
        "A"                   -> "A"
        "B"                   -> "B"
        "START"               -> "START"
        "SELECT"              -> "SELECT"
        else                  -> null
    }

    private fun wrap(s: SkillResult): ToolOutcome =
        if (s.ok) ToolOutcome.Ok(s.message, s.ramAfter.mapValues { it.value.toString() })
        else ToolOutcome.Fail(s.message)
}
