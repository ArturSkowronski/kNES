# FF1 Koog Agent V3.0 Implementation Plan — Vision-First Interior Navigation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the FF1 agent escape interior maps (towns, castles, dungeons) using a screenshot-driven walker, mirroring the Claude Plays Pokemon architecture — instead of the V2.4–V2.6 ROM/RAM decoder stack which plateaued at 13% step success in Coneria Town.

**Decision context:** see `docs/superpowers/DECISION-2026-05-03-v3-vision-first-interior.md`. Datacrystal verification showed our `currentMapId=0x0048` is from a V2.4 heuristic, not canonical. Rather than chase a proper decoder, we delegate interior navigation to vision.

**Tech Stack:** Kotlin 2.3.20 / Gradle 9.4.1 / Kotest 6.1.4 / Koog 0.6.1 / Anthropic Messages API (direct Ktor, like `AnthropicVisionPhaseClassifier`).

**Branch:** `ff1-agent-v2-4` (existing worktree). PR after slice 1 ships and validates live.

**Slice 1 scope (this plan):**
- **Task 0: feasibility probe — single screenshot → Anthropic vision → does it pick a sensible direction?** Cheapest possible validation of the riskiest assumption before building anything.
- New vision-driven directional navigator that internally calls Anthropic vision per step.
- New `walkInteriorVision` skill exposed as `@Tool`.
- Executor + advisor prompts updated; `exitInterior`/`findPathToExit` marked deprecated.
- One live run; trace + screenshots saved.

**Out of scope:**
- Multi-modal executor input (screenshot attached to executor turn). Defer to V3.1 if slice 1 succeeds.
- Removing decoder code. `InteriorMapLoader` etc. stay compiled and tested; only the prompts and live behavior change.
- NPC interaction / talk-NPC tool. V3.2.
- Battle / overworld changes. None.

---

## Architecture

```
[ FfPhase = Indoors ]
       │
       ▼
Executor LLM (Sonnet 4.5)
       │  picks  walkInteriorVision(maxSteps)
       ▼
WalkInteriorVision skill (loop ≤ maxSteps)
       │
       │  per iteration:
       │   1. screenshot = toolset.getScreen()
       │   2. dir = VisionInteriorNavigator.nextDirection(screenshot)
       │      └─ direct Anthropic Messages API call (Sonnet 4.5, image + JSON answer)
       │   3. if dir == EXIT → return ExitedInterior (let outer loop reobserve)
       │      if dir == STUCK → return Stuck
       │   4. toolset.step(buttons=[dir.button], frames=48)
       │   5. read RAM: did localX/localY change OR did we transition to overworld?
       │      - moved        → continue
       │      - blocked      → record, ask vision again with hint
       │      - transitioned → return ExitedInterior
       │
       ▼
SkillResult → AgentSession trace → next outer turn
```

**Why a skill rather than executor multimodal input?**
Koog 0.6.1 has no documented multimodal-input support for `AIAgent.run`. `AnthropicVisionPhaseClassifier` already proves the bypass pattern works: direct Ktor → Anthropic → JSON parse. Slice 1 keeps Koog as the outer agent runner and delegates vision to a sub-LLM inside one tool. This avoids touching Koog's prompt construction.

**Cost model:**
- 1 vision turn ≈ 1500 image tokens + ~200 reasoning tokens at Sonnet 4.5.
- ≈ $0.005–0.01 per step.
- 50 steps to clear Coneria Town ≈ $0.25–$0.50.
- Already in budget envelope of current runs ($0.50–$1).

---

## File Structure

**New files:**
- `knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt`
- `knes-agent/src/main/kotlin/knes/agent/skills/WalkInteriorVision.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/VisionInteriorNavigatorTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/skills/WalkInteriorVisionTest.kt`

**Modified files:**
- `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt` — add `walkInteriorVision` `@Tool`; mark `exitInterior` / `findPathToExit` deprecated in description.
- `knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt` — system prompt: prefer `walkInteriorVision` in Indoors; deprecate decoder skills.
- `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt` — note in prompt that interior maps are vision-navigated; advisor should not propose decoder waypoints.
- `knes-agent/src/main/kotlin/knes/agent/Main.kt` — wire `VisionInteriorNavigator` from `ANTHROPIC_API_KEY` into the skill chain.

**Files NOT touched (decoder stays for V3.1+ if vision underperforms):**
- `InteriorMap.kt`, `InteriorMapLoader.kt`, `InteriorTileClassifier.kt`, `MapSession.kt`, `InteriorPathfinder.kt`, `ExitInterior.kt`, `ExitBuilding.kt`.

---

## Task 0: Feasibility probe — does Sonnet 4.5 understand FF1 interior frames?

**Why first:** the entire V3.0 pivot rests on "vision can read FF1 sprite art and pick a walkable direction." If Sonnet 4.5 returns garbage on FF1 8-bit pixel art (low resolution, ambiguous tiles), every later task is wasted. ~10 min of work, ~$0.05 cost, kills or confirms the bet.

**Files:**
- New: `knes-agent/src/test/kotlin/knes/agent/perception/VisionInteriorFeasibilityTest.kt`
- New (artifact): `docs/superpowers/notes/2026-05-03-vision-interior-feasibility.md`

- [ ] **Step 1: Boot ROM and capture three different interior screenshots offline**

Test boots ROM, runs `pressStartUntilOverworld`, walks into Coneria (existing `(145,152)` step S transition known from V2.6 evidence), saves screenshot 1. Then walks 1 tile in some direction, saves screenshot 2. Then taps DOWN repeatedly until either RAM transitions to overworld or 10 steps, saves screenshot 3. Each screenshot is a base64 PNG.

Test is `.config(enabled = ANTHROPIC_API_KEY != null && ROM exists)` so it only runs when explicitly invoked.

- [ ] **Step 2: Single Anthropic call per screenshot**

Direct Ktor `post` to `/v1/messages` with `claude-sonnet-4-6`, the V3.0 navigator system prompt (verbatim from Task 1), and the screenshot. Print the JSON response.

- [ ] **Step 3: Manual verdict — write feasibility note**

Capture in `notes/2026-05-03-vision-interior-feasibility.md`:
- The three screenshots (paste base64 to PNG → save as `shot-1.png` etc).
- The three vision responses verbatim.
- Manual judgment: did it pick a plausible direction for each?
- Verdict: GO / NO-GO / UNCERTAIN-needs-iteration.

If GO → continue to Task 1.
If NO-GO → stop, escalate to user with evidence; consider C (advisor screenshots only) or revisit decoder path.
If UNCERTAIN → iterate prompt 1-2 times in same session, no commits yet.

- [ ] **Step 4: Commit only the test + note (no production code yet)**

```bash
git add knes-agent/src/test/kotlin/knes/agent/perception/VisionInteriorFeasibilityTest.kt \
        docs/superpowers/notes/2026-05-03-vision-interior-feasibility.md
git commit -m "research(agent): V3.0 — vision feasibility probe on FF1 interior frames"
```

---

## Task 1: VisionInteriorNavigator — direction-picker over Anthropic Messages API

**Files:**
- New: `knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt`
- New: `knes-agent/src/test/kotlin/knes/agent/perception/VisionInteriorNavigatorTest.kt`

- [ ] **Step 1: Define interface + result types**

```kotlin
package knes.agent.perception

enum class InteriorMove(val button: String?) {
    NORTH("UP"), SOUTH("DOWN"), EAST("RIGHT"), WEST("LEFT"),
    EXIT(null),    // model believes we just exited / are about to exit
    STUCK(null),   // no walkable direction visible (give up to outer loop)
    UNCLEAR(null), // vision did not parse — treat like STUCK
}

interface VisionInteriorNavigator {
    suspend fun nextDirection(
        screenshotBase64: String,
        frame: Int,
        hintLastBlocked: InteriorMove? = null,
    ): InteriorMove
}
```

- [ ] **Step 2: Anthropic implementation (mirrors AnthropicVisionPhaseClassifier)**

`AnthropicVisionInteriorNavigator` — direct Ktor `post` to `/v1/messages`, model `claude-sonnet-4-6` (better spatial reasoning than Haiku for FF1 sprite art), max_tokens=120, JSON response:
```
{ "direction": "N|S|E|W|EXIT|STUCK", "reason": "..." }
```

System prompt:
```
You are a navigator playing Final Fantasy 1 (NES) inside a town/castle/dungeon.
The party stands at the centre of the screen (around tile column 8, row 7).
Your job: pick ONE direction (N/S/E/W) that moves the party toward the
nearest exit — a door, staircase, opening at the south edge, or any clear
corridor leading off the visible area.
Avoid: walls, water, shop counters, locked rooms, NPCs blocking the path.
If the screen shows the overworld (top-down terrain map with no walls), return EXIT.
If you cannot identify any clear walkable direction, return STUCK.
Output ONLY JSON: {"direction":"N|S|E|W|EXIT|STUCK","reason":"<<=80 chars"}.
```

User prompt template:
```
Pick the next direction for the party. ${hintLine}
```
Where `hintLine` is empty by default, or `"Last attempt to go ${dir} was blocked — try a different direction."` when `hintLastBlocked != null`.

Response parser uses `Regex("""\"direction\"\s*:\s*\"([A-Z]+)\"""")`. UNCLEAR on parse failure or HTTP error.

Cache by frame number (same frame, same hint → no API call), like the phase classifier.

- [ ] **Step 3: Unit test stub navigator**

A no-API `FakeVisionInteriorNavigator` that returns a programmable sequence of moves; used by `WalkInteriorVisionTest` (next task) to drive the skill without hitting Anthropic.

```kotlin
class FakeVisionInteriorNavigator(private val script: List<InteriorMove>) : VisionInteriorNavigator {
    private var i = 0
    override suspend fun nextDirection(
        screenshotBase64: String, frame: Int, hintLastBlocked: InteriorMove?
    ): InteriorMove = script[i.coerceAtMost(script.lastIndex)].also { i++ }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Test parser robustness**

`VisionInteriorNavigatorTest`:
- "happy path JSON" → parses to NORTH/SOUTH/etc.
- "JSON wrapped in code fence" → parses (the regex is permissive).
- "non-JSON text" → returns UNCLEAR.
- "EXIT" / "STUCK" → maps correctly.
- Test case names follow Kotest `should "..."` convention.

Run: `./gradlew :knes-agent:test --tests VisionInteriorNavigatorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/VisionInteriorNavigatorTest.kt
git commit -m "feat(agent): V3.0 — VisionInteriorNavigator (direct Anthropic vision per step)"
```

---

## Task 2: WalkInteriorVision skill

**Files:**
- New: `knes-agent/src/main/kotlin/knes/agent/skills/WalkInteriorVision.kt`
- New: `knes-agent/src/test/kotlin/knes/agent/skills/WalkInteriorVisionTest.kt`

- [ ] **Step 1: Skill implementation**

```kotlin
class WalkInteriorVision(
    private val toolset: EmulatorToolset,
    private val navigator: VisionInteriorNavigator,
    private val toolCallLog: ToolCallLog? = null,
    private val framesPerTile: Int = 48,   // matches V2.4.5 ExitInterior tuning
) : Skill {
    override val id = "walk_interior_vision"
    override val description =
        "Walk inside an FF1 interior map by asking a vision model for one direction at a time."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 24
        var totalFrames = 0
        var stepsTaken = 0
        var lastBlocked: InteriorMove? = null

        while (stepsTaken < maxSteps) {
            val ramPre = toolset.getState().ram
            // Outer-loop signals — encounter or transition take precedence.
            if ((ramPre["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ramPre)
            }
            val onOverworld = (ramPre["locationType"] ?: 0) == 0 &&
                (ramPre["localX"] ?: 0) == 0 && (ramPre["localY"] ?: 0) == 0
            if (onOverworld) {
                return SkillResult(true,
                    "exited interior to overworld at (${ramPre["worldX"]},${ramPre["worldY"]})",
                    totalFrames, ramPre)
            }

            val shotB64 = toolset.getScreen().base64
            val frame = toolset.getState().frame
            val dir = navigator.nextDirection(shotB64, frame, lastBlocked)
            toolCallLog?.append("walkInteriorVision.dir", "step=$stepsTaken dir=${dir.name}" +
                (lastBlocked?.let { " hintBlocked=${it.name}" } ?: ""))

            when (dir) {
                InteriorMove.EXIT -> return SkillResult(true,
                    "vision says exited after $stepsTaken steps", totalFrames, ramPre)
                InteriorMove.STUCK, InteriorMove.UNCLEAR ->
                    return SkillResult(false,
                        "vision returned ${dir.name} after $stepsTaken steps", totalFrames, ramPre)
                else -> { /* fall through to tap */ }
            }

            val r = toolset.step(buttons = listOf(dir.button!!), frames = framesPerTile)
            totalFrames += r.frame
            stepsTaken++

            val ramPost = toolset.getState().ram
            val moved = ramPost["localX"] != ramPre["localX"] ||
                        ramPost["localY"] != ramPre["localY"]
            val transitioned = (ramPost["locationType"] ?: 0) == 0 &&
                               (ramPost["localX"] ?: 0) == 0 && (ramPost["localY"] ?: 0) == 0
            toolCallLog?.append("walkInteriorVision.step",
                "from=(${ramPre["localX"]},${ramPre["localY"]}) " +
                "after=(${ramPost["localX"]},${ramPost["localY"]}) " +
                "moved=$moved transitioned=$transitioned")

            lastBlocked = if (!moved && !transitioned) dir else null
            if (transitioned) return SkillResult(true,
                "exited mid-loop at (${ramPost["worldX"]},${ramPost["worldY"]})",
                totalFrames, ramPost)
        }

        val ram = toolset.getState().ram
        return SkillResult(false, "walked $maxSteps steps without exit", totalFrames, ram)
    }
}
```

- [ ] **Step 2: Unit test (no real ROM, no real network)**

`WalkInteriorVisionTest` uses:
- `FakeEmulatorToolset` — minimal stub that records button presses and returns canned RAM dict + dummy base64 screenshot.
- `FakeVisionInteriorNavigator` from Task 1 — programmable script.

Cases:
1. Script `[NORTH]` and toolset reports localX/Y unchanged twice → after `maxSteps=2` returns `ok=false`, hint blocked recorded.
2. Script `[EAST, EAST, EXIT]` and toolset reports localX changing each tap → returns `ok=true` "vision says exited".
3. Toolset reports overworld RAM on entry (locationType=0, localX/Y=0) → returns `ok=true` "exited interior to overworld" without ever calling navigator.
4. Toolset reports `screenState=0x68` (encounter) on entry → returns `ok=true` "encounter triggered".
5. After a blocked step, the next `nextDirection` call receives `hintLastBlocked` populated.

Run: `./gradlew :knes-agent:test --tests WalkInteriorVisionTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/WalkInteriorVision.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/WalkInteriorVisionTest.kt
git commit -m "feat(agent): V3.0 — WalkInteriorVision skill (vision per step + RAM verify)"
```

---

## Task 3: Register `walkInteriorVision` `@Tool` and deprecate decoder tools in description

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt`

- [ ] **Step 1: Inject navigator + skill instance**

Add constructor parameter `navigator: VisionInteriorNavigator?` (nullable so existing tests that build SkillRegistry without it keep compiling). Construct `walkInteriorVisionSkill = navigator?.let { WalkInteriorVision(toolset, it, toolCallLog) }`.

- [ ] **Step 2: Add `@Tool` method**

```kotlin
@Tool
@LLMDescription(
    "PREFERRED for Indoors phase. Walk inside the current FF1 interior map by " +
        "asking a vision model for one direction at a time. Each step: looks at " +
        "the screen, picks a direction, taps the button, verifies movement via RAM. " +
        "Stops on exit-to-overworld, encounter, or visual STUCK. maxSteps default 24."
)
suspend fun walkInteriorVision(maxSteps: Int = 24): SkillResult {
    val skill = walkInteriorVisionSkill
        ?: return SkillResult(false, "vision navigator not configured (ANTHROPIC_API_KEY missing?)", 0, emptyMap())
    toolCallLog.append("walkInteriorVision", "maxSteps=$maxSteps")
    return skill.invoke(mapOf("maxSteps" to "$maxSteps"))
}
```

- [ ] **Step 3: Update existing decoder tool descriptions**

Mark `exitInterior` and `findPathToExit` as `(DEPRECATED — use walkInteriorVision in Indoors)`. Do NOT remove.

```kotlin
@Tool
@LLMDescription(
    "(DEPRECATED on towns) Walk to the nearest exit using the offline interior decoder. " +
        "Step success rate ~13% on town maps because the decoder mis-aligns sub-maps. " +
        "Prefer walkInteriorVision in Indoors. Kept available for measurement / fallback."
)
suspend fun exitInterior(maxSteps: Int = 64): SkillResult { … }
```
(`findPathToExit` description analogous.)

- [ ] **Step 4: Compile + run all existing tests**

Run: `./gradlew :knes-agent:test`
Expected: BUILD SUCCESSFUL — no regressions; existing tests use a no-arg `SkillRegistry` constructor or one without navigator, both unchanged.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt
git commit -m "feat(agent): V3.0 — register walkInteriorVision @Tool; deprecate exitInterior on towns"
```

---

## Task 4: Update executor system prompt — vision-first interior

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt`

- [ ] **Step 1: Edit the `Skills available` block**

Replace the `exitInterior` line and add `walkInteriorVision` directly after `pressStartUntilOverworld`:

```
- pressStartUntilOverworld: title screen → overworld with default party
- walkInteriorVision: PREFERRED in Indoors. Vision-driven step-by-step walk
  toward the nearest exit. Stops on exit, encounter, or visual STUCK.
- exitInterior: (deprecated on towns; ~13% step success) decoder-based exit walk.
  Kept as fallback only.
- walkOverworldTo(targetX, targetY): walk on overworld using deterministic
  BFS pathfinder; aborts on encounter
- findPath(targetX, targetY): query the overworld pathfinder (does not move)
- findPathToExit: (deprecated) decoder query for interior pathfinder
- battleFightAll: every alive character uses FIGHT until battle ends
- walkUntilEncounter: walk randomly until a battle starts
- askAdvisor(reason): consult the planner when stuck or at a phase boundary
```

- [ ] **Step 2: Update the "Indoors" block in FF1 KNOWLEDGE**

Replace the block describing `exitInterior` + ASCII map with:

```
- Indoors phase: inside a town / castle / dungeon. The map decoder is
  unreliable on town maps (covers <30% of tiles). Use walkInteriorVision —
  it sees the screen and picks one direction per step. Just call it; the
  skill loops internally until exit, encounter, or visually stuck.
- If walkInteriorVision returns STUCK, ask the advisor with a screenshot
  rather than retrying the decoder.
- Spawn at Overworld(146, 158); you should not see Indoors at the very
  start of a run.
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin`

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt
git commit -m "feat(agent): V3.0 — executor prompt prefers walkInteriorVision in Indoors"
```

---

## Task 5: Update advisor prompt — stop proposing decoder waypoints in interior

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`

- [ ] **Step 1: Read current prompt**

- [ ] **Step 2: Add an Indoors-specific guidance line**

Append (or splice into existing FF1 FACTS block):

```
- Indoors navigation is vision-based: tell the executor to call walkInteriorVision
  with a step budget (e.g. 12–24). Do NOT propose target localX/localY coordinates
  inside towns or castles — the decoder is unreliable. For the overworld, you
  may continue to propose (worldX, worldY) waypoints.
```

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :knes-agent:compileKotlin
git add knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt
git commit -m "feat(agent): V3.0 — advisor prompt: vision-based interior; no decoder waypoints"
```

---

## Task 6: Wire VisionInteriorNavigator from Main.kt

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/Main.kt`

- [ ] **Step 1: Construct the navigator**

```kotlin
val visionInteriorNavigator: VisionInteriorNavigator? = anthropicApiKey?.let {
    AnthropicVisionInteriorNavigator(apiKey = it)
}
```

- [ ] **Step 2: Plumb into SkillRegistry / ExecutorAgent**

If `SkillRegistry` is built inside `ExecutorAgent`, add the navigator to ExecutorAgent's constructor and forward to its inner SkillRegistry. Use the existing AnthropicSession config object for the api key — don't re-read env in two places.

- [ ] **Step 3: Smoke run (no live API)**

```bash
./gradlew :knes-agent:assemble :knes-agent:test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/Main.kt \
        knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt
git commit -m "feat(agent): V3.0 — wire VisionInteriorNavigator into executor"
```

---

## Task 7: Live run — V3.0 slice 1 evidence

**Files (new):**
- `docs/superpowers/runs/2026-05-03-v3-vision-interior/SUMMARY.md`
- (auto) `docs/superpowers/runs/2026-05-03-v3-vision-interior/<ISO>/trace.jsonl`

- [ ] **Step 1: Run agent**

```bash
ANTHROPIC_API_KEY=… \
KNES_RUN_DIR=$(pwd)/docs/superpowers/runs/2026-05-03-v3-vision-interior \
./gradlew :knes-agent:run \
  --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes --profile=ff1 \
          --max-skill-invocations=40 --wall-clock-cap-seconds=720"
```

- [ ] **Step 2: Inspect trace**

Quick analysis script:
```bash
python3 -c "
import json
events=[json.loads(l) for l in open('docs/superpowers/runs/.../trace.jsonl')]
calls=[c for e in events if e.get('toolCalls') for c in e['toolCalls']]
steps=[c for c in calls if 'walkInteriorVision.step' in c]
moves=[s for s in steps if 'moved=true' in s]
print(f'walkInteriorVision steps: {len(steps)} moves: {len(moves)} success={len(moves)/max(1,len(steps)):.1%}')"
```

- [ ] **Step 3: Write SUMMARY.md**

Report:
- Outcome (Victory / OutOfBudget / PartyDefeated / Error).
- walkInteriorVision step success rate.
- Whether agent exited Coneria interior at least once via vision (DoD).
- Cost estimate (turn count × ~$0.01 vision + advisor calls).
- Failure modes if any.

- [ ] **Step 4: Commit run evidence**

```bash
git add docs/superpowers/runs/2026-05-03-v3-vision-interior/
git commit -m "evidence(agent): V3.0 slice 1 — vision-first interior navigation live run"
```

---

## Acceptance criteria (slice 1 done)

1. Unit tests:
   - `VisionInteriorNavigatorTest` covers JSON parse + UNCLEAR fallback.
   - `WalkInteriorVisionTest` covers 5 scenarios (script flow, encounter, overworld, blocked-hint, exit).
   - All other agent tests still pass — no regression.
2. Compile clean: `./gradlew :knes-agent:assemble`.
3. One live run completes without crash. Trace + SUMMARY saved.
4. **Step success rate ≥ 50%** in Coneria interior (currently 13% with decoder).
5. Party reaches Indoors → Overworld at least once via `walkInteriorVision` (NOT via `pressStartUntilOverworld` reset and NOT via `exitInterior`).

If criteria 4 or 5 fail: write a failure SUMMARY identifying which pattern broke (vision picks bad direction? RAM-moved check too strict? STUCK threshold wrong?), then iterate. Do not declare V3.0 complete until live evidence confirms the rate.

---

## After V3.0 slice 1

- Open PR for `ff1-agent-v2-4` (will include the prior 27 commits + V3.0 slice 1).
- If slice 1 hits DoD: V3.1 work could be NPC interaction (talk-to-king plot flag), proper multimodal executor input, or Chaos Shrine traversal. Decide based on what blocks `Outcome.AtGarlandBattle` next.
- If slice 1 misses: roll back to D+C hybrid (advisor screenshots when stuck + decoder for non-town interiors), or drop further interior work and investigate why FF1 vision is harder than Pokémon vision.
