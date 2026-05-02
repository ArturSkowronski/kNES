# V2 first autonomous run — 2026-05-02

**Outcome:** `OutOfBudget` (skill budget) — agent did NOT reach Garland this run.

**This is the V2 milestone.** First time the agent autonomously played FF1 end-to-end without crashing. V1 reliably hit ITERATION_CAP on the first executor turn and got stuck looping at TitleOrMenu. V2 transitions title→overworld in ~30 seconds on turn 1, then walks the overworld for 13 more turns before exhausting the skill budget.

## What happened

- Turn 0: phase `TitleOrMenu`. Executor invoked `pressStartUntilOverworld`. Phase changed to `Overworld(138, 158)`. Title→party-created→overworld in one outer turn.
- Turns 1-13: phase `Overworld`. Executor invoked walking skills repeatedly. Coords drifted: (138,158) → (130,144) → (139,144) → (135,135) → (122,135) → (130,125) → (126,125). Agent moving but not on the optimal path to the Coneria bridge.
- Turn 14: skill budget exhausted (`maxSkillInvocations=20`).

## Known issues / next steps

- Every executor turn caps at Koog's `maxIterations=10` (the model keeps calling tools + analysing instead of returning final text). Cap fires AFTER one tool call has already executed, so progress is real, but each turn is ~30s instead of ~5s.
- Agent walks but doesn't have a known-good bridge target. Need to either pin a fixed Coneria→bridge route or improve the advisor's directional planning.
- No prompt caching → input tokens recomputed each turn.

## Run config

```
./gradlew :knes-agent:run \
  --args="--rom=$ROM --profile=ff1 --max-skill-invocations=20 --wall-clock-cap-seconds=420"
```

Wall clock: ~7 min 40s. Skill invocations: 14. Outcome: OutOfBudget.

## Evidence

- `trace.jsonl`: full per-turn record (advisor calls, executor results, RAM diffs)
