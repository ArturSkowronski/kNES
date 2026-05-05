# FF1 Koog Agent — Handoff (V5.28 → V5.29 in flight)

**Branch:** `ff1-agent-v2-4` of `/Users/askowronski/Priv/kNES-ff1-agent-v2`
**HEAD:** `bec76a2` — V5.28 fog block 1x1 around warp tiles
**Required env:** `ANTHROPIC_API_KEY` (live runs only; tests run without it)

---

## TL;DR — where we are (2026-05-05, post-iter12)

V5.6 foundation (sm_player_x/y, mapflags) holds since the previous session.
This session shipped V5.19 → V5.28, twelve commits, plus 12 live iterations
(iter1 → iter12) on the Coneria → Garland scenario. **Goal not reached.**
Each run currently OutOfBudget around the south edge of Coneria Town.

The session converged on a clear architectural picture: the planner LLM was
controlling per-step movement via `walkInteriorVision` /
`walkOverworldVision` / `walkUntilEncounter`, ignoring deterministic
guardrails. V5.26 removed those skills from the @Tool surface. After that
the bottleneck flipped from "agent burns budget on vision steps" to
"agent can't escape Coneria Town interior using exitInterior alone".

**Next deliverable in flight: V5.29 ExploreInteriorFrontier skill** —
deterministic frontier search using the existing `InteriorFrontier` +
`InteriorPathfinder` + `InteriorMemory` infrastructure. Replaces
walkInteriorVision as the way to uncover an interior; exit emerges as a
side-effect of full map coverage.

---

## Read first (in this exact order)

1. `docs/superpowers/runs/2026-05-05-v528-iter12/2026-05-05T*/trace.jsonl` —
   most recent live run; shows agent stuck at Overworld(144, 153) with
   exitInterior 2200 sub-steps unable to escape mapId=24.
2. `~/.knes/ff1-overworld-warps.json` — persistent warp memory; current
   contents (3 tiles): (145,152), (147,153), (144,153). All Coneria-area.
3. `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` —
   session loop, session-memory injection, persistent warp load/save.
4. `knes-agent/src/main/kotlin/knes/agent/perception/OverworldWarpMemory.kt` —
   V5.25 persistence layer.
5. `knes-agent/src/main/kotlin/knes/agent/perception/InteriorFrontier.kt` —
   the BFS already in tree that V5.29 will wrap as a skill.
6. `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt` —
   V5.26 intent-only @Tool surface. Vision-step + walkUntilEncounter
   methods retained but unannotated.
7. `knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt` —
   system prompt with V5.20-V5.27 rules, maxIterations=16.
8. `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt` —
   matching planner prompt.

---

## Commit log (this session)

```
bec76a2  V5.28  fog block warp tiles 1x1 (was 3x3, sealed agent in)
086c09c  V5.27  T/C entry is legit FF1 traversal, not a trap (prompt)
ea56a4b  V5.26  architectural shift, intent-only skill surface
555bd42  V5.25  persistent overworld warp memory across runs
ba39f02  V5.24  fog.markBlocked 3x3 zone around each warp tile
35437fa  V5.23.2 bump maxIterations 10→16 (cap=10 still fired)
f7c8b8b  V5.23.1 bump maxIterations 4→10 (cap=4 broke every turn)
55184dd  V5.23  Koog usage corrections (maxIterations 20→4 (rolled back), session memory)
a6e64fd  V5.22  goal-focus + propagate-failure-to-advisor rules (prompt)
9e8ba75  V5.21  three behavioral rules from iter1+iter2 evidence (prompt)
7601100  V5.20  WalkOverworldTo: ok=false on accidental interior entry
6197979  V5.19  wire AnthropicVisionOverworldNavigator into ExecutorAgent
```

---

## What's WORKING (do not regress)

### Code

- **V5.20 walkOverworldTo abort signal** — `ok=true` only when the entered
  interior matches (targetX, targetY); otherwise `ok=false` with
  "UNEXPECTED interior entry at world=(X,Y)" recovery hint.
- **V5.23 cross-turn session memory** in `AgentSession`:
  `failedWarpTiles: MutableSet<Pair<Int,Int>>`. Regex on `drainedCalls` for
  `world=(X,Y) ... targeted=false` adds tiles. Both advisor and executor
  observations are augmented with "Session memory — known FF1 warp tiles
  to avoid as targets or route-throughs: (X,Y), ...".
- **V5.24/V5.28 deterministic fog block** — exactly 1 tile per warp.
  BFS pathfinder honours `FogOfWar.isBlocked`, so the agent reroutes
  without depending on prompt compliance.
- **V5.25 persistent warp memory** — `OverworldWarpMemory` reads/writes
  `~/.knes/ff1-overworld-warps.json`. Loaded on AgentSession startup,
  preloads `failedWarpTiles` + fog blocks. Saved immediately on every
  detection so a crash mid-run keeps the discovery.
- **V5.26 intent-only @Tool surface** — `walkInteriorVision`,
  `walkOverworldVision`, `walkUntilEncounter` are retained as Kotlin
  methods but lack `@Tool` annotations. Koog does not surface them. The
  planner LLM only sees: `pressStartUntilOverworld`, `walkOverworldTo`,
  `exitInterior`, `battleFightAll`, `findPath`, `findPathToExit`,
  `askAdvisor`, `getState`.
- **V5.6 foundation** (sm_player_x/y, mapflags bit 0) — never broke,
  underlies all of the above.

### Prompts

- Executor system prompt (`ExecutorAgent.ff1ExecutorSystemPrompt`):
  - STUCK DETECTION (V5.21): 3 identical consecutive calls without phase
    change → askAdvisor.
  - UNINTENDED INTERIOR RECOVERY (V5.21): when walkOverworldTo returns
    "UNEXPECTED interior entry at (X,Y)", call exitInterior until
    Overworld then re-route avoiding (X,Y).
  - PROPAGATE FAILURE TO ADVISOR (V5.22): include warp coords in
    askAdvisor reason so the advisor reroutes.
  - GOAL FOCUS (V5.22+V5.26): terminal goal = Battle.enemyId=0x7C; random
    encounters are progress; after 5 failed exits askAdvisor.
  - T/C ENTRY IS LEGITIMATE (V5.27): pick a visible T/C neighbour as
    walkOverworldTo target when otherwise BLOCKED.
- Advisor system prompt (`AdvisorAgent.systemPrompt`):
  - GROUND TRUTH ONLY (V5.21): no FF1 coords from training data.
  - Matching corollaries to executor's V5.21/V5.22/V5.26/V5.27 rules.
  - Removed V5.21's hard-coded "go WEST to x=140 grass corridor"
    instruction — that route was the source of the (145,152) warp trap.

---

## Open blockers (post-iter12)

### 1. V5.29 ExploreInteriorFrontier (NEW, in flight, top priority)

Iter12 evidence: agent enters Coneria Town overlay, calls exitInterior with
maxSteps=64/128/256, 2200 sub-steps total, never escapes. Per V3.0
evidence the decoder gets ~13% step success on town overlays. The
remaining 87% are wasted budget.

The fix per the user's architectural critique (point 6, "exploration as
frontier search"): wrap `InteriorFrontier.nearestUnvisited` + a walker
loop into a `@Tool` skill. Deterministic, no LLM in the step loop.
Termination: phase=Overworld (real exit emerged), encounter, or no
frontier left (interior fully covered — at which point the runtime hands
back to advisor for "still stuck after full coverage" handling).

Code skeleton: see `WalkInteriorVision.kt` for the per-step loop pattern,
swap the vision call for `InteriorFrontier.nearestUnvisited` to pick the
direction, then `toolset.tap(direction)` and verify movement via RAM.

### 2. Iterative warp discovery is expensive

After 12 iters at $1-2 each, the persistent warp memory has 3 tiles:
(145,152), (147,153), (144,153). Geography:

```
Y=151  .  .  .  .  .
Y=152  .  W  .  .  .       W = warp
Y=153  W  .  W  .  .
```

Likely 2-3 more in the same zone (south edge of Coneria proper). Each
iteration discovers one more tile, then OutOfBudget. Cheaper option:
write a one-shot ROM-walker tool that reads the FF1 entry-tile table
(or seeds from Disch disassembly) and pre-populates the JSON. Defer
until V5.29 lands; if the agent can escape interiors deterministically,
the warp count matters less (entering Coneria becomes traversal, not
trap).

### 3. Phase-classifier confusion at warp boundaries

Iter12 turn 5+ phase=Overworld(144,153) but agent narrates
"Stuck in interior at (144,153)". RAM check shows mapflags=1 (in
standard map) yet the classifier returns Overworld. Suspect: brief
warp followed by rapid exit-back-to-overworld leaves RAM in a
transitional state at observation time. Symptom is benign (executor
falls back to walkOverworldTo, BFS still routes) but adds noise to
the trace. Worth a deeper RAM-diff probe later.

### 4. Anthropic prompt caching deferred

Decompiled jar confirms Koog 0.6.1 `SystemAnthropicMessage` has no
`cache_control` field; `PromptCacheConfig` is a no-op. Implementing
Anthropic prompt caching means either upgrading Koog (>=0.7?) or
writing a custom Ktor `HttpClient` that injects `cache_control:
{"type":"ephemeral"}` into the JSON body before send.
Estimated 70-90% reduction on input tokens for repeated system
prompts. Significant cost win, fragile implementation.

### 5. Vision skills retained as dormant code

`walkInteriorVision` / `walkOverworldVision` / `walkUntilEncounter`
methods exist in SkillRegistry without `@Tool` annotations. If V5.29
ExploreInteriorFrontier proves robust, delete the three methods and
their backing classes (`WalkInteriorVision.kt`,
`WalkOverworldVision.kt`, `VisionInteriorNavigator.kt`,
`VisionOverworldNavigator.kt`). Keep a note in HANDOFF if intentional.

---

## Iteration log (this session)

| Iter | Variant | Phases reached | New warps | Tools (highlights) |
|---|---|---|---|---|
| 1 | V5.19 baseline | mapId=8 → mapId=24 stuck | (145,152) | walkOverworldTo 11, exitInterior 437, walkInteriorVision 446 |
| 2 | V5.21 prompts | mapId=24 stuck | — | exitInterior 306, walkInteriorVision 261 |
| 3 | V5.20 ok=false | mapId=24 stuck | — | walkOverworldVision 17 (FIRST ever) |
| 4 | V5.21 + advisor reroute | mapId=24 stuck | — | walkOverworldTo 21, exitInterior 455 |
| 5 | V5.23 maxIter=4 | ITERATION_CAP every turn | — | regression, hotfix → V5.23.1 |
| 6 | V5.23.1 maxIter=10 | mapId=24 stuck | — | session memory printed (works) |
| 7 | V5.24 fog 3x3 + maxIter=16 | mapId=24 stuck | — | fog markBlocked logged |
| 8 | V5.25 preseeded (145,152) | mapId=24 (via walkUntilEncounter) | — | walkUntilEncounter bypassed fog |
| 9 | V5.26 intent-only | (152,151) — got past warp! | — | walkOverworldTo 49, NO vision |
| 10 | V5.27 T/C entry rule | (147,153) trip | (147,153) | walkOverworldTo 37, exitInterior 1741 |
| 11 | 2 warps preseed | sealed at (145,153) | — | 3x3 overlap bug |
| 12 | V5.28 fog 1x1 | (144,153) trip | (144,153) | exitInterior 2200, no escape |

---

## Useful CLI

```bash
# Live run (needs ANTHROPIC_API_KEY)
KNES_RUN_DIR=$(pwd)/docs/superpowers/runs/<date>-<topic> \
  ./gradlew :knes-agent:run \
    --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes --profile=ff1 \
            --max-skill-invocations=30 --wall-clock-cap-seconds=480 \
            --cost-cap-usd=2.0"

# Trace summary
python3 -c "
import json, re
events = [json.loads(l) for l in open('PATH/trace.jsonl')]
counts = {}
for e in events:
    for tc in e.get('toolCalls') or []:
        s = tc if isinstance(tc, str) else json.dumps(tc)
        m = re.match(r'(\\w+)', s)
        if m: counts[m.group(1)] = counts.get(m.group(1), 0) + 1
print('TOOLS:', counts)
for e in events: print(f'turn={e[\"turn\"]} {e[\"role\"]} {e[\"phase\"]}')"

# Inspect persistent warp memory
cat ~/.knes/ff1-overworld-warps.json

# Tests (2 pre-existing failures expected: Coneria8VisualDiffTest,
# ConeriaTownEmpiricalDiscoveryTest)
./gradlew :knes-agent:test
```

---

## Repo paths

- Worktree: `/Users/askowronski/Priv/kNES-ff1-agent-v2`
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Persistent warp memory: `~/.knes/ff1-overworld-warps.json`
- Persistent interior memory: `~/.knes/ff1-interior-memory.json`
- Iteration runs: `docs/superpowers/runs/2026-05-{04,05}-*/`

---

## First message to send to the next session

> Resume FF1 Koog agent V5.28 from `HANDOFF.md`. V5.6 foundation holds.
> Architecture is now intent-only (V5.26): planner LLM sees no
> per-step skills. Persistent warp memory (V5.25) tracks 3 Coneria-area
> warp tiles in `~/.knes/ff1-overworld-warps.json`. Top priority:
> finish V5.29 ExploreInteriorFrontier — wrap `InteriorFrontier` +
> `InteriorPathfinder` + `InteriorMemory` into a `@Tool` skill so the
> agent can escape Coneria Town interior deterministically (current
> exitInterior 13% step success → wastes budget). Conversation in
> Polish; user prefers short iterations, evidence-based conclusions,
> commits per closed phase.
