# V2.4.2 evidence — multi-map classifier transforms agent behaviour

Run on 2026-05-03 ~05:13. Args: `--max-skill-invocations=30 --wall-clock-cap-seconds=600`.
Trace: `2026-05-03T05-13-59.585591Z/trace.jsonl` (19 events).

## Headline

**Multi-map classifier completely changed the agent's path.** V2.4.2 didn't
even enter Coneria town this run — it walked east-then-north on the overworld,
triggered a random encounter, **won the battle (3 imps, +400 gold)**, then got
stuck in PostBattle results screen.

## Phase progression

```
TitleOrMenu
  → Overworld(146, 158)
  → Overworld(149, 159)   walked east
  → Overworld(149, 157)   walked north
  → Battle(imps)          random encounter
  → PostBattle (stuck)    XP/gold results screen blocks
```

V2.4 baseline: stuck Indoors at Coneria town (146, 152).
V2.3.1: stuck inside Coneria castle interior.
V2.4.2: never even entered town. Agent took an entirely different overworld
route because (a) the multi-map classifier removed the false-positive south-
edge exit that would have pushed it into mapId=24, and (b) the LLM advisor's
plan for this run happened to suggest east-then-north instead of straight
north into town.

## What worked

- Multi-map InteriorTileClassifier (0x30,0x32-0x37 walls, 0x39/0x3c/0x3f/0x47
  paddings, 0x31 floor explicit) didn't fire any false positives.
- Random encounter triggered naturally on overworld grass step.
- `battleFightAll` won the imp battle in 8 rounds.
- Phase classifier correctly identified Battle and PostBattle.

## What broke

`PostBattle` (screenState=0x63) is FF1's multi-stage results screen: XP,
level-up notifications, gold/items. Each stage requires a single A press to
dismiss. Old `battleFightAll` finished battle then issued a `tap("A", count=10)`
flush — not enough for multi-stage modal, agent stayed locked at
screenState=99 (= 0x63). 7 turns burned trying to escape PostBattle.

## V2.4.3 fix (next iteration)

`BattleFightAll` extended:
- `canExecute` now accepts both BATTLE (0x68) and POST_BATTLE (0x63).
- Final dismissal loop: single A tap + 30-frame wait, check screenState, repeat
  up to 30 times until phase transitions out of both Battle and PostBattle.

This unblocks the PostBattle modal. After dismissal, agent should resume
overworld navigation north toward Garland.

## Comparison

| Metric | V2.4.1 | V2.4.2 |
|---|---|---|
| Phase classifier (mapId) | yes | yes |
| Multi-map classifier coverage | no | YES |
| Reaches Coneria town | yes (stuck localY=42) | no — went east instead |
| Triggers random encounter | no | YES |
| Wins battle | no | YES (3 imps) |
| Reaches overworld after combat | n/a | NO — stuck in PostBattle |
| Failure mode | mapId=24 unknown tiles | PostBattle modal not dismissed |
