package knes.agent.v2.tools

import knes.agent.skills.BuyAtShop
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
}

class DefaultToolSurface(
    private val toolset: EmulatorToolset,
    private val phaseProvider: () -> Phase,
    private val pressStartUntilOverworld: PressStartUntilOverworld,
    private val walkOverworld: WalkOverworldTo,
    private val exitInterior: ExitInterior,
    private val buyAtShopSkill: BuyAtShop,
    private val equipWeaponSkill: EquipWeapon,
    private val restAtInnSkill: RestAtInn,
    private val haiku: HaikuClient? = null,
    private val menuWalker: MenuWalker = MenuWalker(),
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
     * smPlayerX/smPlayerY (town-local) coordinates. Each step:
     *  1. Read smPlayer. If already at or adjacent to target → Ok.
     *  2. Ask Haiku for the next cardinal toward the target, with the
     *     screenshot as context (Haiku locates party + target → derives
     *     direction per `feedback_locate_party_first.md`).
     *  3. Tap that cardinal; verify smPlayer changed via RAM.
     *  4. If no movement (NPC block / wall), try the perpendicular axis
     *     toward the target. After K consecutive non-moves, give up.
     *
     * Returns Ok if adjacent to target, Fail otherwise. Reject if Haiku
     * isn't wired (test harness path).
     */
    private suspend fun townWalkVision(tx: Int, ty: Int, maxSteps: Int): ToolOutcome {
        val haiku = this.haiku ?: return ToolOutcome.Reject(
            "townWalkVision: Haiku not wired (DefaultToolSurface haiku=null)"
        )
        var steps = 0
        var consecutiveStuck = 0
        val stuckLimit = 8
        while (steps < maxSteps) {
            val r = toolset.getState().ram
            val sx = r["smPlayerX"] ?: 0
            val sy = r["smPlayerY"] ?: 0
            val dx = tx - sx
            val dy = ty - sy
            if (sx == tx && sy == ty)
                return ToolOutcome.Ok("townWalk: reached ($tx,$ty) in $steps steps")
            if (kotlin.math.abs(dx) + kotlin.math.abs(dy) == 1)
                return ToolOutcome.Ok("townWalk: adjacent to ($tx,$ty) at sm=($sx,$sy) in $steps steps")

            val b64 = toolset.getScreen().base64
            val dir = try {
                haiku.directionTo(b64, "town tile local($tx,$ty) — distance dx=$dx dy=$dy from party at local($sx,$sy)")
            } catch (e: Exception) {
                return ToolOutcome.Fail("townWalk: Haiku error after $steps steps: ${e.message?.take(80)}")
            }
            val primary = mapCardinal(dir) ?: dominantAxisCardinal(dx, dy)
                ?: return ToolOutcome.Fail("townWalk: no direction (Haiku=$dir, dx=$dx dy=$dy) after $steps steps")

            // Try primary; if blocked, try both perpendicular cardinals before
            // counting as stuck. Coneria evidence (Smoke 1 v10): party reached
            // sm=(15,18), Haiku kept picking blocked cardinal — single-direction
            // retry hit the 4-stuck guard and gave up. Perpendicular fallback
            // lets us route around walls/NPCs without a full BFS pathfinder.
            val tried = tryCardinals(primary, dx, dy, sx, sy)
            steps += tried.tapsUsed
            val r2 = toolset.getState().ram
            val sx2 = r2["smPlayerX"] ?: 0
            val sy2 = r2["smPlayerY"] ?: 0
            val moved = (sx2 != sx || sy2 != sy)
            if (!moved) {
                consecutiveStuck++
                if (consecutiveStuck >= stuckLimit)
                    return ToolOutcome.Fail("townWalk: stuck at sm=($sx,$sy) for $stuckLimit rounds toward ($tx,$ty); tried=${tried.tried.joinToString(",")}")
            } else {
                consecutiveStuck = 0
            }
        }
        val r = toolset.getState().ram
        return ToolOutcome.Fail("townWalk: maxSteps=$maxSteps reached, sm=(${r["smPlayerX"]},${r["smPlayerY"]}) target=($tx,$ty)")
    }

    private data class CardinalTryResult(val tried: List<String>, val tapsUsed: Int)

    /** Try primary, then perpendiculars, then opposite — first one that moves the party wins. */
    private suspend fun tryCardinals(
        primary: String, dx: Int, dy: Int, sx: Int, sy: Int,
    ): CardinalTryResult {
        // Build try order: primary first, then two perpendiculars (target-biased),
        // then the opposite (last resort).
        val perps = perpendicularsOf(primary, dx, dy)
        val opp = oppositeOf(primary)
        val order = listOfNotNull(primary, perps.first, perps.second, opp).distinct()
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

    override suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome {
        require(items.size == charSlots.size) { "items.size must equal charSlots.size" }
        val results = mutableListOf<String>()
        for ((i, c) in items.zip(charSlots)) {
            val r = buyAtShopSkill.invoke(mapOf(
                "itemSlot" to "$i", "forCharSlot" to "$c", "expectedKeeperKind" to "weapon",
            ))
            results += "i=$i c=$c → ${r.message}"
            if (!r.ok) return ToolOutcome.Fail("buyAtShop aborted at i=$i c=$c: ${r.message}")
        }
        return ToolOutcome.Ok(results.joinToString("; "))
    }

    override suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome {
        val r = equipWeaponSkill.invoke(mapOf("charSlot" to "$charSlot", "weaponSlot" to "$weaponSlot"))
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

    private fun wrap(s: SkillResult): ToolOutcome =
        if (s.ok) ToolOutcome.Ok(s.message, s.ramAfter.mapValues { it.value.toString() })
        else ToolOutcome.Fail(s.message)
}
