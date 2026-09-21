# kNES Modernization — Task Backlog

Date: 2026-09-21

Actionable breakdown of `architecture-modernization-backlog.md`. That document says
*what kind of system* kNES should become; this one is the work queue. Each task carries
the evidence that it is still needed, so a task can be dropped the moment the evidence
stops being true.

Size is relative effort, not time: **S** = one sitting, **M** = one PR with tests,
**L** = needs its own spec, **XL** = split before starting.

Waves are ordered by dependency, not importance. Within a wave, tasks are independent
unless `Depends on` says otherwise.

---

## Wave A — finish what is half-done

Cheapest wins. Every task here closes a gap that current code actively contradicts.

### A1. Collapse the two phase classifiers — **M** — *done 2026-09-21*

`Phase.fromRam` now delegates to `AgentObservationBuilder.phaseFor`, so the FF1 rules
exist once, in `profiles/ff1.json`.

Correction to the original plan: `Dialog`, `BattleMessage` and `Cutscene` were **not**
states the agent could reach. `fromRam` never returned them and nothing else assigned
them — they were unreachable enum constants, referenced only by
`PHASE_STATIC_WHITELIST`. Inventing RAM rules for them would have meant inventing
semantics nobody had verified, so they were deleted instead. `Watchdog` keeps its
whitelist *mechanism* with an empty default; real dialog detection belongs to G1.

`CartographerExplore` stayed agent-side as planned.

### A2. Evict FF1 constants from the agent runtime — **L, split per file**

The "move game-specific RAM interpretation into profile semantics" bullet is only done
for `knes-agent-tools`. `knes-agent/src/main` still hardcodes FF1 in ~10 files:

| File | What is hardcoded |
|---|---|
| ~~`tools/ToolSurface.kt`~~ | *done 2026-09-21 — reads `GameSemantics` instead* |
| ~~`Main.kt`~~ | *done — positions via `GameSemantics`* |
| ~~`runtime/MilestonePredicates.kt`~~ | *done 2026-09-21 — now `campaign/Ff1Campaign`* |
| ~~`pathfinding/*`~~ | *clean already — only a comment mentioned FF1* |
| ~~`runtime/StrategyContext.kt`~~ | *done 2026-09-21 — folded into `Ff1Campaign`* |
| ~~`agents/*`, `skills/ExitInterior.kt`, `skills/PressStartUntilOverworld.kt`~~ | *done* |

Do it as one PR per concern, not one big one. Suggested order: ~~`ToolSurface`~~ (done),
then ~~`MilestonePredicates`~~ (done), then ~~`StrategyContext`~~ (done). The pathfinders
turned out to be clean already — the only FF1 mention in them is a comment. What is left
is the `smPlayerX/Y` prompt-building reads in the three agents, plus `Memory`,
`CartographerAgent` and `HaikuClient`.

Correction to the done-when: "no edit under `knes-agent/src/main`" is not reachable for
campaign logic without inventing a generic RPG party model. A campaign is a game's goal
list, party model and item encoding — data structures, not RAM addresses. The reachable
goal is *isolation*: one `Campaign` implementation per game, and a runtime that only
knows the interface. Claude Plays Pokémon does not model this at all (the model edits
its own objectives in a knowledge base); kNES keeps it deterministic on purpose, because
"premature goal completion" is a documented failure of that approach.

`ToolSurface` needed four game signals that phases and landmarks did not cover, now in
`ProfileSemantics.signals`: `transitioning` (is the engine mid-transition),
`locationIdentity` (which map/overlay are we on), `menuFingerprint` (did my taps do
anything), plus the existing position mapping. Agents reach them through the
`GameSemantics` facade in knes-agent-tools, so knes-agent never imports knes-debug.

**Outcome:** four raw RAM reads remain in `knes-agent/src/main`, each deliberate and
commented — `currentMapId` twice as the persisted `InteriorMemory` key, and
`currentMapId`/`mapflags` in `AdvisorAgent` because those names appear verbatim in the
LLM prompt, where renaming them is a behaviour change no test here can catch.

Everything else goes through `GameSemantics` or `Campaign`. The `GameSemantics`
parameter is **not** defaulted on skills: a default meaning "no semantics" compiles fine
and fails silently at runtime, which is exactly the stealth no-op this project treats as
its dominant failure mode.

### A3. Delete the dead legacy MCP session — **S** — *done 2026-09-21*

`knes-mcp/src/main/kotlin/knes/mcp/NesEmulatorSession.kt` (147 lines) has no caller in any
`src/main`; only its own `NesEmulatorSessionTest` keeps it alive. `knes-emulator-session`'s
`EmulatorSession` is the real one.

**Done when:** file and test are gone, `./gradlew build` green. Port any assertion worth
keeping onto `EmulatorSession` first.

### A4. Stop running CI twice per PR — **S** — *done 2026-09-21*

`.github/workflows/build.yml` triggers on both `push: ["**"]` and `pull_request: ["**"]`,
so every PR branch runs the identical `build` job twice (observed on PR #134: two runs,
3m28s and 3m20s).

**Done when:** one run per PR. Keep `push` on the default branch only, or drop the
`pull_request` trigger — pick one and write down which.

---

## Wave B — MCP contract layer

The tool catalog is shared; the handlers are not.

### B1. One backend port behind both MCP modes — **L** — *done 2026-09-21*

`McpServer.kt` (262 lines) and `RemoteRestBridge.kt` (416 lines) still implement every
tool twice — in-process against `EmulatorToolset`, remote against hand-rolled REST calls.
`McpToolCatalog` unified the *schemas*, which makes the remaining behavioural drift
harder to see, not easier.

- Define a narrow port for emulator operations (`EmulatorToolset` is close already).
- Implement it once in-process and once over REST.
- Reduce both MCP entry points to registration + serialization.

**Outcome:** `EmulatorToolset` was already the port — it had a local and a remote
implementation. `RemoteRestBridge` was re-adapting REST by hand next to it, so deleting
it and building both entry points from `createMcpServer(backend: () -> EmulatorToolset)`
removed 416 lines without a new abstraction.

`createRemoteMcpServer` takes the backend **lazily**: `RemoteEmulatorToolset`
health-checks in its constructor, and building it eagerly made `--remote` die at startup
instead of on the first tool call. A test pins that.

### B2. Expose MCP resources — **M** — *done 2026-09-21*

`grep -rn "addResource" knes-mcp/src/main` returns nothing. Everything is a tool, so stable
read-only data is re-fetched as tool calls and re-serialized each time.

Published: `knes://emulator/state`, `knes://emulator/profiles`,
`knes://profiles/watched-ram` and `knes://profiles/semantics`.

ROM metadata is not among them — nothing on the `EmulatorToolset` port reports it today,
and inventing a REST endpoint for it belongs with the port work, not here. Left for
later.

Both rules from B1 apply and are tested: registration must not touch the backend, and
the read handlers must share **one** backend instance rather than calling the provider
(which would build a fresh emulator per read).

### B3. Structured tool results — **M** — *done 2026-09-21*

Thirteen handlers now return `structuredContent` alongside their content blocks.

Deviation from the plan, on purpose: the task said to replace the text with a *summary*.
The text is left byte-identical instead. An LLM reading these results is a client too,
and a summary that drops the RAM dump is a behaviour change no test in this repo can
catch. Structured content is additive; narrowing the text channel is a separate decision
that needs a smoke run behind it.

`get_screen` gets no structured content — the payload is the image, and repeating the
base64 in a second channel doubles every screenshot response.

`outputSchema` is deliberately not declared. The MCP spec binds a declared schema to
conforming structured content, and writing schemas for fourteen result types is its own
task rather than a side effect of this one.

### B4. Local/remote parity tests — **M** — *mostly obsolete after B1*

Parity is now structural: one handler set, so there is nothing to drift. `McpServerToolsTest`
drives handlers through a recording fake and asserts both entry points register the
catalogued tools. What is still worth adding is an end-to-end test against a live REST
server, which is E2E infrastructure rather than parity.

---

## Wave C — deterministic runtime

Everything about replay, golden tests and multi-session hosting is blocked here.

### C1. Explicit stepping API — **L** — *done 2026-09-21*

`NES` now has `stepInstruction()`, `stepCpuCycles(n)` and `stepFrame()`, each returning
the CPU cycles consumed, plus `frameCount` and an `onFrame` hook. `EmulatorSession`'s
hand-rolled advance loop is gone.

**Finding: `NesConfig.appletMode` is misnamed.** It has nothing to do with applets — it
selects whether the CPU loop clocks the PPU, i.e. stepped execution versus the PPU being
clocked elsewhere. `stepFrame` only terminates with it on, so it `check`s and fails
loudly rather than hanging. Renaming it (`steppedExecution`?) touches both UIs and the
applet and belongs with F3.

### C2. `Globals` → per-instance `NesConfig` — **L** — *done 2026-09-21*

`Globals` is a singleton holding `appletMode`, `palEmulation`, `enableSound`,
`disableSprites`, `timeEmulation`, `preferredFrameRate`, plus keycode/control maps
(`knes-emulator/src/main/kotlin/knes/emulator/utils/Globals.kt`). 20 call sites across
CPU, PPU, PAPU, `EmulatorSession`, both UIs and the applet.

Two emulator instances in one JVM cannot currently disagree about region or sound. That
blocks parallel agent runs and makes tests order-dependent.

**Outcome:** `NES` takes a `NesConfig` and hands it to CPU, PPU and PAPU. The only
`Globals` reads left inside the emulator are `NesConfig.fromGlobals()` — the bridge that
keeps desktop hosts working — plus `CPU_FREQ_NTSC` and `debug`, which are constants.
`EmulatorSession` no longer mutates the singleton at all; it passes `NesConfig.HEADLESS`.

Five test harnesses used to configure themselves by writing to `Globals` in an `init`
block, which made results depend on what an earlier test had left behind. Each now
states its own config.

`Globals.memoryFlushValue` was **not** carried over: the applet sets it and nothing in
the emulator ever reads it. Carrying it would have implied it does something. Worth a
look separately — the applet's comment says it exists to make a hacked SMB1 boot.

Still on `Globals`: the Compose UI and applet frame pacing, and the applet's keycode and
control maps. The maps are input configuration, not emulator state. Migrating the UIs is
follow-up work, and F3 (demote the applet) overlaps it.

### C3. One console-clock coordinator — **XL, split first**

CPU (1295 lines), PPU (1851) and PAPU (984) each carry their own timing. Centralize
scheduling once C1 and C2 are in.

**Depends on:** C1, C2.

---

## Wave D — state and replay

### D1. Versioned savestates with named chunks — **M** — *done 2026-09-21*

`NES.stateSave` (`NES.kt:93`) writes `putByte(1)` then dumps components positionally;
`stateLoad` accepts version `1` and nothing else. No ROM identity, mapper id, region,
controller state or config is recorded, so a savestate silently loads against the wrong
ROM.

**Outcome:** format 2 writes `KNES`, a version, a `RomIdentity` (mapper, PRG/CHR bank
counts, mirroring, FNV-1a over the program banks) and six length-prefixed named chunks,
so an unknown chunk from a newer writer can be stepped over instead of derailing the
read. A state from another ROM raises `SavestateMismatchException`; malformed data still
returns false. The original positional format is still read, told apart by the magic.

Region and config are **not** in the header. `palEmulation` now lives in `NesConfig`
(C2), and recording it would raise a question this task should not answer alone: does a
state saved under PAL refuse to load under NTSC, or reconfigure the machine? Left for
D2, where replay determinism forces the answer.

### D2. Replay format + determinism golden tests — **M**

A small input-script format independent of the API JSON, plus save→load→replay golden
tests.

**Depends on:** C1, D1.

---

## Wave E — accuracy and debug tooling

### E1. Turn `nestest.nes` into a real fixture — **M**

`nestest.nes` already sits in `knes-emulator/src/test/resources` and
`knes-agent-tools/src/test/resources`. Add golden-log assertions against the known-good
trace instead of using it as a smoke ROM.

### E2. Mappers beyond NROM/MMC1 — **L**

`mappers/` holds `MapperDefault` and `MapperMMC1` only. Track compatibility by ROM/test
name with expected results, and keep commercial-ROM tests separate from redistributable
ones (`roms/` currently holds FF1 dumps that cannot ship).

**Depends on:** C3.

### E3. Debug API: breakpoints, watchpoints, trace ring buffer — **L**

Promote memory watch, nametable reads, CPU registers and trace logging into a stable API
usable from API/MCP/UI. Keep agent strategy out of it.

---

## Wave F — module and build hygiene

### F1. Gradle convention plugin — **M**

Every module repeats the same `kotlin { jvmToolchain }` / `java { toolchain }` /
`kotlinOptions` / `test { useJUnitPlatform() }` block.

### F2. Decide Java 11 or 17 — **S to decide, M to migrate**

Currently split: 11 in `knes-emulator`, `knes-controllers`, `knes-debug`,
`knes-emulator-session`, `knes-terminal-ui`, `knes-skiko-ui`, `knes-applet-ui`; 17 in
root, `knes-api`, `knes-mcp`, `knes-agent`, `knes-agent-tools`, `knes-compose-ui`.

The 11/17 line runs straight through the core/tooling boundary, which may well be
deliberate — write the reason down either way.

### F3. Demote or remove the applet — **M**

`knes-applet-ui` plus `src/main/java/knes/launcher/AppletLauncher.java` keep applet-era
code first-class and produce removal warnings on modern JDKs. `Globals.appletMode`
defaults to `true`, which is a poor default for a headless-first project.

**Depends on:** C2.

---

## Wave G — agent harness

### G1. Richer observations — **M**

Add transition detection, collision/passability hints, and event/dialog/menu state to
`AgentObservation`. Location confidence already landed with profile semantics.

**Depends on:** A1.

### G2. Benchmark tasks with explicit win conditions — **M**

Reach Coneria, buy a weapon, equip it, exit town, survive a battle, reach the next
landmark. Each needs a machine-checkable predicate, reusable by the Reviewer.

### G3. Replayable decision traces — **M**

Log every observation, decision, action and verifier result in a form that can be
replayed without an LLM.

**Depends on:** D2.

### G4. Unblock `arm_party` / EQUIP — **L**

Parked FF1 blocker from the 2026-07-19 smoke: the Executor cannot drive the
`WEAPON|EQUIP|TRADE|DROP` sub-header, so 0/4 characters equip despite 3/4 holding a
weapon (127 turns stuck). Needs a deterministic state machine like the native
`buyAtShop`, not per-turn LLM taps. Secondary: `buyAtShop` serves 3/4 characters, RedMage
consistently gets nothing.

This is gameplay, not architecture — but it is the one open correctness bug with a
reproduction, so it should not fall off the list.

---

## Suggested order

A4 → A3 → A1 → A2 → B1 → B2/B3/B4 → C2 → C1 → D1 → G1 → the rest.

A4 and A3 are near-free. A1/A2 stop the semantics work from rotting. B1 is the largest
single reduction in duplicated code. C2 unblocks parallel sessions, which everything in
the agent harness eventually wants.
