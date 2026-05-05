# FF1 Koog Agent — Handoff (V5.30, session close 2026-05-05)

**Branch:** `ff1-agent-v2-4` of `/Users/askowronski/Priv/kNES-ff1-agent-v2`
**HEAD:** `5dbc08a` — V5.30 fog destination-OK + deliberate-warp-entry prompt
**Required env:** `ANTHROPIC_API_KEY` (live runs only; tests run without it)

---

## TL;DR — where we are (2026-05-05, session close after iter14)

V5.6 foundation (sm_player_x/y, mapflags) holds since previous session.
**This session shipped V5.19 → V5.30, fourteen commits, plus 14 live
iterations (iter1 → iter14) on the Coneria → Garland scenario.**
Goal (Garland battle) not reached; every run still OutOfBudget around
south Coneria. But the architectural picture is clean and the pieces
needed for the next attempt are in place.

Bottleneck migration log:
  - iter1-iter5  burn budget on vision-step skills inside mapId=24
  - iter6-iter8  agent uses panic-skills (`walkUntilEncounter`,
                 `pressStartUntilOverworld`) that bypass guardrails
  - iter9        V5.26 strips vision/random; agent finally moves
                 through overworld but gets blocked at (152, 151)
  - iter10-iter12 each iteration discovers ONE new Coneria-area warp
  - iter13       3 fog-blocked warps + transit-block sealed the
                 agent in a 1-tile pocket at (145, 153)
  - iter14       V5.29 + V5.30 unblocked deliberate warp entry;
                 agent reached (147, 154) and called
                 `exploreInteriorFrontier` 4× — first time the new
                 deterministic explorer ran live. Discovered 4th warp.

**Persistent warp memory (`~/.knes/ff1-overworld-warps.json`)
currently 4 tiles:** (145,152), (144,153), (147,153), (147,154).
Geographic pattern: south edge of Coneria Town entries Y=152-154.

**Architecture stable, prompt stable. Next session can either:**
  (a) Continue iterative discovery (~$1-2/iter; 1-2 more warps
      probably exist in this area)
  (b) Manual ROM seed: read FF1 entry-tile table from Disch
      disassembly and pre-populate the JSON in one shot
  (c) Hard-block panic skills: agent's `pressStartUntilOverworld`
      panic reset costs an entire run; V5.31 would AgentSession-
      side reject the call when phase != TitleOrMenu

---

## Read first (in this exact order)

1. `docs/superpowers/runs/2026-05-05-v530-iter14/2026-05-05T*/trace.jsonl` —
   most recent live run; shows V5.29 ExploreInteriorFrontier called 4×
   live, agent reached Overworld(147, 154), discovered new warp.
2. `~/.knes/ff1-overworld-warps.json` — persistent warp memory; current
   contents (4 tiles): (145,152), (144,153), (147,153), (147,154).
   All Coneria-area.
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
5dbc08a  V5.30  fog destination-OK + deliberate-warp-entry prompt
34a660d  V5.29  ExploreInteriorFrontier deterministic interior explorer
69c7fda  docs   handoff refresh for V5.28
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

## Open blockers (post-iter14)

### 1. V5.31 panic-reset guard (top priority for next session)

iter14 turn 7 evidence: agent called `pressStartUntilOverworld` from
inside Indoors as a "hard warp trap reset", cost the entire run. The
skill is supposed to be no-op when worldX != 0 + char1_hpLow != 0,
but the LLM still chose it. AgentSession-side guard: reject the call
unless phase == TitleOrMenu, return `BLOCKED, only valid on title
screen`. ~10-line change to SkillRegistry. Without this, every run
where the agent panics burns the budget twice (once on the failed
exploration, once on starting over).

### 2. exploreInteriorFrontier in iter14 didn't escape

V5.29 was called 4× live. Agent still ended OutOfBudget inside the
interior. Possible causes:
  - InteriorMemory.visited doesn't include all reachable tiles, BFS
    keeps proposing the same direction → "stuck: same blocked dir
    twice" guard fires too early
  - The town overlay's true exit isn't reachable through the BFS
    passable mask (decoder limitation per V3.0 13% data)
  - 4× 64-step ceiling = 256 frontier steps; might need 128 each
Need a focused trace inspection on the iter14 explore log lines
(`exploreInteriorFrontier.target` + `.step` in toolCallLog) to see
which case fires.

### 3. Iterative warp discovery is expensive

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
| 13 | preseeded 3 warps | sealed at (145,153) | — | overworld trapped, fog blocks all paths |
| 14 | V5.29 + V5.30 | (147,154) reached | (147,154) | exploreInteriorFrontier 4× LIVE; panic-reset cost the run |

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

> Resume FF1 Koog agent V5.30 from `HANDOFF.md`. 14 iterations this
> session, architecture now stable: intent-only skill surface (V5.26),
> persistent warp memory across runs (V5.25, currently 4 Coneria
> tiles), deterministic interior explorer (V5.29), fog destination-OK
> for deliberate warp entry (V5.30). Goal (Garland) not reached — every
> run still OutOfBudget around south Coneria. Top priority next:
> V5.31 AgentSession-side guard against `pressStartUntilOverworld` in
> non-TitleOrMenu phases (iter14 panic-reset cost a full run). Then
> either continue iterative warp discovery (~$1-2/iter) OR research
> FF1 ROM entry-tile table for one-shot manual seed. Conversation in
> Polish; user prefers short iterations, evidence-based conclusions,
> commits per closed phase.
