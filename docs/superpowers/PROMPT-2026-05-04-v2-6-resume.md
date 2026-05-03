# Resume prompt — V2.6.5 → V2.6.6+ — interior coord semantics still uncertain

You're a fresh agent picking up the FF1 Koog Agent project after V2.6.5.
Branch `ff1-agent-v2-4` of `/Users/askowronski/Priv/kNES-ff1-agent-v2`.
ANTHROPIC_API_KEY required for live runs.

## Read first (in order)

1. `docs/superpowers/STATE-2026-05-03-v2-6.md` — full session state up to V2.6.3.
2. `docs/superpowers/runs/2026-05-03-v2-6-4-scrolloffset/SUMMARY.md` (if you write one) — V2.6.4 evidence: scroll-offset hypothesis introduced.
3. `docs/superpowers/runs/2026-05-03-v2-6-5-stepstrace/2026-05-03T13-28-12.424398Z/trace.jsonl` — V2.6.5 per-step trace data.
4. `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt` — V2.6.4/V2.6.5 changes.
5. `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMap.kt` — `readFullMapView` (V2.6.2).
6. `knes-agent/src/test/kotlin/knes/agent/perception/OverworldDumpTest.kt` — disabled diagnostic tests; enable as needed.

## Where we are

V2.6 stack made huge progress this session: vision phase classifier, full-map
overworld+interior BFS, hard-impassable TOWN/CASTLE rule, RAM hard-override,
fog defensive marking, classifier coverage gap closed, prompt corrected,
south-edge probe depth bounded, **interior coord semantics shifted**.

V2.6.4 introduced the hypothesis that RAM `localX/localY` (0x0029/0x002A)
is the **scroll offset** (top-left of 16×15 NES viewport), not the party's
tile. Party actual map tile = `(localX + 8, localY + 7)`. Verified for
mapId=24: RAM (3, 2) → (11, 9) lands on the throne-room corridor floor,
which matches "party in main hall" semantics. Live V2.6.4 run: party
moved within mapId=8 from RAM (5, 28) to (4, 11) — first real interior
progression in V2.x.

## What's verified ambiguous in V2.6.5

V2.6.5 added `exitInterior.step` per-step trace. Live run produced
**507 no-moves vs 76 moves** (87% of step attempts physical-blocked) and
**39 direction-coord inconsistencies** — most concerning: `dir=W` (LEFT
button) repeatedly produced `Y-1` (a north move in our coord system),
e.g. `from=(13,36) dir=W → after=(13,35)`. One case showed a +7 jump in Y.

Two interpretations:

**(A) Scroll-offset hypothesis is right but party gets pushed by NPCs.**
Coneria Town (mapId=8) is a town outdoor map with random-walking NPCs.
NPC adjacency may be blocking moves and causing scroll perturbations.

**(B) Scroll-offset hypothesis is wrong.** Pre-V2.6.4 (localX/Y = party
tile directly) worked for mapId=8 in V2.4.4 evidence (17-step path found,
party never moved due to other timing bugs). V2.6.4 may have introduced
correlation-not-causation: party did move from (5, 28) to (4, 11) under
new BFS, but the path may have been *pseudo-random* through ROM-decoded
bytes that happened to align with playable terrain by coincidence.

## Verification result (executed at end of last session)

Ran the mapId=8 dump test. Results decisive but contradictory:

| RAM coord | Raw interpretation | Scroll-offset interpretation |
|---|---|---|
| (5, 28) (V2.4.4 spawn) | **passable** ✓ (0x31 floor) | (13, 35) **wall** ✗ |
| (4, 11) (V2.6.4 stuck) | **wall** ✗ (0x30) | (12, 18) **STAIRS** ✓ (0x44) |

Neither interpretation works for both positions. THIRD theory now leading:

**Theory C: our decoded mapId=8 is NOT the same map the game actually
plays.** The dump shows mapId=8 as a castle-like layout (walls 0x30,
floors 0x31, STAIRS 0x44, internal rooms, south-edge openings at y=33/34).
This looks more like Coneria Castle than Coneria Town outdoor. FF1 may
have a sub-map ID layered on top of `currentMapId` (RAM 0x0048) that
selects the actual playable map. Our `InteriorMapLoader.load(8)` always
decodes the same ROM section — but the in-game map may differ depending
on which sub-room party is in.

If C is correct, BFS finds paths through OUR decoded bytes, party walks
in real game by coincidence (when our decoded passable matches the real
passable). 13% step-success rate (76/583 in V2.6.5 trace) supports this.

## Your task — verify Theory C, then decide

Do NOT code blindly. Do this analysis first:

1. **Find FF1 sub-map ID byte.** Look at FF1 RAM map (Disch disassembly,
   datacrystal FF1 RAM page). There may be a SECOND map ID byte (e.g.
   0x004A or somewhere) that distinguishes "Coneria Castle interior" from
   "weapon shop" from "throne room sub-map" — all sharing currentMapId=8.
   Profile `knes-debug/src/main/resources/profiles/ff1.json` doesn't track it.

2. **Capture screenshot at first Indoors(mapId=8) frame.** The trace doesn't
   record images. Add screenshot capture to AgentSession on first phase
   change to Indoors. Compare visually with our decoded mapId=8 dump —
   confirm whether party is in the same map our loader thinks.

3. **Verify InteriorMapLoader pointer table.** ROM offset 0x10010, 128 maps,
   2 bytes each. Maybe FF1 uses a different table for sub-maps vs main
   maps; or the bank-resolution code in `load()` is wrong for mapId>=8.

4. **Decide based on evidence:**
   - If our decoded mapId=8 ≠ real map: fix the loader / find correct table.
   - If decoded map IS correct but RAM coords need a different interpretation:
     the screenshot will show party position visually; reverse-engineer.
   - If both correct but transitions happen invisibly: track sub-map ID byte.

## Constraints / preferences

- User wants verification BEFORE code. "weryfikuj zamiast zgadywać".
- Polish conversation preferred (see memory).
- Don't burn live runs unless you have a falsifiable hypothesis. Each run
  costs ~$0.50–$1 and 12 minutes wall-clock.
- 22 commits already on `ff1-agent-v2-4` since master. Ready to PR but blocker
  remains.

## Useful CLI

```bash
# Run agent live
ANTHROPIC_API_KEY=... \
  KNES_RUN_DIR=$(pwd)/docs/superpowers/runs/<date>-<topic> \
  ./gradlew :knes-agent:run \
    --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes --profile=ff1 \
            --max-skill-invocations=40 --wall-clock-cap-seconds=720"

# Run a specific test (e.g. enable dump test temporarily, then run):
./gradlew :knes-agent:test --tests "knes.agent.perception.OverworldDumpTest" --rerun-tasks -i

# Trace analysis:
python3 -c "
import json
events = [json.loads(l) for l in open('docs/superpowers/runs/.../trace.jsonl')]
for e in events:
    print(e.get('role'), e.get('phase'), e.get('toolCalls', []))"
```

## Definition of done for this milestone

V2.6 closes when:
- Interior coord semantics are confirmed (localX/Y = ?).
- ExitInterior achieves consistent multi-tile movement in mapId=8 with
  >50% steps successful (currently 13%).
- Party reaches Indoors → Overworld at least once via south-edge or
  DOOR/STAIRS/WARP without LLM `pressStartUntilOverworld` hack.

V3.0 long-arc target unchanged: `Outcome.AtGarlandBattle` (= `Battle.enemyId == 0x7C`,
i.e. boss room of Chaos Shrine, an interior dungeon).
