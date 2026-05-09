# Coneria End-to-End Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire reliable post-buy `EXIT` and `GRIND` phases so a Coneria run goes Entry → Buy → Exit → Grind → first-encounter-won, without regressing Phase 1 (walk-in) or Phase 2 (4/4 buy).

**Architecture:** Approach A from the spec — vision-LLM is the only town-exit path on the hot path; `ExitTownEmpirical` and `ExitInterior.walkOutOfTownOverlay` are dropped from the hot path (they remain reachable as offline fallback when `outfitNavigator == null`). Post-exit, a small pure helper picks a grind anchor by reading `OverworldMap` tile classifications (GRASS/FOREST = encounter zone). User gameplay hints land in `VisionInteriorNavigator.SYSTEM_PROMPT` (one paragraph). `GrindLoop` keeps its existing logic; we add an `encounterCounter` delta diagnostic log.

**Tech Stack:** Kotlin 1.9, kotest FunSpec for unit tests, Gradle (`./gradlew :knes-agent:test`, `./gradlew :knes-agent:run`).

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `knes-agent/src/main/kotlin/knes/agent/runtime/GrindAnchorSelector.kt` | Create | Pure function: pick grind anchor from party position + `OverworldMap`. Returns `(anchor, tileClass, fellBack)`. |
| `knes-agent/src/test/kotlin/knes/agent/runtime/GrindAnchorSelectorTest.kt` | Create | Unit tests for the selector across the 4 cases (party on grass / on forest / on town tile / surrounded by water). |
| `knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt` | Modify (lines 233–260) | Append one paragraph to `SYSTEM_PROMPT` encoding user hints #1/#3. |
| `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` | Modify (lines 1465–1497) | Replace exit block: vision-only when navigator wired, drop `ExitTownEmpirical` from hot path. Add anchor selection + grind orchestration with re-anchor (max 2 retries). Emit new trace markers. |
| `knes-agent/src/main/kotlin/knes/agent/skills/GrindLoop.kt` | Modify (lines 76–78 vicinity) | Log `encounterCounter` delta per step + emit `grind_encounter_byte_dead` warning if all-zero across the loop. |

**Pinned files (DO NOT touch — regression baseline per spec § Regression protection):**
`BuyAtShop*`, `SYSTEM_SHOP_PURCHASE` prompt, `Main.kt` savestate warm-up, `OutfitBootPhase.kt` entry guards, `NameTable.stateSave` in vNES.

---

## Task 1: Pure helper — `GrindAnchorSelector`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/GrindAnchorSelector.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/runtime/GrindAnchorSelectorTest.kt`

The selector takes a party world coordinate and an `OverworldMap`. If the party tile is `GRASS` or `FOREST`, the anchor is the party tile (no fallback). Else it scans a 5×5 box (`radius=2`) around party, finds all `GRASS`/`FOREST` tiles, and picks the closest by Manhattan distance, breaking ties by preferring south (`dy > 0`) then east (`dx > 0`). If no encounter tile is in the 5×5, the result is the party tile with `fellBack = true`.

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/runtime/GrindAnchorSelectorTest.kt`:

```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.OverworldMap
import knes.agent.perception.TileType
import java.lang.reflect.Constructor

/**
 * Builds a 256×256 OverworldMap from a tileId mapping. Tiles default to 0x17
 * (WATER, impassable) so any cell not explicitly populated is non-encounter.
 *
 * 0x00 = GRASS (per OverworldTileClassifier)
 * 0x03 = FOREST
 * 0x10 = MOUNTAIN
 * 0x17 = WATER (default fill)
 * 0x4A = TOWN
 */
private fun buildMap(populated: Map<Pair<Int, Int>, Int>): OverworldMap {
    val tiles = ByteArray(256 * 256) { 0x17.toByte() }
    for ((xy, id) in populated) {
        val (x, y) = xy
        tiles[y * 256 + x] = id.toByte()
    }
    val ctor: Constructor<OverworldMap> =
        OverworldMap::class.java.getDeclaredConstructor(ByteArray::class.java)
    ctor.isAccessible = true
    return ctor.newInstance(tiles)
}

class GrindAnchorSelectorTest : FunSpec({
    test("party on GRASS → anchor = party, no fallback") {
        val map = buildMap(mapOf(146 to 156 to 0x00))
        val pick = GrindAnchorSelector.pickGrindAnchor(146 to 156, map)
        pick.anchor shouldBe (146 to 156)
        pick.tileClass shouldBe TileType.GRASS
        pick.fellBack shouldBe false
    }

    test("party on FOREST → anchor = party, no fallback") {
        val map = buildMap(mapOf(146 to 156 to 0x03))
        val pick = GrindAnchorSelector.pickGrindAnchor(146 to 156, map)
        pick.anchor shouldBe (146 to 156)
        pick.tileClass shouldBe TileType.FOREST
        pick.fellBack shouldBe false
    }

    test("party on TOWN tile, GRASS one south → anchor shifts south") {
        val map = buildMap(mapOf(
            147 to 155 to 0x4A,  // party tile = TOWN
            147 to 156 to 0x00,  // south = GRASS
            147 to 154 to 0x00,  // north = GRASS (also valid — should lose tie-break)
        ))
        val pick = GrindAnchorSelector.pickGrindAnchor(147 to 155, map)
        pick.anchor shouldBe (147 to 156)
        pick.tileClass shouldBe TileType.GRASS
        pick.fellBack shouldBe false
    }

    test("party on TOWN tile, FOREST diagonally SE wins south+east tie-break vs NW GRASS") {
        val map = buildMap(mapOf(
            100 to 100 to 0x4A,                    // party = TOWN
            99 to 99 to 0x00,                      // NW grass (Manhattan 2)
            101 to 101 to 0x03,                    // SE forest (Manhattan 2) — should win
        ))
        val pick = GrindAnchorSelector.pickGrindAnchor(100 to 100, map)
        pick.anchor shouldBe (101 to 101)
        pick.tileClass shouldBe TileType.FOREST
        pick.fellBack shouldBe false
    }

    test("no encounter tile within radius → fellBack=true, anchor=party") {
        // Default fill is WATER; nothing populated near (50,50).
        val map = buildMap(mapOf(50 to 50 to 0x17))
        val pick = GrindAnchorSelector.pickGrindAnchor(50 to 50, map)
        pick.anchor shouldBe (50 to 50)
        pick.fellBack shouldBe true
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :knes-agent:test --tests "knes.agent.runtime.GrindAnchorSelectorTest"`
Expected: compilation failure — `GrindAnchorSelector` does not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `knes-agent/src/main/kotlin/knes/agent/runtime/GrindAnchorSelector.kt`:

```kotlin
package knes.agent.runtime

import knes.agent.perception.OverworldMap
import knes.agent.perception.TileType
import kotlin.math.abs

/**
 * Picks a grind anchor near the party using overworld tile classifications.
 * Encounter zones are GRASS or FOREST (FF1 mechanic, user-confirmed).
 * If the party already stands on an encounter tile, the anchor IS the party
 * tile. Otherwise the closest encounter tile within `radius` is picked,
 * breaking ties by preferring south (dy>0) then east (dx>0).
 */
object GrindAnchorSelector {
    data class Pick(
        val anchor: Pair<Int, Int>,
        val tileClass: TileType,
        val fellBack: Boolean,
    )

    fun pickGrindAnchor(
        party: Pair<Int, Int>,
        map: OverworldMap,
        radius: Int = 2,
    ): Pick {
        val (px, py) = party
        val partyClass = map.classifyAt(px, py)
        if (partyClass == TileType.GRASS || partyClass == TileType.FOREST) {
            return Pick(party, partyClass, fellBack = false)
        }
        var best: Pick? = null
        var bestDist = Int.MAX_VALUE
        var bestSouthBias = Int.MIN_VALUE
        var bestEastBias = Int.MIN_VALUE
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx == 0 && dy == 0) continue
                val tx = px + dx
                val ty = py + dy
                val klass = map.classifyAt(tx, ty)
                if (klass != TileType.GRASS && klass != TileType.FOREST) continue
                val dist = abs(dx) + abs(dy)
                val southBias = if (dy > 0) dy else 0
                val eastBias = if (dx > 0) dx else 0
                val better = when {
                    dist < bestDist -> true
                    dist == bestDist && southBias > bestSouthBias -> true
                    dist == bestDist && southBias == bestSouthBias && eastBias > bestEastBias -> true
                    else -> false
                }
                if (better) {
                    best = Pick(tx to ty, klass, fellBack = false)
                    bestDist = dist
                    bestSouthBias = southBias
                    bestEastBias = eastBias
                }
            }
        }
        return best ?: Pick(party, partyClass, fellBack = true)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :knes-agent:test --tests "knes.agent.runtime.GrindAnchorSelectorTest"`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/GrindAnchorSelector.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/GrindAnchorSelectorTest.kt
git commit -m "feat(grind): GrindAnchorSelector — pick GRASS/FOREST anchor from OverworldMap"
```

---

## Task 2: Append user-hint paragraph to `VisionInteriorNavigator.SYSTEM_PROMPT`

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt:233-260`

The existing `SYSTEM_PROMPT` already covers castles/dungeons and basic town nav. We append one paragraph encoding user hints #1 (DOWN-from-shop), #3 (overworld trees walkable). Hint #2 (green=grind target) is encoded in `GrindAnchorSelector` already, not in this prompt.

- [ ] **Step 1: Make the edit**

Open `knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt`. Find the line ending `Output ONLY JSON: {"direction":"N|S|E|W|EXIT|STUCK","reason":"<<=80 chars"}.` (around line 260). Insert this block immediately before that final line, inside the same string concatenation:

```kotlin
                "POST-SHOP TOWN EXIT (when the screen shows a town interior with shops/houses): " +
                "after buying weapons your goal is to leave the town to the overworld. " +
                "Walk SOUTH out of the shop building first (counter is north, door is south); " +
                "then keep walking SOUTH along the dirt path between buildings until the camera " +
                "scrolls off the town onto the overworld (terrain map: grass, trees, mountains, water). " +
                "Building doorways on LEFT/RIGHT lead INTO other shops and trap you in a dialog — " +
                "avoid them unless SOUTH is genuinely blocked. Trees in the town overlay block " +
                "movement; trees on the overworld do NOT — they're walkable encounter terrain. " +
```

The final concatenated line stays as-is (the `Output ONLY JSON: ...` text).

- [ ] **Step 2: Build to verify the string compiles**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Sanity-check the prompt rendered correctly**

Run: `grep -A2 "POST-SHOP TOWN EXIT" knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt`
Expected: prints the inserted lines.

- [ ] **Step 4: Run any existing navigator tests to confirm no regression**

Run: `./gradlew :knes-agent:test --tests "*VisionInteriorNavigator*"` (no-op if no tests exist for this class; skip cleanly).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt
git commit -m "feat(nav): post-shop town-exit hints in VisionInteriorNavigator prompt

Encode user hints #1 (DOWN-from-shop) and #3 (overworld trees walkable) so
the model walks SOUTH out of the shop building, avoids LEFT/RIGHT building
doorways, and doesn't return STUCK when it sees trees on the south horizon."
```

---

## Task 3: GrindLoop encounterCounter delta logging

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/GrindLoop.kt:76-78` (the `println` line + surrounding context)

We add a delta calculation against the previous step's counter, and emit a one-shot warning at the end of the loop if all 12 deltas were zero (signalling the wrong RAM byte or a peninsula dead-zone — visible in trace).

- [ ] **Step 1: Make the edit**

Open `knes-agent/src/main/kotlin/knes/agent/skills/GrindLoop.kt`. Two changes:

a) Add a counter-tracking var at the top of `invoke`. After the line `var goingNorth = true` (around line 42), add:

```kotlin
        var prevEncCounter: Int? = null
        var nonZeroDeltaSeen = false
```

b) In the per-step `println` block (around lines 76–78), wrap it to compute delta and update tracking. Replace:

```kotlin
            println("[grind] step=$steps world=(${ramStep["worldX"]},${ramStep["worldY"]}) " +
                "mapflags=${ramStep["mapflags"]} encounterCounter=${ramStep["encounterCounter"]} " +
                "screenState=0x${(ramStep["screenState"] ?: 0).toString(16)}")
```

with:

```kotlin
            val encNow = ramStep["encounterCounter"] ?: 0
            val encDelta = prevEncCounter?.let { encNow - it } ?: 0
            if (prevEncCounter != null && encDelta != 0) nonZeroDeltaSeen = true
            prevEncCounter = encNow
            println("[grind] step=$steps world=(${ramStep["worldX"]},${ramStep["worldY"]}) " +
                "mapflags=${ramStep["mapflags"]} encounterCounter=$encNow delta=$encDelta " +
                "screenState=0x${(ramStep["screenState"] ?: 0).toString(16)}")
```

c) Just before each of the **two** existing `return SkillResult` statements that report `NoEncounter` and the final fall-through (at the end of the function), add a one-shot warning `println` if `prevEncCounter != null && !nonZeroDeltaSeen`. The simplest way is to add it right before the fall-through `val stateAfter = toolset.getState()` outside the loop (around line 113):

```kotlin
        if (prevEncCounter != null && !nonZeroDeltaSeen) {
            println("[grind] WARN grind_encounter_byte_dead: encounterCounter=$prevEncCounter " +
                "stayed flat across $steps steps — wrong RAM byte or true dead-zone")
        }
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run existing GrindLoop tests if any**

Run: `./gradlew :knes-agent:test --tests "*GrindLoop*" --tests "*GrindAndHeal*"`
Expected: BUILD SUCCESSFUL with all green.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/GrindLoop.kt
git commit -m "feat(grind): log encounterCounter delta + warn on flat-counter loop

Per-step delta makes encounter-byte staleness visible in stdout; one-shot
WARN at loop end (grind_encounter_byte_dead) signals when all 12 steps
saw zero delta, distinguishing wrong RAM byte from true peninsula dead-zone."
```

---

## Task 4: Replace exit + grind block in `runOutfitBootPhase`

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt:1465-1536` (the exit + equip + summary block)

This is the largest edit. We:
1. Drop `ExitTownEmpirical` from the hot path (kept available only when `outfitNavigator == null`).
2. Use `WalkInteriorVision(navigator, historyHint=scratchpad.renderForLLM(), maxSteps=60)` as the primary exit.
3. After exit success, emit `boot_phase3_exit_result`, settle 120 frames, read world coords, call `GrindAnchorSelector.pickGrindAnchor(party, outfitViewportSource as OverworldMap)`, emit `boot_phase4_grind_anchor`.
4. Run `GrindLoop` with re-anchor (max 2 retries on `NoEncounter`); after each `GrindLoop`, emit `boot_phase4_grind_result`.
5. On `EncounteredBattle`, emit `boot_pipeline_end {victory:pending}` — actual battle win is handled by the strategic loop's existing PostBattle handler; we mark "pending" so trace makes the boundary visible. Equip stays gated by `KNES_FF1_BOOT_EQUIP` as today.

**Important:** preserve the existing scratchpad recording, the `boot_outfit_summary` event, and the equip logic. Only the exit/grind block changes.

- [ ] **Step 1: Read the current block to anchor the edit**

Run: `sed -n '1465,1540p' knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`
Expected output: the existing exit + equip + summary block, ending at `}` of `runOutfitBootPhase`.

- [ ] **Step 2: Replace lines 1465–1496 (exit block) with vision-only + anchor + grind orchestration**

Open `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`. Find the block starting at `// 5. Exit shop interior; equip on overworld.` (line 1465) and ending after the `scratchpad.record(...)` call that closes `boot_exit_interior_result` (around line 1496). Replace it with:

```kotlin
        // 5. Exit shop + town to overworld via vision-LLM (Phase 3).
        // Per spec 2026-05-09-coneria-pipeline: vision is the sole hot-path
        // exit. ExitTownEmpirical / tree-detour stay reachable only when the
        // navigator is unwired (offline tests).
        repeat(8) {
            toolset.tap(button = "B", count = 1, pressFrames = 4, gapFrames = 8)
            toolset.step(buttons = emptyList(), frames = 4)
        }

        val exitResult = if (outfitNavigator != null) {
            knes.agent.skills.WalkInteriorVision(
                toolset, outfitNavigator, toolCallLog,
                historyHint = scratchpad.renderForLLM(),
            ).invoke(mapOf("maxSteps" to "60"))
        } else {
            // Offline fallback: empirical+tree-detour, then BFS.
            val emp = knes.agent.skills.ExitTownEmpirical(toolset, scratchpad)
                .invoke(mapOf("maxTaps" to "120"))
            if (emp.ok) emp
            else ExitInterior(toolset, outfitMapSession, fog).invoke(emptyMap())
        }
        val exitRam = exitResult.ramAfter
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_phase3_exit_result: ok=${exitResult.ok} msg=${exitResult.message} " +
                "world=(${exitRam["worldX"] ?: "?"},${exitRam["worldY"] ?: "?"}) " +
                "mapflags=${exitRam["mapflags"] ?: "?"} " +
                "via=${if (outfitNavigator != null) "vision" else "fallback"}"))
        scratchpad.record(kind = "exit",
            summary = "phase3_exit_result: ok=${exitResult.ok}",
            smPost = (exitRam["smPlayerX"] ?: 0) to (exitRam["smPlayerY"] ?: 0),
            mapflagsPost = exitRam["mapflags"] ?: 0,
            note = exitResult.message.take(120))

        // Phase 4: grind anchor selection + GrindLoop with re-anchor.
        // Only run when exit succeeded AND viewport source is an OverworldMap.
        val owMap = outfitViewportSource as? knes.agent.perception.OverworldMap
        if (exitResult.ok && owMap != null) {
            // 120-frame settle so any mapflags.bit1 mid-scroll transient clears.
            toolset.step(buttons = emptyList(), frames = 120)
            val ramSettled = toolset.getState().ram
            val partyXY = (ramSettled["worldX"] ?: 0) to (ramSettled["worldY"] ?: 0)
            val triedAnchors = mutableSetOf<Pair<Int, Int>>()
            var firstPick = GrindAnchorSelector.pickGrindAnchor(partyXY, owMap)
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_phase4_grind_anchor: anchor=(${firstPick.anchor.first}," +
                    "${firstPick.anchor.second}) tileClass=${firstPick.tileClass} " +
                    "fellBack=${firstPick.fellBack} party=(${partyXY.first},${partyXY.second})"))
            var grindAttempt = 0
            val maxAttempts = 3  // 1 initial + 2 re-anchors
            var lastResult: knes.agent.skills.SkillResult? = null
            var currentPick = firstPick
            while (grindAttempt < maxAttempts) {
                triedAnchors += currentPick.anchor
                val (ax, ay) = currentPick.anchor
                lastResult = knes.agent.skills.GrindLoop(toolset).invoke(mapOf(
                    "anchorX" to ax.toString(),
                    "anchorY" to ay.toString(),
                    "corridorRadius" to "3",
                    "maxStepsWithoutEncounter" to "12",
                ))
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_phase4_grind_result: attempt=${grindAttempt + 1}/$maxAttempts " +
                        "anchor=($ax,$ay) outcome=${lastResult.message}"))
                val msg = lastResult.message
                val battle = msg.startsWith("EncounteredBattle")
                if (battle) break
                val noEnc = msg.startsWith("NoEncounter")
                if (!noEnc) break  // WanderedOff / Blocked → don't re-anchor
                grindAttempt++
                if (grindAttempt >= maxAttempts) break
                // Re-anchor: read current world coord and pick the next-best
                // tile that we haven't tried yet. Loosen by widening radius.
                val reRam = toolset.getState().ram
                val reParty = (reRam["worldX"] ?: ax) to (reRam["worldY"] ?: ay)
                val widerPicks = (1..3).asSequence().mapNotNull { r ->
                    val p = GrindAnchorSelector.pickGrindAnchor(reParty, owMap, radius = r + 1)
                    if (p.anchor in triedAnchors || p.fellBack) null else p
                }
                currentPick = widerPicks.firstOrNull() ?: break
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_phase4_grind_reanchor: attempt=${grindAttempt + 1} " +
                        "newAnchor=(${currentPick.anchor.first},${currentPick.anchor.second}) " +
                        "tileClass=${currentPick.tileClass}"))
            }
            val pipelineVictory = lastResult?.message?.startsWith("EncounteredBattle") == true
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_pipeline_end: victory=$pipelineVictory " +
                    "lastPhase=${if (pipelineVictory) "phase4_battle_pending" else "phase4_grind"}"))
        } else if (!exitResult.ok) {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_pipeline_end: victory=false lastPhase=phase3_exit_failed"))
        } else {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_pipeline_end: victory=false lastPhase=phase4_skipped_no_overworld_map"))
        }
```

The existing equip block (`if (System.getenv("KNES_FF1_BOOT_EQUIP")...`) and the final summary block (`val finalGold = ... boot_outfit_summary: $summary`) stay untouched — they're after this insertion.

- [ ] **Step 3: Verify imports — `GrindAnchorSelector` is in the same package, no import needed; `OverworldMap`/`SkillResult` may need explicit imports**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL. If `Unresolved reference: GrindAnchorSelector` or `SkillResult` appears, add at the top of `AgentSession.kt`:

```kotlin
import knes.agent.skills.SkillResult
```

(`GrindAnchorSelector` is in `knes.agent.runtime` — same package as `AgentSession`, no import needed.)

- [ ] **Step 4: Run the full unit-test suite to verify no regression**

Run: `./gradlew :knes-agent:test`
Expected: 348 unit tests + 3 in `SavestateRoundtripDebug` + 5 new in `GrindAnchorSelectorTest` = 356 tests, all green. If `OutfitBootPhaseTest` regresses, `runOutfitBootPhase` was perturbed beyond the exit block — STOP and re-read the diff.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt
git commit -m "feat(boot): vision-only Phase 3 exit + Phase 4 grind w/ anchor + reanchor

Drops ExitTownEmpirical / tree-detour from hot path (kept as offline fallback
when outfitNavigator==null). Post-exit settles 120 frames, picks anchor via
GrindAnchorSelector reading OverworldMap GRASS/FOREST classifications, runs
GrindLoop with up to 2 re-anchors on NoEncounter. New trace markers:
boot_phase3_exit_result, boot_phase4_grind_anchor, boot_phase4_grind_result,
boot_phase4_grind_reanchor, boot_pipeline_end."
```

---

## Task 5: Smoke check #1 — savestate-loaded run (validates Phase 3+4)

This is a **manual run** because Phase 3 exits a real emulator state via vision-LLM and we cannot mock the navigator faithfully in unit tests. Savestate-load is the fast path (~30s startup vs ~5min fresh).

- [ ] **Step 1: Verify the savestate exists**

Run: `ls -la /tmp/spec5-post-buy.savestate /tmp/spec5-post-buy.actions.json 2>&1`

Expected: both files present (savestate ~12KB, actions.json with walk-in trajectory). If absent, do Smoke check #2 first (which will dump the savestate at `boot_post_buy_savestate_dumped`).

- [ ] **Step 2: Run with savestate, capture trace**

Run:
```bash
KNES_VISION=gemini-pro \
KNES_FF1_LOAD_SAVESTATE=/tmp/spec5-post-buy.savestate \
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY \
GEMINI_API_KEY=$GEMINI_API_KEY \
./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
  --wall-clock-cap-seconds=300 --cost-cap-usd=2.0 --max-skill-invocations=120"
```

Expected: process exits in <5min. The strategic loop may bail post-grind via `UnknownMapTrap` or `OutOfBudget` — that's OK; what matters is what landed in the trace.

- [ ] **Step 3: Inspect the trace for the new markers**

Run:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E 'boot_phase3_exit_result|boot_phase4_grind_anchor|boot_phase4_grind_result|boot_pipeline_end' "$LATEST/trace.jsonl"
```

Expected output:
- `boot_phase3_exit_result` with `ok=true` and `via=vision`. If `ok=false`, vision-exit failed in 60 steps — escalate per spec § Risks (consider Approach B in a follow-up; do NOT re-introduce tree-detour).
- `boot_phase4_grind_anchor` with `tileClass=GRASS` or `FOREST`. If `fellBack=true`, the post-exit settle position is on town/castle/water — possible coast deadlock; verify by viewing the post-exit screenshot at `/tmp/spec5-postexit-*.png`.
- One or more `boot_phase4_grind_result` lines.
- One `boot_pipeline_end` with either `victory=true` (EncounteredBattle) or `victory=false`.

- [ ] **Step 4: Decide pass/fail**

Pass criteria for this smoke: `boot_phase3_exit_result.ok=true` AND at least one `boot_phase4_grind_result` line emitted. `victory=true` is the goal but not blocking — encounter rate is probabilistic and the spec allows up to 2 re-anchors.

If exit-result is `ok=false` after this commit, do NOT proceed to Smoke #2. Open a follow-up issue documenting the vision-exit failure mode (final mapflags, screenshot) and stop.

- [ ] **Step 5: No commit — this is observation only**

---

## Task 6: Smoke check #2 — fresh-run regression (validates Phase 1+2 baseline)

This validates that the rework did NOT break the 4/4 buy baseline. Runtime ~5–8 min (full advisor loop fires).

- [ ] **Step 1: Clear stale state**

Run:
```bash
rm -f ~/.knes/ff1-ow-memory.json /tmp/spec5-post-buy.savestate /tmp/spec5-post-buy.actions.json
cat > ~/.knes/ff1-landmarks.json <<'JSON'
{"version":1,"landmarks":[
  {"id":"interior_entry_147_155","kind":"TOWN_ENTRY","worldX":147,"worldY":155,
   "visited":true,"note":"coneria-town overworld waypoint","discoveredRunId":"preseed"},
  {"id":"weapon_shopkeeper_preseed","kind":"NPC_SHOPKEEPER","visited":false,
   "note":"kind=weapon; preseed; items=staff:5,dagger:5,nunchuck:10,rapier:10,hammer:10","discoveredRunId":"preseed"}
]}
JSON
```

- [ ] **Step 2: Fresh run**

Run:
```bash
KNES_VISION=gemini-pro \
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY \
GEMINI_API_KEY=$GEMINI_API_KEY \
./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
  --wall-clock-cap-seconds=900 --cost-cap-usd=3.0 --max-skill-invocations=120"
```

Expected: process completes within wall-clock cap.

- [ ] **Step 3: Verify Phase 2 baseline (4/4 buy)**

Run:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E 'BOUGHT|boot_outfit_summary|boot_post_buy_savestate' "$LATEST/trace.jsonl"
```

Expected: `boot_outfit_summary: weaponsBought=4 ...`. **If `weaponsBought < 4`, this is a regression of the pinned baseline — STOP, do NOT commit anything else; revert the rework commits and re-investigate.**

- [ ] **Step 4: Verify Phase 3+4 fired on fresh state too**

Run:
```bash
grep -E 'boot_phase3_exit_result|boot_phase4_grind_anchor|boot_pipeline_end' "$LATEST/trace.jsonl"
```

Expected: same shape as Smoke #1 — exit ok, anchor picked, pipeline_end emitted. Cost on this run will be higher (full advisor + vision-exit).

- [ ] **Step 5: No commit — observation only. Capture results in HANDOFF.md update.**

---

## Task 7: Update HANDOFF.md with cont-4 results

**Files:**
- Modify: `HANDOFF.md` — prepend a new "TL;DR — 2026-05-09 cont 4" section.

- [ ] **Step 1: Open `HANDOFF.md` and prepend a new TL;DR section above the existing cont-3 section**

The new section should state, with concrete numbers from the smoke runs:
- Final HEAD SHA after this plan's commits.
- Smoke #1 result: exit-ok? anchor tileClass? pipeline_end victory?
- Smoke #2 result: weaponsBought=N (must be 4 to pass), exit-ok, pipeline_end.
- Files touched (4 files per the file structure section above).
- Known follow-ups: e.g. if vision-exit was unreliable on certain start positions, note it; if encounter rate stayed zero across all 3 attempts in both smokes, recommend the next-session direction (pure-grass corridor further from coast, or `encounterCounter` byte audit).

Template:

```markdown
# FF1 Koog Agent — Handoff (Spec 5 — Coneria pipeline rework, 2026-05-09 cont 4)

**Master HEAD:** `<SHA>`.
**Branch:** `ff1-buy-and-equip-coneria`.
**Tests:** 356 unit + 3 in SavestateRoundtripDebug. Compiles clean.

## TL;DR — 2026-05-09 cont 4 progress

**Goal:** Phase 3 (exit) + Phase 4 (grind) reworked per
`docs/superpowers/specs/2026-05-09-coneria-pipeline-design.md`.

**What landed:**
- `GrindAnchorSelector` — pure helper picks GRASS/FOREST anchor from OverworldMap.
- `VisionInteriorNavigator.SYSTEM_PROMPT` — appended POST-SHOP TOWN EXIT paragraph.
- `runOutfitBootPhase` — drops ExitTownEmpirical/tree-detour from hot path; Phase 3 = WalkInteriorVision(historyHint=scratchpad, maxSteps=60); Phase 4 = anchor select + GrindLoop with up to 2 re-anchors.
- `GrindLoop` — encounterCounter delta log + grind_encounter_byte_dead WARN.
- New trace markers: boot_phase3_exit_result, boot_phase4_grind_anchor, boot_phase4_grind_result, boot_phase4_grind_reanchor, boot_pipeline_end.

**Smoke #1 (savestate):** <fill in>
**Smoke #2 (fresh):** <fill in>

**Known follow-ups:** <fill in>
```

- [ ] **Step 2: Commit the handoff**

```bash
git add HANDOFF.md
git commit -m "docs(handoff): cont-4 — Phase 3+4 rework landed, smoke results captured"
```

---

## Self-review notes (for the implementer)

If the smoke runs reveal that vision-exit is still unreliable:
- DO NOT re-enable `ExitTownEmpirical` in the hot path. The spec explicitly drops it.
- Open a new spec for Approach B (PPU nametable read).

If the smoke runs reveal `grind_encounter_byte_dead` warnings every loop:
- Open a follow-up to audit the `encounterCounter` byte address against the FF1 disasm.
- Do NOT widen the corridor or move the anchor outside the 5×5 in this plan — that's scope creep.

If `weaponsBought < 4` in Smoke #2:
- Revert. Re-read the diff against `runOutfitBootPhase`. The most likely culprits are an accidental edit to the lines BEFORE 1465 (Phase 2 / dialog dismiss) or AFTER 1496 (equip / summary). The only allowed change is the exit/grind block.
