# V4 hybrid C — 2026-05-03

**Outcome:** `OutOfBudget` after ~12 min.
**Trace:** `<run dir>/trace.jsonl`
**Cost:** ~$0.50

## Counters

| Component | Invocations | Steps | Moved | Success | Transitions |
|---|---|---|---|---|---|
| `exitInterior` (decoder, primary) | 12 | 764 | 35 | **4.6 %** | 0 |
| `walkInteriorVision` (vision, escalation) | 19 | 29 | 4 | 13.8 % | 0 |
| Advisor consults | 6 | — | — | — | — |
| **Combined** | — | **793** | **39** | **4.9 %** | 0 |

Phase progression: spawn → Overworld(146,158) → entered Coneria Town
(`Indoors mapId=8 lx=5 ly=28`) → exited to Overworld(144,161) → walked
to (139,161) → re-entered Coneria Town → OutOfBudget.

## Two surprises

### 1. Decoder dropped vs V2.6.5 baseline

V2.6.5 reported 13 % step success on the decoder over 583 steps. V4
measured **4.6 %** on **764** steps in the same map (Coneria Town,
mapId=8). With a larger N the gap is real.

Two possibilities:
- The V2.6.5 13 % figure was a single-run fluke under favourable
  conditions (different entry tile? different NPC positions?).
- The decoder genuinely degrades when invoked repeatedly: BFS finds
  the same wrong path each time, taps a blocked direction, marks the
  tile blocked, recomputes — but the underlying decoded map stays wrong
  so the loop just shifts to another wrong tile.

### 2. Vision-with-advisor outperformed pure vision

V3.2 town-aware standalone vision: 7.8 % on 51 steps.
V4 vision-as-escalation (after advisor sees frame and recommends): 13.8 %
on 29 steps.

The advisor's cardinal hint gives the navigator context the navigator
cannot derive from a single frame. This is the only positive signal
in the run, but the N is too small (29 steps) to be conclusive.

## Net result

Hybrid C **does NOT lift overall step success** above either pure-decoder
or pure-vision baselines. Combined rate of 4.9 % is the lowest of any
configuration tested this session. The advisor-driven vision sub-component
shows promise (14 %) but its N is small.

## Two negative results in a row

| Configuration | Step success | Note |
|---|---|---|
| V2.6.5 decoder pure | 13 % | original baseline, n=583 |
| V3.0 / V3.1 vision pure | 25 % / n/a | small n |
| V3.2 vision pure (town-aware) | 7.8 % | n=51 |
| V4 hybrid C combined | 4.9 % | n=793 |
| V4 decoder alone (in V4 run) | 4.6 % | n=764, **degraded vs V2.6.5** |
| V4 vision (advisor-guided) | 13.8 % | n=29, encouraging but small |

The hypothesis that the right skill mix on top of the current movement
primitives would unlock town traversal is **disconfirmed**. Step success
on Coneria Town consistently floors below 15 %, regardless of architecture.

## Likely deeper bottleneck

The skill design treats "tap a button for 48 frames" as an atomic
movement primitive. But FF1 town overlays have:

- **NPCs that block tiles randomly.** Our skills mark such tiles as
  permanently blocked in the fog, then never retry.
- **Animation/walk-state timing.** A press during a walk animation may
  be ignored. Our 48-frame hold may not consistently land on a single
  tile boundary.
- **Collision corners.** The party can be diagonally adjacent to a
  walkable tile but cardinal-only movement requires going around.
- **Scroll wrap quirks.** mapId=8's localY oscillates 28↔29 even when
  the party visually moves further south, suggesting the "position"
  byte we read isn't the canonical party tile coordinate (a hypothesis
  V2.6.4 raised but never definitively resolved).

These are all *movement primitive* issues, not skill-strategy issues.
No amount of better skill orchestration will fix them.

## Recommended next steps (V5+ — out of scope for this thread)

1. **Movement primitive audit.** Instrument the emulator-session step
   API: log every button press, hold duration, and *exact* RAM positions
   per frame. Compare against a known-good human play recording. This is
   ~50 LOC of instrumentation but would identify whether the timing or
   collision model is wrong.
2. **NPC fog reset.** When the party returns to a previously-blocked tile
   (NPC has moved), clear the block. Currently fog markings are permanent.
3. **Diagonal-aware fallback.** When BFS shows two cardinals as blocked
   but a diagonal as open, sequence cardinal taps to navigate the corner.
4. **Drop the slice 1 DoD criterion entirely** for now. It was framed as
   "≥50 % step success" but the underlying baseline is unstable. A more
   honest criterion: *the agent reaches Garland (mapId 0x7C boss room)*.
   That outcome is binary and unambiguous.

## Cost ledger this session

- Feasibility probe: $0.015
- V3.0 slice 1: ~$0.45
- V3.1 verification: ~$0.45
- V3.2 town-aware: ~$0.55
- V4 hybrid C: ~$0.50
- **Total ≈ $2.00** across 4 live runs.

## Recommendation

**Stop the V3 / V4 thread.** Both architectures are disconfirmed.

PR #99 already documents this — merge as-is when reviewed. Future work
moves to V5: movement primitive instrumentation, then maybe revisit
strategy (decoder vs vision) on a fixed primitive.
