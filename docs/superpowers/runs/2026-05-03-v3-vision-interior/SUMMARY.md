# V3.0 slice 1 live run — 2026-05-03

**Outcome:** `OutOfBudget` after 12 m 42 s.
**Trace:** `2026-05-03T14-37-49.194343Z/trace.jsonl`
**Cost estimate:** ~$0.50 (26 vision-navigator calls + ~12 advisor calls + executor turns).

## TL;DR

The new V3.0 stack (`AnthropicVisionInteriorNavigator` + `WalkInteriorVision`)
is **wired correctly and behaves as designed**. The run never reached a real
interior, so neither DoD criterion (≥50% step success in `mapId=8`; party exits
interior via vision) was actually exercised on a real interior map. Instead the
run uncovered a **pre-existing phase-classifier bug** carried over from V2.5
that loops the agent between misclassified-Overworld and false-Indoors.

## What V3.0 slice 1 proved

1. **Tool wiring**: `walkInteriorVision` `@Tool` was selected by the executor
   in the (false) Indoors phase 26 times. Skill correctly returned to the outer
   loop without crashing.
2. **Navigator JSON parse**: every navigator response parsed; no `UNCLEAR`s in
   the trace despite varied frame content.
3. **Navigator semantics**: when shown an overworld frame, navigator correctly
   returned `EXIT` (28 of 30 `dir` entries). When the screen *did* look like
   an indoor space (RAM states with `lx=9, ly=16/17` mid-transition), navigator
   returned `SOUTH` and the resulting tap moved the party (3 of 4 mechanical
   taps reported `moved=true`).
4. **Hint feedback**: one trace entry shows `hintBlocked=SOUTH` correctly fed
   back after the only failed tap.

## What it did NOT prove

- DoD criterion 4: ≥50% step success in `mapId=8`. **Not measured.** Of the
  4 mechanical taps that did fire, 3 moved (75 %), but those were on the
  overworld with stale `localX/Y`, not inside Coneria Town.
- DoD criterion 5: party exits interior via `walkInteriorVision`. **Not
  measured.** Party never entered a real interior (`mapId` was `0` throughout).

Cannot declare the V3.0 hypothesis confirmed or refuted from this run alone.

## Pre-existing bug uncovered (V2.5 phase classifier)

```
phases timeline (relevant slice)
  ...
  executor Overworld(x=144, y=155)               tools=42 first=walkOverworldTo
  advisor  Indoors(mapId=0, localX=9, localY=17) tools= 0
  executor Indoors(mapId=0, localX=9, localY=17) tools=53 first=walkInteriorVision  ← stuck
  advisor  Overworld(x=144, y=154)               tools= 0
  executor Overworld(x=144, y=154)               tools=41 first=walkOverworldTo
  advisor  Indoors(mapId=0, localX=9, localY=16) tools= 0
  executor Indoors(mapId=0, localX=9, localY=17) tools= 6 first=walkInteriorVision
  ... oscillation continues until budget exhausted
```

Cause analysis:
- `currentMapId == 0` is "overworld N/A" per `ff1.json`.
- `locationType == 0` (overworld per profile + datacrystal).
- But `localX/localY` retain stale values from a previous walkOverworldTo
  abort — datacrystal RAM map calls 0x0029/0x002A "Non-world map position" but
  FF1 doesn't reset them on overworld entry.
- The V2.5.7 RAM hard-override fires only when `locType==0 AND lx==0 AND ly==0`.
  With stale `lx/ly` it falls through to the vision phase classifier (Haiku 4.5),
  which evidently classifies the frame as INDOORS — possibly because the
  walkOverworldTo abort left the camera mid-scroll on a tile that *visually*
  looked like a town entrance.

Phase classifier fights the navigator: navigator (Sonnet 4.6) sees the same
frame and consistently says EXIT (= "this is overworld"). Two vision models
disagree; the upstream classifier wins because the outer loop trusts it.

## Fix (V3.1, small)

Strengthen the RAM hard-override. Two cheap options:

**A.** `locType == 0 && currentMapId == 0` → Overworld. `mapId=0` is the
canonical "no interior loaded" signal; this is unambiguous.

**B.** `locType == 0` alone → Overworld. Per datacrystal RAM map, 0x000D is
*the* outside/inside flag (0 / 0xD1). Stricter than A but matches docs.

Either fix would have prevented the entire 12-minute oscillation. Recommend
A — keeps locType as a primary signal and uses mapId as a tie-breaker for the
edge case where locType=0 but the engine hasn't fully unloaded a previous map.

## Carry-over diagnosis (NOT in V3.1 slice)

- The `walkOverworldTo` "interior abort" signal currently fires on
  `localX != 0 || localY != 0` (V2.5.2). After the abort, those bytes stay
  non-zero into subsequent overworld walking — feeding the bug above. Could be
  reset by skill teardown or by extending hard-override.

## Action items

- [ ] V3.1: extend RAM hard-override (option A above).
- [ ] V3.2 re-run: same agent, same budget, with hard-override fix → real
      mapId=8 / mapId=24 step-success measurement against the V2.6.5 13 %
      baseline.
- [ ] Optional: PR `ff1-agent-v2-4` after V3.1 ships, since slice 1 alone
      ships infrastructure but no measurable behavior change.

## Cost ledger

- Vision-classifier (Haiku, per outer turn): 28 events × ~$0.002 = ~$0.06
- Vision-navigator (Sonnet, 26 invocations × ~30 dir calls inside): ~$0.10 –
  $0.20
- Executor (Sonnet 4.5/Haiku 4.5 per outer turn, 26 turns): ~$0.20
- Advisor (Opus on uncertain phases, 5 calls): ~$0.10
- **Total ≈ $0.45 – $0.55**, in budget.
