# Fix B research: canonical FF1 RAM addresses (Disch / Entroper disassembly)

Source: [Entroper FF1Disassembly](https://github.com/Entroper/FF1Disassembly), file
`Final Fantasy Disassembly/variables.inc` and `bank_0F.asm`.

## Canonical zero-page identifiers

| Addr | Symbol | Description |
|---|---|---|
| `$0027` | `ow_scroll_x` | X scroll of overworld in tiles |
| `$0028` | `ow_scroll_y` | Y scroll of overworld in tiles |
| `$0029` | `sm_scroll_x` | X scroll of **standard map** (town/castle/dungeon) view |
| `$002A` | `sm_scroll_y` | Y scroll of standard map view |
| `$002D` | **`mapflags`** | bit 0 set = in standard map; bit 1 = column-drawing mode |
| `$0033` | `facing` | 1=R 2=L 4=D 8=U |
| `$0042` | `vehicle` | 1=walk 2=canoe 4=ship 8=airship |
| `$0048` | **`cur_map`** | canonical current standard-map ID |
| `$0049` | `cur_tileset` | current tileset (varies per map kind) |
| `$004A` | `cur_mapobj` | counter for updating map objects |
| `$0053` | `ow_tile` | last overworld tile we stood on |
| `$0068` | **`sm_player_x`** | **party X on standard map** (NPC collision check) |
| `$0069` | **`sm_player_y`** | party Y on standard map |

`$000D` per datacrystal is "0=outside / 0xD1=inside" — but **it's not the canonical
indoor flag**. `mapflags & 0x01` is. The 0xD1 value likely reflects something more
specific (room indicator? tile-prop hash?) — we don't need it.

## Standard map decoder

Per `bank_0F.asm:4124` (`LoadStandardMap` routine):

```asm
LDA cur_map           ; get current map ID
ASL A                 ; double, throw in X
TAX
LDA lut_SMPtrTbl, X   ; lo byte of pointer
STA tmp
LDA lut_SMPtrTbl+1, X ; hi byte
TAY
AND #$3F              ; CPU addr in $8000-$FFFF
ORA #$80
STA tmp+1
TYA
ROL A; ROL A; ROL A
AND #$03              ; bank index relative to BANK_STANDARDMAPS
ORA #BANK_STANDARDMAPS
STA tmp+5
```

`BANK_STANDARDMAPS = $04` (Constants.inc). One unified pointer table for all
non-overworld maps (towns, castles, dungeons) at start of bank 4 = file offset
`0x10010`.

**Our `InteriorMapLoader` (knes-agent) is byte-for-byte correct** vs this routine.
The "wrong table for towns" hypothesis was wrong — there's only one table.

## What we got wrong (root cause of V2.4-V2.6.5)

Our profile maps:
```json
"localX": {"address": "0x0029", ...},   // says "Non-world map X position"
"localY": {"address": "0x002A", ...},   // says "Non-world map Y position"
```

But `$0029/$002A` is `sm_scroll_x/y` — the **scroll** position of the view,
**not the party tile**. Pathfinder asks `interiorMap.tileAt(localX, localY)` and
gets the tile at the scroll origin (top-left of viewport), not at the party's
foot. Party can be anywhere in the 16×15 visible viewport.

V2.4.4 evidence: spawn=(5, 28) passable in decoder, party LOOKS like it's at
correct tile only when the scroll happens to coincide with party tile. V2.6.4
saw "+8/+7 fixes one case but not another" — that's because the scroll offset
is **dynamic** (varies with party movement), not fixed.

Real party position is `$0068 = sm_player_x`, `$0069 = sm_player_y`. Comment
in variables.inc says "**Only used for NPC collision detection**" — meaning
this is what the engine consults to determine if the party tile collides
with an NPC. The standard map renderer uses sm_scroll for camera, but
sm_player_x/y is the source of truth for party tile coords.

V5.3 RAM-diff confirmed: after entering Coneria interior with raw N×6/W×1/UP,
`$29=4, $2A=25` (scroll), `$68=11, $69=32` (party). Tap RIGHT×2 →
`$29=6, $2A=25` (Δ=+2/+0), `$68=13, $69=32` (Δ=+2/+0). Both move +2 because
the party-screen relationship stays fixed when the camera follows the party.

## What `mapId=8` actually is

InteriorMapLoader.load(8) decodes a **castle-shaped layout** (walls 0x30,
floors 0x31, STAIRS 0x44). V2.6.x evidence showed live frames as "Coneria
Castle courtyard". Both consistent — `mapId=8` is **most likely Coneria Castle
1F or similar castle map**, NOT Coneria Town.

The agent has been entering Coneria Castle (not Town) all along when it
believed it reached Coneria Town. We don't have a verified ROM mapId for
Coneria Town yet — needs empirical sweep.

## Minimal-diff fix proposal (V5.5+)

### Phase 1 — profile + RamObserver (low risk, high payoff)

```diff
--- a/knes-debug/src/main/resources/profiles/ff1.json
+++ b/knes-debug/src/main/resources/profiles/ff1.json
-    "locationType": {"address": "0x000D", "description": "..."},
+    "locationType": {"address": "0x000D", "description": "$D1=castle/dgn-only flag (NOT canonical indoor, see mapflags)"},
+    "mapflags":     {"address": "0x002D", "description": "bit 0 = in standard map (town/castle/dungeon)"},
-    "localX": {"address": "0x0029", ...},
-    "localY": {"address": "0x002A", ...},
+    "smScrollX":  {"address": "0x0029", "description": "Standard-map scroll X (camera, NOT party)"},
+    "smScrollY":  {"address": "0x002A", "description": "Standard-map scroll Y"},
+    "smPlayerX":  {"address": "0x0068", "description": "Party X on standard map (canonical)"},
+    "smPlayerY":  {"address": "0x0069", "description": "Party Y on standard map"},
+    "facing":     {"address": "0x0033", "description": "1=R 2=L 4=D 8=U"},
+    "vehicle":    {"address": "0x0042", "description": "1=walk 2=canoe 4=ship 8=airship"},
+    "curTileset": {"address": "0x0049", "description": "Current tileset id"},
+    "owTile":     {"address": "0x0053", "description": "Last overworld tile we stood on"},
```

Keep `localX/Y` as aliases pointing to $0029/$002A for backward compatibility,
but mark deprecated.

### Phase 2 — RamObserver dispatch on mapflags

```diff
-val locType = ram["locationType"] ?: 0
-if (partyCreated && (locType == LOCATION_TYPE_INDOORS || onLocalMap)) {
-    return FfPhase.Indoors(mapId, localX, localY, isTown = locType != 0xD1)
-}
+val mapflags = ram["mapflags"] ?: 0
+val inStandardMap = (mapflags and 0x01) != 0
+if (partyCreated && inStandardMap) {
+    return FfPhase.Indoors(
+        mapId = ram["currentMapId"] ?: -1,
+        localX = ram["smPlayerX"] ?: 0,    // ← canonical party tile (was $29 scroll!)
+        localY = ram["smPlayerY"] ?: 0,
+        isTown = (ram["locationType"] ?: 0) != 0xD1,
+    )
+}
```

### Phase 3 — find Coneria Town's true mapId

Empirical sweep: walk overworld until we visually verify "town huts visible"
(distinct from castle), read `$0048`. Update any code that hardcodes 8 = town.

### Out of scope for this commit

- InteriorPathfinder fixes (depend on Phase 1+2 being merged first)
- Coneria8VisualDiffTest renaming/repurposing (its assumption "8=town" is
  wrong — depending on Phase 3 outcome we either delete or rename)
- Tileset-aware tile classification ($0049 indicates if we're in town vs
  castle vs dungeon — different tile semantics; future work)
