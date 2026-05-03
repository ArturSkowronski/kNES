# V5 movement-primitive audit — 2026-05-03

Disabled-by-default test `MovementAuditTest.kt` boots FF1, walks UP into the
Coneria peninsula until `locationType != 0`, then captures **per-frame RAM**
during a 200-frame DOWN-hold and a subsequent 200-frame UP-hold. Goal: stop
guessing about coord semantics and timing — get ground truth.

CSV artefacts: `docs/superpowers/notes/movement-audit-2026-05-03/`.

## Key findings

### 1. localY = party tile, NOT scroll offset (V2.6.4 hypothesis disconfirmed)

`DOWN` hold inside Coneria Castle (`mapId=24`) increments `localY` cleanly:

```
frame  locY  scrolling
   0    0    1   (engine setting up)
  11    0    1   (locType briefly flickers to 0x01 then back)
  27    1    1   (first tile move at frame 27)
  43    2    1
  59    3    1
  75    4    1
  91    5    1
 107    6    1
 123    7    1
 139    8    1
 155    9    1
 171   10    1
 187   11    0   (movement settles, scrolling drops)
```

11 tile moves in 200 frames = 1 tile per ~16 frames. Cleanly monotonic.
**`localY` IS the party tile in the canonical interior map.** Old V2.6.4
scroll-offset hypothesis was wrong.

### 2. Movement primitive is clean inside the right interior

When the underlying decoder is correct (mapId=24 = Coneria Castle), holding
DOWN for ~16 frames moves party reliably. Our skill's 48-frame-per-tile
parameter (V2.4.5 tuning) is **3× more than needed** for castles — this
explains some of the 4–13% "step success" numbers being low: each step
holds the button so long that the party potentially walks through one tile
and into the next blocked tile, then the skill records "didn't move" because
RAM was sampled during a moment when party was in fact stationary at the
new tile.

### 3. Sub-map boundary produces RAM spaghetti

`UP` hold from the same starting point:

```
frame  mapId  locY
   0     24    11   (in castle)
  43      8    11   (mapId flips to 8 — Coneria Town! mid-walk)
  50      8    25   (localY jumps)
  51      8    19
  52      8    14
  53      8    11   (settles)
... no further transitions in remaining ~150 frames
```

Two observations:
- Sub-map transitions happen **mid-frame** without warning. `mapId` is the
  signal but it changes as a side-effect of walking off a map edge, not as
  a deliberate teleport tile.
- The 4-frame `localY` ride (25 → 19 → 14 → 11) on transition is the
  engine re-centring the camera on the new map. Position semantics during
  these frames are not stable.

### 4. mapId=8 (Coneria Town) decoder remains the real bug

Once the party transitioned to mapId=8 at frame 43 of the UP run, it
walked at most a few tiles then froze. This is consistent with V2.6.5
"Theory C": `InteriorMapLoader.load(8)` decodes a different ROM section
than the game actually plays for Coneria Town. The party is on a real
map but our BFS is searching a phantom one.

V3.0/V3.2 vision-first ran into the same wall: even when vision picked
the right cardinal, the underlying tile collision (game's, not ours)
blocked the move 87 % of the time because we were giving directions
based on the wrong map.

V4 hybrid C ran into the same wall × 2 (decoder failed; vision failed
on the same map).

## Implication for next steps

The V3/V4 "search/architecture" lever is exhausted. The remaining lever
is **fix the decoder for mapId=8** — which is exactly what V2.6.6 was
about to investigate before the V3 pivot.

Three candidate paths:

**A. Brute-force ROM table audit.** Open the FF1 ROM in a hex viewer
at offsets `0x10010 + 8*2` (the mapId=8 pointer entry). Verify the
pointer + bank resolution by hand; cross-reference with Disch's
disassembly. ~2 h of careful manual work, no LLM cost.

**B. Compare decoded mapId=8 with rendered frame.** Capture a
screenshot of Coneria Town from a known position; render our decoded
mapId=8 ASCII to the same scale; visually identify which tiles
disagree. The mismatch fingerprint will hint at whether it is a
pointer-table-off-by-one, a wrong-bank-selection, or a decoder
bug specific to RLE patterns in town data.

**C. Replace decoder with screen-derived map.** Skip ROM decoding;
build the tile collision map from rendered frames at boot
(deterministic — same ROM same frames). Slower but bypasses the
ROM-format reverse-engineering problem entirely.

Recommend **B** as next research step: cheap (one screenshot + one
ASCII dump), gives immediate visual evidence whether decoder is
"slightly off" or "completely wrong table".

## Test code

`knes-agent/src/test/kotlin/knes/agent/perception/MovementAuditTest.kt`
— disabled by default (`enabled = false && canRun`). Flip to re-run.

## Cost

Zero. Pure offline test, no LLM calls.
