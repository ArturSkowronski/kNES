# Decision — 2026-05-03 — Pivot to V3.0 vision-first interior navigation

**Status:** approved by user, supersedes V2.6.6 plan in `HANDOFF.md`.

## Context

After V2.6.5, interior navigation in `mapId=8` (Coneria Town) achieves
**13% step-success rate** (76 moves / 583 attempts). Four sessions of work
on the interior decoder/pathfinder stack (V2.4 → V2.6.5) keep uncovering
new bugs. The handoff proposed V2.6.6: find FF1 sub-map ID byte, screenshot
diagnostic, audit `InteriorMapLoader` pointer table.

## Verification done before pivot (offline, no live runs)

| Claim | Evidence | Result |
|---|---|---|
| `currentMapId` lives at RAM 0x0048 | datacrystal RAM map | **Not confirmed.** Datacrystal: "documentation lacks a discrete map ID address; map context appears encoded across multiple flags." Our 0x0048 came from V2.4 heuristic (byte that differs A=overworld vs C=interior), not from disassembly. |
| Indoor party position is at 0x0029/0x002A | datacrystal | **Ambiguous.** 0x0029/0x002A = "Non-world map position", but datacrystal *also* lists 0x0068/0x0069 = "Indoor X/Y position" as separate addresses. We may be reading the wrong pair. |
| Pointer table at ROM file offset 0x10010 | datacrystal ROM map | ✅ Confirmed (Bank 4 start + 16-byte iNES header). |
| Bank resolution `(raw >>> 14) & 0x03` | FF1 disassembly | **Suspicious.** In MMC1/UxROM the upper 2 bits of a CPU pointer normally identify the swap window ($8000 vs $C000), not a bank index. Real bank number is usually held in a parallel table. |

## Why we pivot

What other LLM-game-agents do:

| Project | Approach | Outcome |
|---|---|---|
| Claude Plays Pokemon | Vision-only: screenshot → button. No RAM decode, no offline maps. | Plays Pokémon for months on Twitch, clears gyms, navigates towns/caves. |
| Voyager (Minecraft) | Code-as-skills + env feedback. Game has good API. | Open-ended curriculum learning. |
| Anthropic Computer Use | Vision-only. | Drives desktop apps. |

What we are doing: reverse-engineering FF1 internals (RLE decoder, tile
classifier, custom 64×64 BFS, fog of war, sub-map ID research) and getting
13% step-success. Every fix uncovers another bug.

**Diagnosis:** we are pursuing a perfect RAM/ROM model in a setting where a
much simpler vision-first agent works on harder games. The interior decoder
is solving the wrong problem.

## V3.0 — vision-first interior navigation

### What changes
1. Drop or downgrade-to-advisory `InteriorMapLoader`, `InteriorPathfinder`,
   `InteriorTileClassifier`. Code stays compiled (tests still green) but the
   executor stops calling them in `Indoors`.
2. In `Indoors` phase the executor receives a **screenshot at every
   tool-call turn**. Sonnet 4.5 vision sees walls, doors, stairs, NPCs.
3. RAM usage in interior:
   - Pre/post step compare on `localX/localY` → "did I move?" feedback.
   - `locationType == 0 && worldX/Y != 0` → exit detected.
   - Both already implemented; nothing new needed.
4. `exitInterior` skill becomes a thin loop: tap a direction, wait N frames,
   re-screenshot, repeat. The LLM picks the direction.

### What stays (do not break)
- Vision phase classifier (V2.5.0).
- RAM hard-override for overworld (V2.5.7).
- Tool-call instrumentation (V2.5.3).
- Full-map overworld BFS (V2.5.4) — works.
- `WalkOverworldTo` interior abort (V2.5.2).
- Fog defensive marking (V2.5.9).
- PostBattle auto-dismiss (V2.5.6).
- AnthropicSession + ModelRouter plumbing.

### Cost estimate
- 1 screenshot ≈ 1500 image tokens (Sonnet 4.5).
- ~$0.005 per interior step.
- 50 steps to clear a town/castle ≈ $0.25.
- Comparable to current budget per run.

### Risk
- One live run validates the design. If walking-by-vision gets stuck the
  same way, we know the bottleneck is prompt/skill design, not maps.
- Lower than continuing decoder work, which has uncertain payoff after
  4 sessions.

## Implementation outline (V3.0 first slice)

1. New skill `walkInteriorByVision(direction, maxSteps)` in
   `knes-agent/src/main/kotlin/knes/agent/skills/`.
   - Loop: tap direction, step ~16 frames, read RAM. If `localX/localY` did
     not change *and* not transitioned to overworld → return `Blocked`. If
     transitioned → return `ExitedInterior`. Otherwise continue up to
     `maxSteps`.
2. Modify `ExecutorAgent`/`AgentSession` to attach screenshot to executor
   input whenever `phase is Indoors`.
3. Update system prompts: "in `Indoors` you can SEE the screen; pick a
   direction toward a door, stairs, or south edge."
4. Optional: keep `findPathToExit` available as `@Tool` but mark in prompt
   "experimental, often wrong on towns; trust your eyes."
5. One live run, save trace + screenshots under
   `docs/superpowers/runs/2026-05-03-v3-vision-interior/`.

## Definition of done for V3.0 slice 1

- Party reaches Indoors → Overworld at least once via vision-driven walking
  (not via `pressStartUntilOverworld` reset).
- Step success rate ≥ 50% in `mapId=8` (currently 13%).
- Trace shows the LLM picking a direction in response to a screenshot.

## Decided alternative not taken

- **(A) V2.6.6 as handoff prescribes** — find sub-map ID, screenshot diag,
  loader audit. Rejected: 4 sessions of decoder work already, no closure
  in sight.
- **(C) Hybrid D+C from V2.3** — advisor screenshots when stuck, executor
  pure-RAM otherwise. Rejected for now: keeps the broken BFS in the hot
  path, still pays the cost of decoder bugs. Reconsider if (B) fails.
