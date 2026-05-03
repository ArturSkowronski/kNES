You're a fresh agent picking up the FF1 Koog Agent project after V2.4.5.
Branch ff1-agent-v2-4 of /Users/askowronski/Priv/kNES-ff1-agent-v2.

Read these in order before responding:
1. docs/superpowers/STATE-2026-05-03.md (state snapshot — start here)
2. docs/superpowers/runs/2026-05-03-v2-4-5-frames48/SUMMARY.md (most recent
   run; the bug we hit)
3. docs/superpowers/runs/2026-05-03-v2-4-4-frames/SUMMARY.md (the previous
   run; symptoms differed)
4. knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt
5. knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt
6. knes-agent/src/main/kotlin/knes/agent/perception/InteriorMap.kt + Loader

# Your task — analyze, do not implement

The previous Claude session iterated 5 times (V2.4.1 → V2.4.5) trying to make
ExitInterior actually escape FF1 town/castle interiors. Each iteration fixed
a real bug AND surfaced a new one — so progress is real, but we never closed
the loop. The current proposal in STATE is "BFS the full 64x64 map instead of
16x16 viewport" (V2.4.6 option B). That's the obvious next move.

I want you to question that obvious move and look at this with fresh eyes.

Specifically, think outside the box about:

1. **Are we framing the problem correctly?** The agent's goal is "reach
   Garland on the bridge north of Coneria." Every iteration has assumed
   "agent must enter towns and exit cleanly to continue." But Coneria town /
   castle are NOT on the path to Garland — Garland's bridge is overworld
   only, accessible by walking north from spawn WITHOUT entering any
   interior. Why is the agent walking into towns at all? Is the real bug at
   a higher level (advisor planning, overworld pathfinder picking town
   tiles as walkable when it should treat them as detours)?

2. **Are we fighting the engine?** FF1 uses InputQueue + tap pattern in
   knes-emulator-session. Direct setButtons + advanceFrames doesn't always
   register inside interiors (V2.4.4/V2.4.5 evidence). Is there a more
   reliable input pattern for indoor walking that the existing
   knes-debug/actions/ff1/* code already uses successfully? Look at how
   BattleFightAll drives input compared to ExitInterior.

3. **Is V2.3 OverworldMap.classifyAt classifying TOWN/CASTLE correctly?**
   Currently TOWN/CASTLE are passable (party can walk onto them, by design
   — entering a town IS the way you go into one). But the path-to-Garland
   strategy should AVOID stepping on town/castle tiles when there's a grass
   alternative. The pathfinder should prefer GRASS over TOWN at equal
   distance. Could a small cost-weighting in BFS solve the real problem
   without ever fixing ExitInterior?

4. **Is the live evidence honest?** V2.4.5 ran ~12 minutes wall clock and
   was killed by --wall-clock-cap. Some "stuck" turns may actually be the
   advisor stalling (Anthropic API latency) — not the in-game state.
   Re-read the trace timestamps and decide whether the agent was actually
   stuck vs just slow.

5. **Is there a 90% solution that's 10% the work?** The user's
   long-arc target is `Outcome.AtGarlandBattle`, NOT "general FF1 interior
   navigation". What's the smallest possible change that makes the agent
   reach Garland? E.g., hardcoded waypoint avoidance ("never walk onto a
   TOWN tile on overworld"). It's contrary to the "agent decides" V2 ideal,
   but it'd close the V2 long-arc this week. Is that acceptable?

# Output

Write a single markdown response (no code edits) with:

- **Reframe** — what is the actual problem, not the surface symptom?
- **Three alternative paths forward** with effort vs probability of closing
  V2-long-arc estimates
- **Your recommendation** with reasoning
- **What you'd want to verify before committing** to that path

Keep it under 1000 words. Don't pad. Don't write code. Don't propose
implementations beyond a sentence each.
