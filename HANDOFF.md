# FF1 Koog Agent — Handoff (Spec 4 merged + Spec 5 WIP, 2026-05-08)

**Branch HEAD:** `5bad78c` on `ff1-buy-and-equip-coneria`.
**Open PR:** #120 (Spec 5 WIP) targeting `ff1-bridge-and-walk-to-tof`.
**Merged this session:** PR #119 (Spec 4 — interior self-discovery vision pipeline).
**Tests:** 333 unit (31 new pass · 3 baseline failures unchanged · 7 gated skipped).

## Stack state

```
master
  └─ ff1-grind-strategy           (PR #116 open — Spec 1)
       └─ ff1-buy-at-shop         (no PR — Spec 2 + savestate cleanup)
            └─ ff1-bridge-and-walk-to-tof   (no PR — Spec 3a)
                 └─ Spec 4 merged here via PR #119 ← THIS SESSION
                      └─ ff1-buy-and-equip-coneria  (PR #120 WIP — Spec 5, THIS SESSION)
```

## What this session shipped

### Spec 4 — Interior self-discovery vision scan (MERGED PR #119)

Spec: `docs/superpowers/specs/2026-05-08-ff1-interior-self-discovery-design.md`
Plan: `docs/superpowers/plans/2026-05-08-ff1-interior-self-discovery.md`

Replaces brittle cold-start `discoverWeaponShop` probe with goal-aware `InteriorExplorer` composing existing `WalkInteriorVision` (unchanged) + new `InteriorScanner` (two-pass vision: Gemini Pass 1 candidate scan + Gemini Pro Pass 2 verify) + new `FrameChangeDetector` (PPU OAM with pixel-hash fallback).

18 commits (12 implementation + 6 empirical-driven fixes from real ROM runs):

- `LandmarkKind` extended with `CHEST`, `SIGN`, `DIALOGUE_TRIGGER`
- `HaikuConsult` interface: `scanInteriorCandidates` + `verifyLandmark` + `AdviceResponse` (Spec 5)
- AnthropicHaikuConsult: real Pass 1 + Opus advisor; GeminiVisionConsult: real Pass 1+2
- `FrameChangeDetector`: OAM primary + per-byte FNV-1a pixel-hash fallback
- `InteriorScanner`: scanCandidates + verifyAndPersist + kind-string mapping
- `InteriorExplorer`: full step loop with all error paths (StuckBailout/NotFoundCapReached/EncounterTriggered/Found)
- Real adapters (`RealInteriorEmulatorState`, `RealWalkInteriorVisionAdapter`) — note OAM not exposed by emulator → fallback engaged automatically per spec §10.2
- Wired into `OutfitBootPhase.discoverWeaponShop`
- Telemetry: 7 new trace events (`interior_scan_*` + `interior_explore_outcome`)

Empirical-fix commits exposed by real runs:
- pixel-hash detected only multi-byte changes (avg-truncation bug) → fixed to per-byte FNV-1a
- `claude-opus-4-0` invalid → bumped `Opus_4` → `Opus_4_5` (max in koog 0.6.1)
- `GeminiVisionConsult.scanInteriorCandidates` was Task 2 stub → real impl wired
- `pass1-degraded` cap 3→10→30
- walk-stuck cap 3→8→30, capSteps 50→100→200
- `WalkInteriorVision` navigator Sonnet 4.6 → Opus 4.5

### Spec 5 — Multi-mapId nav for buy+equip (PR #120 WIP)

Branch: `ff1-buy-and-equip-coneria` cut from Spec 4 merge.

Goal: agent fizycznie wchodzi do weapon shop sub-interior, kupuje broń, ekwipuje. Spec 4 odkrył że BuyAtShop wymaga `landmark.mapId == currentMapId` AND party fizycznie stoi przed shopkeeperem — Spec 4 explorer scanuje ale nie navguje deliberately do shop interior.

10 commits dwóch wariantów:

**v1: hardcoded sweep** — `EnterConeriaWeaponShop` skill chodzi N+W per Mike's RPG Center map, sweep horizontal po hit wall, próbuje N at each X. Plus post-enter Pass 1 vision scan + walk-to-keeper.

Empiryczny rezultat: party trafia w mapId=24 = Coneria CASTLE entrance hall (run 16 screenshot dump confirmed pillared corridor), nie weapon shop. Hardcoded sweep nie znajdzie shop door bez znajomości faktycznego layout.

**v2: Opus 4.5 advisor-driven** — `HaikuConsult.adviseShopApproach` interface; AnthropicHaikuConsult uses `claude-opus-4-5-20251101` directly via HTTP. Loop in `runOutfitBootPhase` calls advisor per iter z context (party position + Mike's map + stuck-count hint). Verify-and-retry: po mapId change, run Pass 1 — jeśli no shopkeeper, tap S 15× to exit, continue advisor loop. Dedicated `outfitAdvisor` ctor param (always Anthropic, bypasses KNES_VISION dispatch).

Empiryczny rezultat: Opus reasoning solid w tekście (run 21 trace ma rich reasoning per iter), stuck-detection works, verify-and-retry fires. ALE: run 22 entered castle ponownie po Left detour. Run 23: advisor stuck at (11, 23) iter 14-29 wszystkie Left, never Right. Agent nadal NIE kupił weapons.

## Empirical investment

- Spec 4 validation: ~$18 across 6 runs (run 4-9)
- Spec 5 validation: ~$45 across 14 runs (run 10-23)
- **Total: ~$63 empirical validation** of Spec 4 + Spec 5 architecture

Vision pipeline (Pass 1 + Pass 2) jest empirycznie potwierdzony. Multi-mapId navigation jest fundamentalnie trudna z aktualnym LLM spatial reasoning na NES pixel art.

## What does NOT yet work

- **Buy + equip e2e in Coneria**: agent identifies weapon shop position in text reasoning, ale spatial nav przez plaza zawodzi systematycznie. Czasami trafia w castle gate, czasami zacina się przy ścianach plazy mid-walk.
- Both v1 (hardcoded sweep) i v2 (Opus advisor) failed — różne failure modes ale same outcome `weaponsBought=0`.

## Realistyczne ścieżki naprzód (Spec 6+ territory)

1. **ROM-based interior pathfinder** — A* na `InteriorMapLoader` decoded tile data + door tile detection przez CHR-ROM analysis. Deterministic, generic, biggest effort. Eliminuje LLM cost dla nav entirely.
2. **FF1 disasm vendoring** — extract sub-shop mapIds + door world coords z Disch FF1 disassembly. Hardcode preseed landmarks dla wszystkich miast/buildings. Deterministic, fast unblock.
3. **Manualne gameplay-recorded path** — record exact tap sequence z Coneria spawn do weapon shop keeper przez human gameplay. Hardcode jako EnterConeriaWeaponShop. Brittle, Coneria-only, ale natychmiastowe. ~30 LoC.

Recommendation: opcja **3** najszybciej unblockuje buy+equip e2e (~30 min pracy, $0 run cost dla manual recording, $3 dla validation run). Opcja **2** scaling later (Pravoka, Elfheim, etc).

## Run command (current state, KNES_VISION=gemini-pro)

```bash
rm -f ~/.knes/ff1-*.json
cat > ~/.knes/ff1-landmarks.json <<'JSON'
{"version":1,"landmarks":[
  {"id":"interior_entry_8_146_152","kind":"TOWN_ENTRY","worldX":146,"worldY":152,
   "mapIdInterior":8,"visited":true,"note":"coneria-town entry","discoveredRunId":"preseed"},
  {"id":"weapon_shopkeeper_preseed","kind":"NPC_SHOPKEEPER","visited":false,
   "note":"kind=weapon; preseed-mike-rpg-map; items=staff:5,dagger:5,nunchuck:10,rapier:10,hammer:10",
   "discoveredRunId":"preseed"}
]}
JSON

KNES_VISION=gemini-pro ANTHROPIC_API_KEY=... GEMINI_API_KEY=... \
  ./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
    --wall-clock-cap-seconds=600 --cost-cap-usd=5.0 --max-skill-invocations=120"
```

Watch traces:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E 'boot_advisor|boot_purchase|boot_outfit_summary' "$LATEST/trace.jsonl"
ls /tmp/spec5-postenter*.png  # diagnostic dumps
```

## Repo paths

- Spec 4 design: `docs/superpowers/specs/2026-05-08-ff1-interior-self-discovery-design.md`
- Spec 4 plan: `docs/superpowers/plans/2026-05-08-ff1-interior-self-discovery.md`
- Spec 5 (no spec doc — POC implementation directly): `knes-agent/src/main/kotlin/knes/agent/skills/EnterConeriaWeaponShop.kt`
- Empirical observations: trace.jsonl per run in `~/.knes/runs/`

## Lessons / observations

1. **Vision pipeline (Pass 1+2) działa empirycznie** — Gemini Pro reliably identifies real NES sprites (`stairs_up`, `exit_tile`, `EXIT_TILE` confirmed). Pixel-hash fallback engaged automatically (PPU OAM not exposed by emulator).

2. **LLM spatial reasoning na NES pixel art jest słaba** — Opus 4.5 produces solid text reasoning but cannot reliably navigate from screenshot. NES pixel art (256x240, 8-bit colormap) lacks signal for Opus to identify door tiles vs walls vs floor.

3. **Hardcoded sweep nie skaluje się bez disasm knowledge** — guessing X offsets reliably lands in castle gate (top center of plaza), nie w shops. Coneria buildings spread laterally; doors not lined up at single Y.

4. **Spec 4 i Spec 5 są separate concerns** — vision pipeline (Spec 4) działa niezależnie od navigation (Spec 5). Vision tylko **rozpoznaje** landmarks; nawigacja **dostaje** party do nich. Spec 4 merged unchanged; Spec 5 wymaga nowej drogi.

5. **`autonomy_principle.md` constraint hard** — bez "agent gra grę" bypass-u (savestate, pre-recorded paths), każde discovery wymaga real LLM call + real movement. Drogo iterować.

6. **Anthropic 5xx errors transient** — claude-opus-4-0 → 500 (invalid model id, fixed); 529 overloaded (transient, retry).

7. **Gradle daemon corruption ujawniona** — first session miała hangs przez stale daemon registry; clean `~/.gradle/daemon/9.4.1` + new shell session resolved.

## Autonomy + no-savestate principles (carried over)

- Agent gra grę; dev nie. Per `autonomy_principle.md`.
- New specs nie używają savestate-hash-keyed flags ani FF1_SAVESTATE-gated e2e tests; persistence flows through `landmarkMemory`. Per `feedback_no_savestate.md`.
