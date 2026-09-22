# Typed decisions: a goal selector on a System-One model

The Executor used to decide a turn by asking a chat model for JSON and reading the
answer back. Three of this project's bugs were failures of that readback, not of
judgement:

- a plan named `armCharsViaMenu`, which no dispatcher knows, and every fallback turn
  rejected it without saying what would have worked;
- `{"x":11}` would not decode into a `Map<String, String>`, so a perfectly good decision
  was discarded as a parse failure;
- a reply with no JSON object in it at all cost the turn.

None of the three can be expressed if the answers are written down before the model is
asked. That is the whole idea behind **Jev**, TypeSafe's "System One" model: constrained
decisions rather than prose, returning a probability over options the caller declared.
[**SemIf**](https://github.com/TheoLeeCJ/SemIf) is an independent open implementation of
the same interface pattern, running locally, and it is what this repo is wired against — its `checkers_semif.py` puts it
well:

> Legal moves become the declared options, so the model never writes a move and never
> has to be parsed: it ranks what the rules already allow. Illegal moves are impossible
> by construction, which is the part a chat-based harness cannot give you.

## Where Minecraft comes in

Minecraft gives every mob a `GoalSelector` holding a set of `Goal`s. Each tick the
selector asks every goal `canUse()` and runs the applicable ones in priority order. A
zombie never invents behaviour; it picks from behaviour that was written down, and the
goals that do not apply right now are not on the menu at all.

That is already the shape a typed decision wants. kNES borrows the structure and swaps
the arbiter: the goals that `canUse()` become the **declared options**, and a model ranks
them instead of a hardcoded priority integer. Priority survives as the tie-break, as the
rule for which goals get shown when more than sixteen apply, and as the entire rule when
no model is configured.

```
knes.agent.goals            knes.agent.decision
  Goal                        Choice { id, state, question, options[2..16], image? }
  WorldSnapshot               Ranking { scores, best, confidence, margin }
  GoalSelector  ───────────►  DecisionModel
  Ff1Goals / SmbGoals           ├─ DeclaredOrder   (no model: lowest priority wins)
  Goals.of(profile)             └─ SemIfProcess    (a local SemIf sidecar, text or pixels)
```

`Choice` is SemIf's row format field for field — `{id, state, question, options[{id,
description}]}` — so a decision serialises with no translation layer and the same row
runs through SemIf's own CLI. The 2..16 bound is SemIf's too: it maps one answer token
per option, and there are sixteen letters in its answer alphabet.

## The goals live in the profile

Nothing about a game is written in Kotlin. `profiles/<id>.json` already said which phase
the RAM means, where the player is and which landmark is nearby; it now also says what the
agent may want, what it is asked, and what it is told:

```json
"agent": {
  "question": "Which of these should Mario do right now?",
  "frame": "512x480",
  "state": [
    { "field": "lives", "label": "lives left" },
    { "field": "playerFloatState",
      "say": { "0": "Mario is standing on solid ground", "*": "Mario is in the air" } }
  ],
  "goals": [
    { "id": "jump_right", "priority": 12, "retryLimit": 6,
      "description": "Jump forward: hold A and Right together, clearing a gap or an enemy ahead.",
      "tool": "hold", "args": { "buttons": "Right,A,B", "frames": "18" },
      "notPhases": ["Boot"],
      "when": [ { "field": "gameState", "notEquals": 0 },
                { "field": "playerFloatState", "equals": 0 } ] }
  ]
}
```

The `when` clauses are the same `RamCondition` language the phase rules already use. Three
things are per game because they genuinely differ:

- **the question.** Final Fantasy's "the party" was being asked in front of a picture of
  Mario.
- **the frame.** Final Fantasy is a grid of 16x16 tiles and reads fine at the NES's own
  256x240; a gap in Mario's floor two tiles ahead is a handful of pixels there, and
  doubling it took him from zero jumps to six.
- **the retry limit.** Final Fantasy's world waits, so three tries that moved nothing means
  blocked. Mario's moves on its own, and a press that changed nothing is often just a press
  made mid-air.

The state lines name only what the game has — gold and a menu cursor, or lives and coins —
so nothing reads `null` whichever cartridge is in. A number the console keeps across
several addresses is put back together first: `goldLow: 144` tells a model nothing about
whether the party can afford a sword.

### Final Fantasy

| priority | goal | applies when | does |
|---|---|---|---|
| 0 | `boot` | `Boot` | `boot` |
| 0 | `fight_battle` | `Battle` | `battleFightAll` |
| 5 | `back_out_of_menu` | `Town`, `Indoors`, `MenuStuck`, or nothing moved for 3 turns | `sequence(B)` |
| 10 | `follow_plan_step` | the plan step names a dispatchable tool | that step |
| 20 | `press_a` | `Town`, `Indoors`, `MenuStuck` | `sequence(A)` |
| 30-33 | `step_north` … `step_east` | `Town`, `Indoors`, `Overworld` | `sequence(Up\|Down\|Left\|Right)` |

### Super Mario Bros

| priority | goal | holds | frames |
|---|---|---|---|
| 0 | `start_game` | START | 8 |
| 10 | `run_right` | Right + B | 8 |
| 11 | `walk_right` | Right | 8 |
| 12 | `jump_right` | Right + A + B | 18 |
| 13 | `jump_up` | A | 18 |
| 20 | `back_off` | Left | 8 |
| 30 | `wait` | nothing | 8 |

Every Mario goal *holds* buttons for a span of frames rather than tapping them, because
there the length of a press is part of the decision: the same A is a hop or a full jump
depending on how long it is held. That is what `ToolSurface.hold` is for.

Two rules stay in code, because a snapshot of RAM cannot express them. Every goal takes
itself off the menu once it has been tried `retryLimit` times inside a run of turns that
changed nothing — Minecraft asks a running goal `canContinueToUse()` every tick, and
ceasing to be an option is the only way a goal can say no here. Counting is by effect, not
by outcome: a tool can report `Ok` having moved nothing. And `follow_plan_step` is the one
goal that is code rather than data, because it dispatches whatever the Advisor wrote; the
profile still guards it, naming the tools that make sense only in one phase.

Nothing here guesses at game internals the project has not established. Where a byte is
used, it was verified: `playerFloatState` (`$001D`) is 0 on the ground because over a
160-turn run Mario's y held still on 103 of the 106 turns where it read 0 and moved on 50
of the 53 where it read 1.

## Making it fast enough to watch

The first runs were honest about the decision and dishonest about the demo: SemIf answered
in 100 ms and the turn still took two seconds. Profiling a 124-turn run said where it all
went, and none of it was the decision:

| | |
|---|---|
| wall clock | 2.06 s a turn |
| the Advisor | 22% of it, in five calls |
| median `walkTo` | **2032 emulated frames** |
| median button tap | **31 emulated frames** |
| SemIf | ~0.1 s — about 5% |

The composite tools are the wall clock. A plan step says `walkTo`, and `walkTo` in a town
runs a vision call per step; one turn took nineteen seconds. So `--reactive` drops the
Advisor: with the selector running, the plan was only one option among several anyway, and
without a plan there is no step naming a composite tool. Every turn becomes a button.

```
                       turn      steady state
  planned              2.06 s        1.6 s
  --reactive           0.43 s        0.20 s     5 decisions a second
  --reactive, pixels   0.47 s        0.22 s
```

The emulator was never the problem — it runs about 660 frames a second, eleven times real
time. It was being asked for 1360 frames a turn.

## Letting the model see the screen

`KNES_DECISION=pixels` is the same readout with the frame as the evidence. The pinned
checkpoint is a vision-language model; SemIf's MLX text backend drops the ViT, but the
weights are in the same cache, and SemIf's own `doom_pixels_semif.py` shows the readout
applied to the full model — option letters declared in the prompt, logits taken at the last
position, nothing sampled. `tools/semif_sidecar.py --backend pixels` does that for kNES.

Measured on an M-series Mac: **3.5 s to load, 188 ms a decision at 256x240** (290 ms at
512x480), against 100 ms for text alone. It is worth the 90 ms, and the reason is Mario.

## Mario

`SmbGoals` is the same machinery asking a different game the question it is best at. Final
Fantasy buries the decision under minutes of planning; Super Mario Bros **is** the decision
— which button, now, several times a second, with nothing to reason about.

Every Mario goal holds buttons for a span of frames rather than tapping them, because there
the length of a press is part of the decision: the same A is a hop or a full jump depending
on how long it is held. That is what `ToolSurface.hold` is for.

| priority | goal | holds | frames |
|---|---|---|---|
| 0 | `start_game` | START | 8 |
| 10 | `run_right` | Right + B | 8 |
| 11 | `walk_right` | Right | 8 |
| 12 | `jump_right` | Right + A + B | 18 |
| 13 | `jump_up` | A | 18 |
| 20 | `back_off` | Left | 8 |
| 30 | `wait` | nothing | 8 |

**Why Mario needs the pixel backend.** Asked from a RAM digest alone, the 4B readout plays
badly in exactly the places that matter — measured on written-out Mario states:

| the situation | what it picked |
|---|---|
| open ground | `walk_right` 0.52 — fine |
| a question block overhead | `jump_up` 0.92 — right |
| a Goomba two tiles ahead | `wait` 0.49 — walks into it |
| a pipe directly ahead | `walk_right` 0.68 — into the pipe |
| **a gap in the floor ahead** | **`walk_right` 0.92 — straight into the pit** |

`profiles/smb.json` watches Mario's position, his lives and the enemy count; it does not
watch the level geometry, so from RAM there is nothing that says *pipe* or *gap*. The model
was not being stupid, it was blind. The pixel backend is what gives it eyes, and it costs
90 ms.

The stall rule rescues some of this without any perception at all — a `run_right` that
stops moving Mario comes off the menu after six tries and something else has to win — but
nothing rescues walking into a pit, because walking into a pit works.

### Mario, actually played

```bash
KNES_DECISION=pixels SEMIF_IMAGE_SIZE=512x480 \
SEMIF_VLM_PYTHON=~/GitHub/SemIf/.venv-vlm/bin/python \
  ./gradlew :knes-agent:run -PappArgs="--fresh --reactive --rom=roms/smb.nes --profile=smb"
```

The cartridge found three bugs before the first Goomba did:

- **`Main` decoded Final Fantasy's overworld out of every ROM.** It is RLE-compressed in a
  fixed bank, and another game's bytes resolve to a negative file offset, so a Super Mario
  Bros run died three lines after the ROM path was read. `OverworldMap.forProfile` does not
  open the file for a game it cannot decode.
- **The model was asked what "the party" should do** — Final Fantasy's word, in front of a
  picture of Mario.
- **Nothing passed a frame size**, so the pixel backend always saw 256x240.

Then three runs of 200 turns each, all on World 1-1:

| | jumps | furthest | lives | ms |
|---|---|---|---|---|
| 256x240 | 0 | 297 px | 2 → 1 | 254 |
| + jumps gated to the ground | 0 | 297 px | 2 → 0 | 264 |
| **512x480** | **6** | **435 px** | 2 → 0 | 400 |

Mario walks right, jumps sometimes, and dies in the pits. The frame size is what moved the
needle: at native resolution a gap in the floor two tiles ahead is a handful of pixels, and
the model picked `walk_right` at 0.50 every turn of a nine-turn fall.

**Gating jumps to the ground** changed nothing measurable and is still right: there is no
double jump in this game, so A in mid-air does nothing, and a goal that cannot work has no
business on the menu. `playerFloatState` (`$001D`) is 0 on the ground — established by
correlation rather than taken from a RAM map: over a 160-turn run Mario's y held still on
103 of the 106 turns where the byte read 0, and moved on 50 of the 53 where it read 1.

### What a description is for

The single most effective change to how Mario plays was four words removed from a goal's
description. `walk_right` used to read *"Walk right without sprinting — slower, and easier
to stop before an edge."* That is advice to walk at the edge of a pit, which is how you
fall into one. Deleting the clause took the first life from 295 px to 722 px and halved the
death rate.

The opposite experiment is just as sharp. Rewriting every description to be purely
mechanical — *"Hold Right, A and B for a third of a second"* — with nothing about what the
option is **for**, produced **zero jumps in 220 turns** and 298 px. The description is the
model's only channel besides the picture; strip the purpose and the option stops being
reachable.

So: say what a goal is for, say nothing about when it is wise.

| | per life | deaths / 220 turns |
|---|---|---|
| original descriptions | 295, 297, 594 | 2 |
| purely mechanical | 297, 298, 297 | 2, no jumps at all |
| **purpose kept, advice removed** | **722, 722** | **1** |

Two other changes measured nothing and were reverted rather than kept as folklore: holding
the jump for 26 frames instead of 18, and offering `wait` only with an enemy on screen (an
enemy is on screen for almost all of World 1-1).

Where this leaves the demo: the mechanism is sound and fast — 200 typed decisions at
400 ms, never once answering with something that was not on the menu — and the *play* is
poor. Seven generic goals and a 4B readout do not clear World 1-1.

## Watching it

`python3 tools/v2_viewer.py`, then **http://localhost:9876/live**: the screen, a controller
that lights the buttons as they go down, and the ranking as bars. It polls eight times a
second, against the dashboard's three-second page reload, because an agent taking five
decisions a second looks like a broken one through a three-second window. The screen comes
from `live.png`, which the tool surface rewrites after **every single press** rather than
once a turn.

http://localhost:9876/ is still the full dashboard: RAM, milestones, plan, prompts.

## Why a sidecar

SemIf's own CLI is batch: a JSONL file in, a new JSONL file out, and the checkpoint
loaded on every invocation. `tools/semif_sidecar.py` keeps one process alive instead and
speaks one JSON decision per line. Measured here on an M-series Mac, Qwen3.5-4B at 4
bits:

```
load          8.0 s   once
decision     66-120 ms
```

Calls are serialised — one model, one pipe — and a decision that does not answer in time
kills the process rather than returning, because after a timeout the stream is out of
step and every later answer would belong to an earlier question. A row that fails
validation answers with an error and the process stays up: one bad decision must not end
a run.

## Tests

`./gradlew :knes-agent:test --tests 'knes.agent.decision.*' --tests 'knes.agent.goals.*'`

The live test needs the checkpoint and, for MLX, Apple Silicon, so it skips unless
`SEMIF_PYTHON` is set — the same arrangement as the ROM-dependent tests, which skip
without `roms/`:

```bash
SEMIF_PYTHON=~/GitHub/SemIf/.venv/bin/python SEMIF_SRC=~/GitHub/SemIf/src SEMIF_BITS=4 \
  ./gradlew :knes-agent:test --tests '*SemIfSidecarLive*'
```

It does not assert which option the model picks — that is the model's judgement, and
pinning it would turn a checkpoint change into a red build. It asserts the property the
design exists for: the answer is one of the declared options, and it is a tool the
Executor can dispatch.

---

*Jev and TypeSafe are other people's names and marks. [SemIf](https://github.com/TheoLeeCJ/SemIf)
is an independent project by its own authors, not affiliated with either; kNES is not
affiliated with any of them, and this integration is written against SemIf's interface.*

### Getting past the pipe

The first live run stood against a pipe in World 1-1 for **155 turns**, pressing Right.
Four things were wrong, and only the last one was about the game:

- **Mario's position was the byte on screen.** `playerX` resets as the screen scrolls, so
  walking right across a scroll read as standing still, and a rule that gives up when
  nothing moves gave up on a goal that was working. A profile can now declare a second byte
  for a coordinate — `"localXHigh": ["screenPage"]` — and the pair is the position.
- **The history was too short for the rule to fire.** Eight turns remembered, seven goals on
  the menu, and a retry limit of six: no single goal ever accumulated. The goal selector now
  sees 24 turns; the chat prompt still shows 8, which is what it was written for.
- **A jump counted as progress.** It changes Mario's position and puts him back exactly
  where he started, which rescued the very goal that was about to be taken away. Progress
  now means ending the turn somewhere the player has not been in recent memory — which also
  covers walking back and forth between two tiles.
- **The jump was too short, and the description did not mention pipes.** `jump_right` listed
  a gap and an enemy, not the third thing a jump is for: something solid in the way.

| | best | per life | longest block |
|---|---|---|---|
| as reported | 722 px | 295, 297, 594 | **155 turns** |
| absolute position + longer history | 898 px | 898, 296, 722 | 155 |
| progress = somewhere new | 722 px | 722 (no deaths in 440 turns) | 40 |
| + "a pipe or wall too tall to walk through" | 723 px | 723 | 43 |
| **+ jump held 28 frames, not 18** | **1662 px** | **1544, 1552, 1662** | 77 |

The jump length was tested earlier and looked like it made no difference — because jumps
were too rare to measure. Fixing the description first is what made the second test
readable.

The stall rule also learned not to empty the menu. After a game over the title screen leaves
exactly one applicable goal, the one that presses START; it took a few turns to land,
stalled itself out, and twenty turns went to a chat-model fallback a reactive run does not
have. When nothing is left, the selector forgets the recent past instead of declining.

### Why the reference Jev demos play Mario better

Compared against [`typesafe-mario`](https://github.com/alexdong/typesafe-mario), the
TypeSafe/Jev harness. Two things are identical and are not the difference: **one decision
every 8 emulator frames**, and an action set of seven macros that matches ours almost
name for name.

What differs:

**Their harness does the perception and the timing; the model confirms.** Jev is never
shown a screenshot. The harness turns RAM into object-centric JSON — `terrain` with
obstacle and gap geometry, `hazard` with up to three enemies at *projected* positions and
contact timing, `trajectory` with `crossing_known_gap`, `reaction_timing` measuring
observation-to-action latency. Its own prompt says it outright: *"Code has already
accounted for inference delay, action cadence, and the frames needed to clear an enemy. If
`jump_must_start_this_decision` is true, choose a forward jump now."* The model is asked to
agree with an answer the harness computed. Ours is asked to work it out from a picture.

**Jumps are held across decisions.** `RIGHT_JUMP` releases to `RIGHT`, and the instructions
say to keep holding while rising. Jump height in this game is a function of how long A is
held, so a jump there is a state the model steers. Ours is a fixed 28-frame commitment
decided once.

**Three typed questions per call, not one:** a Choice over the actions, a Noul (0–1) for
"should a jump begin or stay held", and a Score for danger. Ours asks one Choice.

**And the model is purpose-built.** That one is not a configuration we can match, and it
turns out to matter most — measured, below.

### Porting their perception, and what it did

`profiles/<id>.json` can now declare a tile buffer and a sprite table, and the agent
renders a window of it as a small ASCII map — `#` solid, `.` open, `M` the player, `E` an
enemy. `EmulatorToolset.readRange` exists for facts too numerous to name one address at a
time: Super Mario Bros keeps the level's collision geometry as 416 bytes at `$0500`.

The addresses came from the reference harness rather than from probing, but its `originY`
of 32 did not transfer — its y comes from a gym `info` dict and ours is raw `$00CE`.
Standing on the floor of World 1-1, the floor rendered two rows below Mario instead of
one. `MarioTileMapTest` boots the ROM, stands him on the ground and checks the ground is
under him; calibrated against it, the origin is 16.

Then four configurations, 440 turns each:

| what the model gets | best | per life |
|---|---|---|
| **the screen only** | **1662 px** | **1544, 1552, 1662** |
| screen + a miscalibrated map | 1222 px | 296, 295, 1222 |
| screen + the correct map | 817 px | 295, 282, 295, 817 |
| the map only, no screen — their shape | 296 px | 296, 257, 86 |

A correct map made a vision model **worse**: given two descriptions of the same thing it
has to reconcile them. And their shape — structured state, no pixels — collapsed outright:
331 of 440 turns spent standing still.

So the gap is not mainly the pipeline. A general 4B reading structured JSON sits on its
hands where a model trained for typed decisions acts. What a thin port of their perception
buys us is nothing; what would buy something is the part we did not port — the computed
deadlines, `jump_must_start_this_decision` and the rest, which turn a judgement into a
confirmation.

The map stays declared and verified, switched off in the state by `map.inState`. It is
correct, it is cheap, and it is waiting for a model that wants it.

## Running it live

```bash
tools/live_demo.sh smb      # or ff1
```

Brings up the viewer, starts the agent, and restarts it whenever it exits — a run ends on
its own at `--max-turns`, and can also die on a dropped model. A demo that stops between
the introduction and the point is worse than no demo. Ctrl-C stops both.

`KNES_DECISION` and `TURNS_PER_RUN` override the defaults; with `KNES_DECISION=pixels` it
finds SemIf's vision environment at `~/GitHub/SemIf/.venv-vlm` or tells you what to set.
