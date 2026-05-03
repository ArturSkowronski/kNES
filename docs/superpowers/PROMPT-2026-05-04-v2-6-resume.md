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

## Your task — verify, then decide

Do NOT immediately code. Do this analysis first:

1. **Enable `dump mapId=8 to verify scroll-offset hypothesis` in
   `OverworldDumpTest.kt`** (currently `config(enabled = false)`). Run it.
   It prints the byte/classify at (5, 28), (4, 11), (10, 32), (13, 35),
   (12, 18) plus a 0..47 × 0..47 glyph dump. Tell me:
   - Are (5, 28) and (4, 11) passable in our decoded mapId=8?
   - Are (13, 35) and (12, 18) passable?
   - Where is the actual playable area in mapId=8?
   - Where does the V2.4.4 "exit at (10, 32)" land — is it a real exit tile or interior?

2. **Cross-check with FF1 community RAM map.** Specifically, find authoritative
   FF1 disassembly notes (Disch's FF1 disassembly is canonical) and verify
   whether RAM 0x0029/0x002A is "party tile" or "scroll offset". Spend 10
   minutes max on this — if not conclusive, treat as inconclusive.

3. **Run the dump tests at decision time.** Based on findings, recommend:
   - **Revert V2.6.4** (raw localX/Y = party tile) → mapId=24 stuck remains.
   - **Keep V2.6.4** (scroll offset) → need to handle NPC-blocking and
     refine coord arithmetic.
   - **Hybrid** — different offset semantics per map type (towns vs castles
     vs dungeons). FF1 may use both schemes.

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
