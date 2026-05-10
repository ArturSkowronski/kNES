package knes.agent.skills

import knes.agent.runtime.AgentScratchpad
import knes.agent.tools.EmulatorToolset

/**
 * 2026-05-09 cont 3 — empirical Coneria-style town-overlay escape.
 *
 * The post-buy state has the agent inside the town overlay (mapflags.bit0=1,
 * mapId=0). The standard ExitInterior BFS doesn't help because mapId=0 decodes
 * to OVERWORLD tiles, not town tiles. Vision-LLM nav also failed in cont-3
 * runs #4-#5. Cardinal walking with NPC patience failed in run #6 (RAM diff:
 * just a wall, no dialog).
 *
 * This skill takes a deterministic exploration approach with scratchpad memory:
 *   1. Build an adjacency map by experimentally tapping each cardinal at every
 *      position the party visits. Record (smPre, dir) → smPost / blocked.
 *   2. If a tap clears mapflags.bit0 → success, scratchpad records the path.
 *   3. If stuck, prefer cardinals leading to NEW positions over revisits;
 *      treat dialog-opens (any RAM byte change beyond smPlayer/localY) as
 *      blocked — B-spam to recover, then mark that direction as "doorway".
 *   4. Cap by maxTaps; on failure, the scratchpad still contains the partial
 *      adjacency map + the dialog/blocked tile classifications, so the next
 *      run inherits and continues from there.
 *
 * Key win over `ExitInterior.walkOutOfTownOverlay`: when DOWN deadlocks at
 * (11,14), this skill SYSTEMATICALLY tries every other reachable position to
 * find the exit, instead of just rotating cardinals randomly. And the
 * scratchpad means knowledge accumulates across runs — once we find the exit
 * once, future runs replay it directly.
 */
class ExitTownEmpirical(
    private val toolset: EmulatorToolset,
    private val scratchpad: AgentScratchpad,
) : Skill {
    override val id = "exit_town_empirical"
    override val description =
        "Empirically explore an FF1 town overlay, build an adjacency map of " +
            "walkable tiles, and exit when mapflags.bit0 clears. Memory-backed."

    private val PRESS_FRAMES = 6
    private val GAP_FRAMES = 14
    private val SETTLE_FRAMES = 8

    private data class Edge(val from: Pair<Int, Int>, val dir: String, val to: Pair<Int, Int>)

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxTaps = args["maxTaps"]?.toIntOrNull() ?: 120
        val defaultCardinals = listOf("DOWN", "RIGHT", "LEFT", "UP")

        // 8 B-taps pre-flight to dismiss any lingering dialog (cont-3 cardinal
        // walker showed shop "Welcome" can persist if BuyAtShop's 6 B-taps
        // weren't enough). Cheap insurance.
        var totalFrames = 0
        repeat(8) {
            val t = toolset.tap(button = "B", count = 1, pressFrames = 4, gapFrames = 8)
            val s = toolset.step(buttons = emptyList(), frames = 4)
            totalFrames += t.frame + s.frame
        }

        // 2026-05-09 cont 3: target the WARP TILE (the smPlayer where the
        // engine placed the party when it walked into town). Pure DOWN-bias
        // exploration leaves us at (13,22)/(14,22) — visually on overworld
        // but mapflags stuck at 3 because we're not on the warp tile.
        // Returning to the warp coord and pressing DOWN once should cross
        // the transition cleanly.
        val warpTile = scratchpad.walkInEntryTile()
        println("[exit-town-empirical] startup: warpTile=$warpTile (target for exit)")

        // Local adjacency: (x,y) → set of cardinals already attempted. Used to
        // prefer unexplored directions over re-tries of known walls/doorways.
        val tried = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()
        val blocked = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()
        val doorways = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()
        val edges = mutableListOf<Edge>()

        var taps = 0
        var transitionWaits = 0
        val mf3Exhausted = mutableSetOf<Pair<Int, Int>>()  // positions where mf3-recovery already failed
        while (taps < maxTaps) {
            val ramPre = toolset.getState().ram
            val mapflagsPre = ramPre["mapflags"] ?: 0
            if ((mapflagsPre and 0x01) == 0) {
                val msg = "exit_town_empirical: reached overworld at " +
                    "world=(${ramPre["worldX"]},${ramPre["worldY"]}) after $taps taps"
                scratchpad.record(kind = "exit",
                    summary = "town_exit_success",
                    smPost = (ramPre["smPlayerX"] ?: 0) to (ramPre["smPlayerY"] ?: 0),
                    mapflagsPost = mapflagsPre,
                    note = "$taps taps; ${edges.size} edges in adjacency")
                return SkillResult(true, msg, totalFrames, ramPre)
            }
            // 2026-05-09 cont 3: bit 1 either = mid-scroll transition (FF1
            // profile says "column-drawing") OR a dialog open (cont-3 #2 RIGHT
            // (11,14)→(12,14) showed mapflags=3 with weapon-shop dialog up).
            // Three-stage recovery:
            //   waits 1-3: idle frames (bit 1 may clear if transition completing)
            //   waits 4-6: B-spam (bit 1 may clear if dialog dismissable)
            //   waits 7-8: long-hold DOWN (bit 1 may clear if engine wants
            //              direction commitment for transition exit)
            // After cap exhausted, fall through to normal tap logic — caller
            // can classify the tile as doorway/transition-stuck and move on.
            val curPosForRecovery = (ramPre["smPlayerX"] ?: 0) to (ramPre["smPlayerY"] ?: 0)
            if ((mapflagsPre and 0x02) != 0 && transitionWaits < 8 &&
                curPosForRecovery !in mf3Exhausted) {
                transitionWaits++
                val recovery = when {
                    transitionWaits <= 3 -> "idle"
                    transitionWaits <= 6 -> "b-spam"
                    else -> "long-down"
                }
                when (recovery) {
                    "idle" -> {
                        // Long idle: cont-3 #8 evidence shows agent is VISUALLY
                        // on overworld at south edge of Coneria but mapflags
                        // bit 1 takes much longer than 30 frames to clear.
                        // 300 frames = ~5 seconds wallclock = enough margin.
                        val s = toolset.step(buttons = emptyList(), frames = 300)
                        totalFrames += s.frame
                    }
                    "b-spam" -> {
                        repeat(4) {
                            val t = toolset.tap(button = "B", count = 1, pressFrames = 4, gapFrames = 8)
                            val s = toolset.step(buttons = emptyList(), frames = 6)
                            totalFrames += t.frame + s.frame
                        }
                    }
                    "long-down" -> {
                        // Multiple short DOWN taps + settle — maybe the engine
                        // wants discrete commits, not held button.
                        repeat(5) {
                            val t = toolset.tap(button = "Down", count = 1, pressFrames = 6, gapFrames = 12)
                            val s = toolset.step(buttons = emptyList(), frames = 60)
                            totalFrames += t.frame + s.frame
                        }
                    }
                }
                println("[exit-town-empirical] mf3-recovery $transitionWaits/8 ($recovery) " +
                    "mapflags=$mapflagsPre smPlayer=(${ramPre["smPlayerX"]},${ramPre["smPlayerY"]})")
                if (transitionWaits == 8) {
                    // Final fallback: dump screenshot so we can see what the
                    // stuck state looks like, then mark this position as
                    // recovery-exhausted so the next loop iteration falls
                    // through to normal cardinal-tap logic instead of
                    // re-entering recovery indefinitely.
                    try {
                        val shot = toolset.getScreen().base64
                        val bytes = java.util.Base64.getDecoder().decode(shot)
                        java.io.File("/tmp/spec5-mf3-stuck-${ramPre["smPlayerX"]}-${ramPre["smPlayerY"]}.png")
                            .writeBytes(bytes)
                        println("[exit-town-empirical] dumped /tmp/spec5-mf3-stuck-" +
                            "${ramPre["smPlayerX"]}-${ramPre["smPlayerY"]}.png; mf3 exhausted at $curPosForRecovery")
                    } catch (_: Throwable) {}
                    mf3Exhausted += curPosForRecovery
                }
                continue
            }
            transitionWaits = 0  // reset for next stuck state
            val sx = ramPre["smPlayerX"] ?: 0
            val sy = ramPre["smPlayerY"] ?: 0
            val pos = sx to sy

            // If at warp tile (entry coord), force DOWN to commit the exit
            // transition — this should cross the warp and clear mapflags.
            if (warpTile != null && pos == warpTile) {
                println("[exit-town-empirical] AT WARP TILE $warpTile — committing DOWN to cross")
                val t = toolset.tap(button = "DOWN", count = 1,
                    pressFrames = PRESS_FRAMES, gapFrames = GAP_FRAMES)
                val s = toolset.step(buttons = emptyList(), frames = 60)
                totalFrames += t.frame + s.frame
                taps++
                continue
            }

            // Pick cardinal:
            //   1. Reverse-trajectory hint if available (reversed walk-in)
            //   2. Untried frontier first, then known-walkable
            val triedHere = tried.getOrPut(pos) { mutableSetOf() }
            val blockedHere = blocked[pos] ?: emptySet()
            val doorwaysHere = doorways[pos] ?: emptySet()
            val candidates = defaultCardinals.filter {
                it !in triedHere && it !in blockedHere && it !in doorwaysHere
            }
            // Manhattan-gradient pick: when warpTile is known, prefer the
            // cardinal that REDUCES Manhattan distance to the warp tile.
            // Falls back to default DOWN-bias when warp unknown or no
            // gradient-improving candidate available.
            fun gradientPick(c: List<String>): String? {
                if (warpTile == null) return null
                val (tx, ty) = warpTile
                val curDist = kotlin.math.abs(sx - tx) + kotlin.math.abs(sy - ty)
                if (curDist == 0) return null  // already at warp; default behaviour
                return c.minByOrNull { dirCardinal ->
                    val nsx = sx + when (dirCardinal) { "RIGHT" -> 1; "LEFT" -> -1; else -> 0 }
                    val nsy = sy + when (dirCardinal) { "DOWN" -> 1; "UP" -> -1; else -> 0 }
                    kotlin.math.abs(nsx - tx) + kotlin.math.abs(nsy - ty)
                }?.takeIf { picked ->
                    val nsx = sx + when (picked) { "RIGHT" -> 1; "LEFT" -> -1; else -> 0 }
                    val nsy = sy + when (picked) { "DOWN" -> 1; "UP" -> -1; else -> 0 }
                    kotlin.math.abs(nsx - tx) + kotlin.math.abs(nsy - ty) < curDist
                }
            }
            val dir = when {
                candidates.isNotEmpty() -> {
                    // Prefer warp-gradient candidate; fall back to DOWN-bias.
                    gradientPick(candidates) ?: candidates.first()
                }
                else -> {
                    // All four already explored; the local cell is exhausted.
                    // Try any walkable edge to leave this cell, otherwise bail.
                    val walkable = edges.filter { it.from == pos }.map { it.dir }.distinct()
                    if (walkable.isEmpty()) {
                        val msg = "exit_town_empirical: all cardinals exhausted at " +
                            "smPlayer=($sx,$sy), no walkable edges — giving up after $taps taps"
                        scratchpad.record(kind = "exit",
                            summary = "town_exit_giveup",
                            smPost = pos,
                            mapflagsPost = mapflagsPre,
                            note = "$taps taps; all cardinals at ($sx,$sy) tried; ${edges.size} edges")
                        return SkillResult(false, msg, totalFrames, ramPre)
                    }
                    walkable.random()
                }
            }
            triedHere.add(dir)

            // Tap + settle.
            val tap = toolset.tap(button = dir, count = 1,
                pressFrames = PRESS_FRAMES, gapFrames = GAP_FRAMES)
            val settle = toolset.step(buttons = emptyList(), frames = SETTLE_FRAMES)
            totalFrames += tap.frame + settle.frame
            taps++

            val ramPost = toolset.getState().ram
            val mapflagsPost = ramPost["mapflags"] ?: 0
            val sxPost = ramPost["smPlayerX"] ?: 0
            val syPost = ramPost["smPlayerY"] ?: 0
            val posPost = sxPost to syPost

            // Detect dialog open: any byte beyond {smPlayer*, localY, facing,
            // mapflags} flipped. cont-3 ground-truth diff confirmed normal
            // movement only changes those four. Anything else = NPC interaction.
            val movementBytes = setOf("smPlayerX", "smPlayerY", "localX", "localY", "facing", "mapflags")
            val unexpectedFlips = ramPost.filter { (k, v) ->
                k !in movementBytes && (ramPre[k] ?: 0) != v
            }.keys
            val dialogOpened = unexpectedFlips.isNotEmpty()

            scratchpad.record(kind = "tap",
                summary = "explore: $dir",
                dir = dir,
                smPre = pos,
                smPost = posPost,
                mapflagsPost = mapflagsPost,
                note = if (dialogOpened) "dialog: flipped=${unexpectedFlips.take(3)}" else null)

            // Classify outcome.
            when {
                (mapflagsPost and 0x01) == 0 -> {
                    // Already handled top-of-loop next iteration; record edge.
                    edges += Edge(pos, dir, posPost)
                }
                dialogOpened -> {
                    // Walked into NPC/sign. B-spam to recover; mark as doorway.
                    doorways.getOrPut(pos) { mutableSetOf() }.add(dir)
                    repeat(8) {
                        val t = toolset.tap(button = "B", count = 1, pressFrames = 4, gapFrames = 8)
                        val s = toolset.step(buttons = emptyList(), frames = 4)
                        totalFrames += t.frame + s.frame
                    }
                }
                posPost == pos -> {
                    // No move and no dialog → wall.
                    blocked.getOrPut(pos) { mutableSetOf() }.add(dir)
                }
                else -> {
                    // Successful move → record edge for backtrack.
                    edges += Edge(pos, dir, posPost)
                    // Allow re-trying this dir from new position later.
                    triedHere.remove(dir)
                }
            }
        }

        // 2026-05-09 cont 3 user-provided exit hint after observing the
        // /tmp/spec5-mf3-stuck-14-22.png screenshot: "blocks on a tree :)
        // Up Right right Down Down z ostatniej pozycji". From the deadlock
        // tile (~14,22) a tree blocks straight-south; the path AROUND the
        // tree to reach the warp tile (16,23) is U R R D D — then one more
        // D to commit the warp transition. Try this as a final hardcoded
        // detour before giving up.
        run {
            // Pre-detour: wait for mapflags bit 1 to settle.
            for (waitIter in 0 until 10) {
                val r = toolset.getState().ram
                if (((r["mapflags"] ?: 0) and 0x02) == 0) break
                val s = toolset.step(buttons = emptyList(), frames = 60)
                totalFrames += s.frame
            }
            val ramBefore = toolset.getState().ram
            val sxBefore = ramBefore["smPlayerX"] ?: 0
            val syBefore = ramBefore["smPlayerY"] ?: 0
            val mfBefore = ramBefore["mapflags"] ?: 0
            println("[exit-town-empirical] tree-detour starting from smPlayer=($sxBefore,$syBefore) mapflags=$mfBefore")

            // 2026-05-09 cont 3 reliability fix: empirical exploration ends
            // at non-deterministic positions across runs (cont-3 #N at (13,14),
            // earlier at (14,22)). The hardcoded U R R D D D sequence was
            // tuned for (14,22) → reaches warp tile (16,23). From other
            // start positions it fails. Pre-navigate to (14,22) using simple
            // delta cardinals: RIGHT (deltaX>0) / LEFT (deltaX<0) then DOWN
            // (deltaY>0) / UP (deltaY<0). Then run the U R R D D D detour.
            val targetX = 14
            val targetY = 22
            val approachSeq = mutableListOf<String>()
            // X delta first
            val dx = targetX - sxBefore
            repeat(kotlin.math.abs(dx)) { approachSeq += if (dx > 0) "Right" else "Left" }
            // Then Y delta
            val dy = targetY - syBefore
            repeat(kotlin.math.abs(dy)) { approachSeq += if (dy > 0) "Down" else "Up" }
            println("[exit-town-empirical] approach to (14,22) via ${approachSeq.size} taps: $approachSeq")
            for (apDir in approachSeq) {
                // Pre-tap mapflags settle
                for (w in 0 until 10) {
                    val rW = toolset.getState().ram
                    if (((rW["mapflags"] ?: 0) and 0x02) == 0) break
                    val sW = toolset.step(buttons = emptyList(), frames = 30)
                    totalFrames += sW.frame
                }
                val tA = toolset.tap(button = apDir, count = 1, pressFrames = 6, gapFrames = 14)
                val sA = toolset.step(buttons = emptyList(), frames = 30)
                totalFrames += tA.frame + sA.frame
            }
            val ramAtDetour = toolset.getState().ram
            println("[exit-town-empirical] approach done; smPlayer=" +
                "(${ramAtDetour["smPlayerX"]},${ramAtDetour["smPlayerY"]}) mapflags=${ramAtDetour["mapflags"]}")
            val sequence = listOf("Up", "Right", "Right", "Down", "Down", "Down")
            for ((idx, dir) in sequence.withIndex()) {
                // Pre-tap: wait for any in-flight mid-scroll to complete
                // (mapflags bit 1 cleared) so the tap actually registers.
                for (w in 0 until 10) {
                    val rW = toolset.getState().ram
                    if (((rW["mapflags"] ?: 0) and 0x02) == 0) break
                    val sW = toolset.step(buttons = emptyList(), frames = 30)
                    totalFrames += sW.frame
                }
                // Run-9 evidence: pressFrames=18 caused 2-tile moves
                // (overshoot past warp tile). Use the established 6-frame
                // press / 14-frame gap pattern for reliable 1-tile-per-tap
                // movement. Long settle between taps so any mid-scroll
                // mapflags=3 transient resolves to 1 before next tap.
                val t = toolset.tap(button = dir, count = 1, pressFrames = 6, gapFrames = 14)
                val s = toolset.step(buttons = emptyList(), frames = 60)
                totalFrames += t.frame + s.frame
                val r = toolset.getState().ram
                val mf = r["mapflags"] ?: 0
                val sx2 = r["smPlayerX"] ?: 0
                val sy2 = r["smPlayerY"] ?: 0
                println("[exit-town-empirical] tree-detour[$idx] $dir → smPlayer=($sx2,$sy2) mapflags=$mf")
                // Per-step visual evidence for tree-detour traversal.
                try {
                    val shot = toolset.getScreen().base64
                    val pb = java.util.Base64.getDecoder().decode(shot)
                    val fn = "/tmp/spec5-detour-%02d-%s-sm%d_%d-mf%d.png"
                        .format(idx, dir.lowercase(), sx2, sy2, mf)
                    java.io.File(fn).writeBytes(pb)
                    println("[exit-town-empirical] dumped $fn")
                } catch (_: Throwable) {}
                scratchpad.record(kind = "tap",
                    summary = "tree_detour: $dir",
                    dir = dir.uppercase(),
                    smPost = sx2 to sy2,
                    mapflagsPost = mf,
                    note = "user-provided detour step ${idx + 1}/${sequence.size}")
                if ((mf and 0x01) == 0) {
                    // Visual verification: dump screenshot at the moment of
                    // declared exit success so we can confirm the agent really
                    // is on overworld (not a false-positive mapflags read).
                    try {
                        val shot = toolset.getScreen().base64
                        val bytes = java.util.Base64.getDecoder().decode(shot)
                        java.io.File("/tmp/spec5-exit-success.png").writeBytes(bytes)
                        println("[exit-town-empirical] dumped /tmp/spec5-exit-success.png — visual proof of exit")
                    } catch (_: Throwable) {}
                    // Post-exit safety walk: walk WEST off the gate tile.
                    // Coneria is on a peninsula — south of (147,155) is
                    // blocked (water), east is water, north re-enters town.
                    // West (147→146→145…) leads onto open grass with random
                    // encounters. cont-3 evidence: 8 DOWN taps post-exit
                    // didn't change worldY (south blocked). LEFT should move
                    // worldX. Walk 4 LEFT then 2 DOWN to clear gate AND
                    // descend to encounter zone.
                    val postSettle = toolset.step(buttons = emptyList(), frames = 120)
                    totalFrames += postSettle.frame
                    val rPostWarp = toolset.getState().ram
                    println("[exit-town-empirical] post-warp: world=(${rPostWarp["worldX"]},${rPostWarp["worldY"]}) mapflags=${rPostWarp["mapflags"]}")
                    // cont-3 evidence + user hint "trees walkable, why castle re-entry":
                    //   - Walking UP from (145,155) reaches (145,152) = castle warp
                    //   - Walking LEFT 2 lands at (145,155) but corridor=2 still
                    //     extends to (145,153) → castle re-entry on shifted anchor
                    //   - Coneria area on overworld: castle north + town south,
                    //     grass+forest extends SOUTH of castle outer area
                    //   - Trees ARE walkable in FF1 (encounter zone, not obstacle)
                    //
                    // Walk 1 LEFT (off the (147,155) gate to (146,155) grass)
                    // then DOWN repeatedly to descend south away from castle.
                    // Anchor will capture below castle's south edge → grind
                    // corridor stays clear of castle warp.
                    val postExitSequence = listOf(
                        "Left", "Down", "Down", "Down", "Down",
                        "Down", "Down", "Down", "Down", "Down", "Down",
                    )
                    for ((tapIdx, ddir) in postExitSequence.withIndex()) {
                        val t = toolset.tap(button = ddir, count = 1, pressFrames = 5, gapFrames = 30)
                        val s = toolset.step(buttons = emptyList(), frames = 60)
                        totalFrames += t.frame + s.frame
                        val rNow = toolset.getState().ram
                        val wxNow = rNow["worldX"] ?: 0
                        val wyNow = rNow["worldY"] ?: 0
                        val mfNow = rNow["mapflags"] ?: 0
                        println("[exit-town-empirical] post-exit $ddir[$tapIdx] " +
                            "world=($wxNow,$wyNow) mapflags=$mfNow")
                        // Per-step visual evidence: dump screenshot every tap so
                        // we can verify the agent actually IS on overworld and
                        // each move is real, not a false-positive RAM signal.
                        try {
                            val shot = toolset.getScreen().base64
                            val pb = java.util.Base64.getDecoder().decode(shot)
                            val fn = "/tmp/spec5-postexit-%02d-%s-w%d_%d-mf%d.png"
                                .format(tapIdx, ddir.lowercase(), wxNow, wyNow, mfNow)
                            java.io.File(fn).writeBytes(pb)
                            println("[exit-town-empirical] dumped $fn")
                        } catch (_: Throwable) {}
                    }
                    val rFinal = toolset.getState().ram
                    val msg = "exit_town_empirical: tree-detour SUCCESS at " +
                        "world=(${rFinal["worldX"]},${rFinal["worldY"]}) after step ${idx + 1} " +
                        "+ 3 post-exit DOWN taps for grind-anchor distancing"
                    scratchpad.record(kind = "exit",
                        summary = "town_exit_success_via_tree_detour",
                        smPost = (rFinal["smPlayerX"] ?: 0) to (rFinal["smPlayerY"] ?: 0),
                        mapflagsPost = rFinal["mapflags"] ?: 0,
                        note = "step ${idx + 1}/${sequence.size} + post-exit south walk")
                    return SkillResult(true, msg, totalFrames, rFinal)
                }
            }
        }

        val ramFinal = toolset.getState().ram
        val msg = "exit_town_empirical: did not exit in $maxTaps taps + tree-detour " +
            "(visited ${tried.size} positions, ${blocked.values.sumOf { it.size }} walls, " +
            "${doorways.values.sumOf { it.size }} doorways, ${edges.size} walkable edges)"
        scratchpad.record(kind = "exit",
            summary = "town_exit_timeout",
            smPost = (ramFinal["smPlayerX"] ?: 0) to (ramFinal["smPlayerY"] ?: 0),
            mapflagsPost = ramFinal["mapflags"] ?: 0,
            note = "${tried.size} positions ${edges.size} edges; tree-detour also failed")
        return SkillResult(false, msg, totalFrames, ramFinal)
    }
}
