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
  Goal                        Choice { id, state, question, options[2..16] }
  WorldSnapshot               Ranking { scores, best, confidence, margin }
  GoalSelector  ───────────►  DecisionModel
  Ff1Goals                      ├─ DeclaredOrder   (no model: lowest priority wins)
                                └─ SemIfProcess    (a local SemIf sidecar)
```

`Choice` is SemIf's row format field for field — `{id, state, question, options[{id,
description}]}` — so a decision serialises with no translation layer and the same row
runs through SemIf's own CLI. The 2..16 bound is SemIf's too: it maps one answer token
per option, and there are sixteen letters in its answer alphabet.

## The goals

`Ff1Goals` is deliberately small, and every goal is applicable from the `Phase` the
profile's semantics already classify from RAM, or from the plan the Advisor wrote.
Nothing here guesses at FF1 internals the project has not established — the shop
row-to-item mapping and the EQUIP sub-header layout are still open, and a goal that
pretended to know them would only be a confident way to be wrong.

| priority | goal | applies when | does |
|---|---|---|---|
| 0 | `boot` | `Boot` | `boot` |
| 0 | `fight_battle` | `Battle` | `battleFightAll` |
| 5 | `back_out_of_menu` | `Town`, `Indoors`, `MenuStuck`, or nothing has moved for 3 turns | `sequence(B)` |
| 10 | `follow_plan_step` | the plan step names a dispatchable tool | that step |
| 20 | `press_a` | `Town`, `Indoors`, `MenuStuck` | `sequence(A)` |
| 30-33 | `step_north` … `step_east` | `Town`, `Indoors`, `Overworld` | `sequence(Up\|Down\|Left\|Right)` |

Every goal also takes itself off the menu once it has been tried three times inside a run
of turns that changed nothing — Minecraft asks a running goal `canContinueToUse()` every
tick, and ceasing to be an option is the only way a goal can say no here. Counting is by
effect, not by outcome: a tool can report `Ok` having moved nothing, which is how the
second smoke run below cycled. If everything applicable falls silent the selector declines
the turn and the chat-model Executor takes it.

Two more details are load-bearing. `follow_plan_step` is one option among several rather than
a fast path around the decision, because a plan written for the overworld has walked the
party north-west into Coneria Castle before now — and it drops off the menu entirely
when its tool is not dispatchable, so a plan naming something that does not exist costs
nothing instead of a turn. And `press_a` carries a warning in its own description that in
a shop, A can confirm a **sale**: one blind A at the Coneria weapon counter emptied every
weapon slot and put the gold back up.

Movement is one tile per goal rather than a run of taps, because FF1's town NPCs wander
every frame: a tile that was blocked last turn may be open this one, and a four-tap run
commits to a route through a world that moved underneath it.

## Running it

Off by default. The agent has a working chat-model Executor, and a second decision path
earns its place by being switched on deliberately.

```bash
# No model at all: the selector runs, lowest priority number wins. Useful for seeing
# which goals apply each turn without spending anything.
KNES_DECISION=order ./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=40"

# A local SemIf model.
KNES_DECISION=semif \
SEMIF_PYTHON=~/GitHub/SemIf/.venv/bin/python \
SEMIF_SRC=~/GitHub/SemIf/src \
SEMIF_BITS=4 \
./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=40"
```

| Variable | Default | Meaning |
|---|---|---|
| `KNES_DECISION` | `off` | `off`, `order`, or `semif` |
| `SEMIF_PYTHON` | `python3` | an interpreter with `semif_phase1` importable |
| `SEMIF_SRC` | — | SemIf's `src/`, when it is not installed in that interpreter |
| `SEMIF_MODEL` | `Qwen/Qwen3.5-4B` | checkpoint id or a local directory |
| `SEMIF_REVISION` | pinned 40-hex | SemIf refuses a remote model without one |
| `SEMIF_BACKEND` | `mlx` | `mlx` (Apple Silicon) or `torch` |
| `SEMIF_BITS` | — | `4` or `8`, MLX in-memory quantization |
| `SEMIF_SIDECAR` | found by walking up | path to `tools/semif_sidecar.py` |

Each turn writes `executor-goals` into the run's prompt directory: the state the model
read, every option it was shown, and the full ranking — so a run can be read back and the
decision checked against what actually happened.

## What it does in a run

Five smoke runs from a cold boot, `KNES_DECISION=semif` with Qwen3.5-4B at 4 bits and
Gemini still doing the planning. Each run found a bug in the goal *policy* — never in the
decision itself, which never once answered with something that was not on the menu:

| run | what happened | what it showed |
|---|---|---|
| 1 | `follow_plan_step` won all 12 turns at 0.94-1.00, after the same `walkTo` had failed five times | a plan step reads sensible no matter how the last attempt went; goals need Minecraft's `canContinueToUse` |
| 2 | dropped the plan after 3 failures, then cycled: plan, plan, plan, one tap, plan… | `sequence(Up)` returns **Ok** having moved nothing, and that was resetting the counter |
| 3 | counted effect instead of outcome — then tapped Up into the same wall for 21 turns | the rule has to apply per goal, not only to the plan |
| 4 | rotated Up→Down→Left→Right→Up correctly, party still never moved | the turn-1 `boot` had opened the main menu on an already-booted game; every later turn read as `Overworld` while Up and Down moved a cursor |
| 5 | `boot` T1, `enter_coneria` T3, `enter_weapon_shop` T6 | matches the chat-model baseline's T1/T2/T4, at ~100 ms a turn instead of seconds |

Run 5 then sat at the weapon counter pressing A, Up, Down and B without buying. That is
the known `buy_weapons` difficulty, and the chat-model Executor needs a long shopping
playbook in its prompt to get through it — eight generic goals and a 4B readout do not
replace that. The selector gets the party there; it does not yet do the shopping.

The fourth run is the one worth keeping in mind. The Advisor's opening plan always starts
with `boot`, and the turn loop has already booted by the time it runs. The chat-model
Executor sees the screenshot and quietly ignores that step. The selector took the plan at
its word — so `Ff1Goals.PHASE_GUARDS` now stops a plan step from routing around a guard
its own goal already carries, and `back_out_of_menu` is offered whenever nothing has moved
for a while, whatever the phase claims. A menu the classifier does not recognise looks
exactly like that from the outside: the screen keeps changing and the party never does.

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
