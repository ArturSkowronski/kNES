# V3.2 town-aware navigator — 2026-05-03

**Outcome:** `OutOfBudget` after ~12 min.
**Trace:** `<run dir>/trace.jsonl`
**Cost:** ~$0.55 (20 invocations × ~0.5 sub-LLM calls each + outer turns).

## Counters

| Metric | V3.1 | V3.2 |
|---|---|---|
| Trace events | 19 | 17 |
| `walkInteriorVision` invocations | 19 | 20 |
| Navigator direction responses | 23 | **70** |
| Mechanical taps | 4 | **51** |
| Moved=true | 1 | 4 |
| Transitioned=true | 0 | 0 |
| **Step success** | 25 % | **7.8 %** |
| STUCK rate | 65 % | 0 % |
| EXIT rate | 17 % | **27 %** |
| Fallback fires | n/a | 0 |

## What V3.2 changed

1. **Navigator prompt** rewritten with explicit TOWN/CASTLE/DUNGEON guidance,
   stating "STUCK ONLY if all 4 cardinals impassable; if even one direction
   shows walkable, pick that".
2. **Skill STUCK threshold**: don't honor first STUCK; rotate to a fallback
   cardinal; honor only after 2 consecutive STUCK.

## What happened

The prompt fix dissolved the over-caution: STUCK rate went from 65 % to 0 %.
**But** the trade was that the navigator now also misclassifies indoor frames
as OVERWORLD (returns EXIT) at a 27 % rate (19 of 70 responses), and when it
DOES pick a cardinal, the resulting tap moves the party only **4 of 51 times**
(7.8 %) — *worse than the V2.6.5 decoder baseline of 13 %*.

Phase trace:
```
spawn (146,158) → Indoors(mapId=8, lx=5, ly=29)
                → Indoors(mapId=8, lx=5, ly=28)   (one tile move)
                → Indoors(mapId=8, lx=5, ly=29)   (and back)
                → Overworld(144,161)              ← exit happened OUTSIDE skill
                → (144,164) → (141,164)
```

The party spent the entire interior phase oscillating between two adjacent
tiles. Vision repeatedly picked SOUTH (27×) and NORTH (18×) but neither
direction physically opened. When it eventually returned to the overworld,
that transition occurred between skill invocations — not via `transitioned=true`.

## Diagnosis — why vision-first failed

Single-frame zero-context vision is the wrong tool for tile-precise NES
navigation. Failure modes:

1. **No movement memory.** Each `nextDirection` call sees a frame in isolation.
   The model can't know "I just tried SOUTH and it failed, the dirt path
   actually rises to the EAST". CPP works because the conversation accumulates
   frames + reasoning across turns. Our `WalkInteriorVision` deliberately
   resets context per call.
2. **Pixel-tile mapping is non-obvious.** A "walkable-looking" 16×16 tile may
   in fact be flagged impassable in FF1's collision table (e.g. the lower
   half of a building, water-coloured floor). Vision can't see the collision
   bit; it only sees rendered pixels.
3. **Two-cardinal trap.** Vision oscillates SOUTH ↔ NORTH because both
   directions look "open" from a centred viewport, but the actual walkable
   path is EAST/WEST one tile away.
4. **Prompt is a knob with two failure modes**: cautious → STUCK loops
   (V3.1); eager → wrong-direction loops (V3.2). There may be no calibration
   that gets >50 % on FF1 towns from single-frame vision alone.

## Honest assessment vs slice 1 DoD

- ❌ Step success ≥ 50 %: missed (7.8 %).
- ❌ Exit via `walkInteriorVision`: missed (0 transitions).
- ✅ Architecture ships clean (compiles, tests pass, no regressions).
- ✅ V3.1 hard-override is a permanent improvement regardless of vision outcome.

The V3.0 vision-first hypothesis as currently architected is **disconfirmed**
for FF1 towns. Could it work with multimodal context, retrieval-augmented
movement memory, or different vision model? Possibly. Out of scope for slice 1.

## Recommended next steps

Three realistic forks:

**A — Hybrid C (advisor-only screenshots).** Decoder for primary movement
(faulty but deterministic baseline), advisor sees screenshots on STUCK/idle
and gives a textual cardinal hint. Best risk-adjusted return: keeps the
13 % decoder baseline as floor, vision used only as planning aid. ~50 LOC.

**B — Multimodal executor.** Give the Koog executor itself the screenshot
so it can reason about overworld + interior with the same eyes and accumulate
across turns. Requires Koog 0.6.1 multimodal investigation. Larger change.

**C — Accept the negative result and ship.** Open PR `ff1-agent-v2-4` with
V2.5 + V2.6 + V3.0 (infra) + V3.1 (override fix). V3.2 stays as evidence
that vision-first single-frame doesn't outperform the decoder; document the
finding. No more live runs in this thread.

Recommend **C → A** sequence: ship clean stop, then iterate with hybrid.
Don't push more vision-prompt epicycles in this session — diminishing returns
confirmed across two attempts.

## Cost ledger this run
- 70 navigator vision calls × ~$0.005 = ~$0.35
- 5 advisor calls × ~$0.05 = ~$0.25 (Opus called when phase Title/recovery)
- 17 executor turns × ~$0.01 = ~$0.17
- **Total ≈ $0.55**

**Cumulative session spend: ~$1.50** across feasibility probe + 3 live runs.
