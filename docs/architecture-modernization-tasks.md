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

### C3. One console-clock coordinator — **XL, now split**

Where scheduling actually lives today, all of it inside `CPU.emulate`'s instruction loop:

```kotlin
if (palEmu) { palCnt++; if (palCnt == 5) { palCnt = 0; cycleCount++ } }
if (clocksPpu) { ppucycles.setCycles(cycleCount * 3); ppucycles.emulateCycles() }
if (emulateSound) { papuClockFrame.clockFrameCounter(cycleCount) }
```

So the CPU owns the schedule, the NTSC dot ratio is a literal `3`, PAL is approximated by
lengthening the CPU rather than quickening the PPU, and when `steppedExecution` is off the
PPU is not advanced from here at all — two incompatible execution models in one build.

**C3a — name the ratios — S — *done 2026-09-21*.** `ConsoleTiming` (NTSC/PAL: CPU
frequency, dots per CPU cycle, the PAL correction interval). Behaviour-preserving, and
nestest is now a real regression guard for it.

**C3b — one coordinator — M — *done 2026-09-21*.** `ConsoleClock` owns "advance the
console by N CPU cycles" and calls the PPU and APU itself; `CPU.emulate` asks to be
caught up with instead of poking two components. Still called from the CPU's loop —
inverting that is C3c.

**C3c — invert the drive — L — *done 2026-09-22*.** The emulation loop lives on `NES`,
not inside `CPU.emulate`: free-running is now `stepInstruction()` called repeatedly,
which is exactly what stepped execution already was. One execution model.

The worry was cost — `emulate` caches registers in locals across iterations, so stepping
pays to save and restore them. Measured instead of assumed: **~34M instructions/second
stepped**, against the ~0.9M a real NES needs. Forty times the headroom.

It also collapsed two sources of truth. `NES.isRunning` was a flag while
`cpu.isRunning` was "is the thread alive", and `stateSave` consulted one while
`stateLoad` consulted the other. `isRunning` is now derived from the emulation thread,
and the applet's `beginExecution` delegates to `startEmulation` instead of reaching past
it.

The free-running path this changes had almost no coverage — everything else in the suite
drives the emulator a step at a time. `FreeRunningExecutionTest` writes down what has to
survive: the loop runs on its own thread, stops and stays stopped, the frame callback
fires off the caller's thread, starting twice does not leave two loops, and the
stop/restart handshake `stateSave` relies on keeps working. Seven tests, sub-second, and
checked five times over for flakiness before landing.

**C3d — delete the second execution model — S — *done 2026-09-22*.** `steppedExecution`
is gone from `NesConfig`, the check from `stepFrame`, and the branch from `ConsoleClock`.
The CPU loop always clocks the PPU, so frames need no configuring.

Worth noting what did *not* happen: `PpuTestHarness` and `MapperMMC1Test` had the flag
off and now clock the PPU during CPU steps. They pass unchanged, so the flag was not
providing the test isolation it looked like it might be.

`Globals.appletMode`, which fed it, is now read by nothing in the emulator. The applet
still writes it; removing that belongs with F3.

**C3e — fix PAL properly — S.** With the schedule in one place, PAL becomes 3.2 dots per
CPU cycle on the PPU's side rather than a fifth-instruction correction on the CPU's.
*Depends on C3c, and needs a PAL test ROM to verify.*

Found while doing C3b: **PAL's extra cycle never fires under stepped execution.** The
counter tracking "every fifth instruction" is a local in `CPU.emulate`, and `step()`
re-enters `emulate()` per instruction, resetting it before it can reach five. Ten stepped
NOPs cost 20 cycles under PAL, the same as NTSC, where they should cost 22. Harmless
today because every stepped configuration in this repo is NTSC, and pinned by
`PalTimingTest` so the assertion flips when C3c/C3e fix it. It is a good illustration of
what two execution models cost: the same config produces different timing depending on
who owns the loop.

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

### D2. Replay format + determinism golden tests — **M** — *done 2026-09-21*

`knes-replay 1`: a text script of `<frames> <buttons>` lines with `#` comments, carrying
the `RomIdentity` it was recorded against. Text rather than JSON because a replay is
something a person reads in a diff and a bisect points at, and the API's request shapes
should be free to change without invalidating recorded runs.

`EmulatorSession.play(replay)` refuses a script recorded on another ROM, the same way
savestates do.

The determinism tests come in pairs: one asserts two runs of the same script agree, and
a companion asserts that *different* input produces a different run — otherwise
"deterministic" is indistinguishable from "nothing happens".

---

## Wave E — accuracy and debug tooling

### E1. Turn `nestest.nes` into a real fixture — **M** — *done 2026-09-21*

**kNES passes nestest — official and unofficial opcode tests both report `0x00`.** It
always did. The fixture was broken, not the emulator.

The story, because it cost two passes to get right:

1. The original test ran with the PPU unclocked, so the ROM parked in the vblank wait at
   `$C008` and never touched RAM. `$0002 == 0x00` held only because the test zeroed RAM
   itself. It also read `$0003` and never asserted it.
2. Clocking the PPU made the ROM execute, but automated mode was still never reached, and
   from reading the code it was not obvious why. JMP absolute worked fine in isolation.
3. The instruction trace from E3 showed `C000: 4C (2)` — two cycles for a three-cycle
   instruction, landing at `$C005`. **`loadRom` leaves a reset interrupt pending**, and it
   is serviced before the next instruction, discarding a hand-assigned program counter and
   sending the CPU to the reset vector.

`NES.jumpTo` drains the pending interrupt first, so the lesson lives in the API rather
than in a comment. With it, the trace reads `C000: 4C (3) | C5F5: A2 (2) | ...` — straight
into the opcode suite — and both result bytes come back zero.

A test pins the trap: assigning the program counter without draining misses automated mode
entirely.

**Still open:** golden-log comparison against the reference `nestest.log`, which is not in
the repo.

### E2. Mappers beyond NROM/MMC1 — **L**

`mappers/` holds `MapperDefault` and `MapperMMC1` only. Track compatibility by ROM/test
name with expected results, and keep commercial-ROM tests separate from redistributable
ones (`roms/` currently holds FF1 dumps that cannot ship).

**Depends on:** C3.

### E3. Debug API: breakpoints, watchpoints, trace ring buffer — **L** — *partly done 2026-09-21*

`InstructionTrace` (a ring of pc/opcode/cycles, off by default and allocated only when
switched on), `NES.breakpoints` + `runUntilBreakpoint`, `NES.programCounter` and
`NES.opcodeAt` — the last reading through the mapper, the way the CPU does, because above
`$2000` raw CPU memory is a different view and a debugger reading it describes
instructions that never ran.

It earned its keep immediately: see E1 below.

Watchpoints followed: `NES.watch(address)` plus `runUntilStop`, which returns a
`DebugStop` saying whether a breakpoint or a watched value stopped it.

Two limits on watchpoints, both deliberate and both tested:

- They fire on a **change**, not a write. They are sampled between instructions rather
  than hooked into the CPU's write path, so a store of the value already there goes
  unnoticed. Keeping debug facilities out of the hot loop is the same call as for the
  trace.
- **RAM only.** Reading `$2002` clears the vblank flag; sampling a register every
  instruction would change the run being observed. `watch` rejects anything at or above
  `$2000` and says why.

The trace is published as the MCP resource `knes://emulator/trace`, **not** as a tool.
It is something to look at rather than an action, and the tool surface is meant to shrink
— Claude Plays Pokémon runs on three tools, and the project's own research notes that
harness was simplified over time rather than extended
(`docs/superpowers/research/2026-05-01-llm-game-agents.md`).

It reaches MCP through `EmulatorSession.traceTail`, not by letting agent tooling import
`knes-emulator`: the core's trace type stays in the core and `knes.api.TraceEntry` is the
session's view of it. Remote mode cannot serve it, the same way it cannot serve
savestates.

**Still open:** breakpoints and watchpoints through the API/MCP surface. They are actions
and would each cost a tool, so they need a decision about that budget first.

---

## Wave F — module and build hygiene

### F1. Gradle convention plugin — **M** — *done 2026-09-21*

`gradle/conventions/kotlin-module.gradle`, applied by eleven modules with an optional
`ext.knesJvmTarget`. Module build files total 804 → 688 lines.

Two things it deliberately does not do:

- **No `test { useJUnitPlatform() }`.** Not every module depends on the JUnit Platform
  launcher; applying it everywhere broke `knes-compose-ui` immediately. Test framework
  selection stays with the module — the backlog item is about Kotlin/JVM settings.
- **No `buildSrc`.** A script plugin applied with `apply from:` does not get the Kotlin
  Gradle plugin on its own compile classpath, so it matches Kotlin compile tasks by class
  name instead of importing the type. `buildSrc` would allow the import, at the cost of
  an extra build to configure and compile.

### F2. Decide Java 11 or 17 — *settled 2026-09-21: **17 everywhere***

Artur's call. Every module now targets Java 17, verified the same way F1 was: the
bytecode major version of a compiled class from each of the twelve modules, all 61
(previously seven were 55). `gradle/conventions/kotlin-module.gradle` no longer takes a
target parameter.

Consequence worth knowing: the emulator core no longer runs on a JRE 11. Nothing in the
repo needed that, and nothing recorded why it was ever a goal.

Side effect: the `java.applet` removal warnings in `knes-applet-ui` and
`src/main/java/knes/launcher/AppletLauncher.java` are now unavoidable on every build
rather than hidden behind an older target. That makes F3 more pressing, not less.

### F3. Demote or remove the applet — **M** — *partly done 2026-09-21*

`NesConfig.appletMode` is renamed `steppedExecution`, which is what it actually controls:
whether the CPU loop clocks the PPU. Nothing applet-specific was ever involved — the
applet merely happened to be the host that needed it on. A test pins the behaviour so
the name stays honest: same instruction count, frames only when it is set.

`Globals.appletMode` keeps its name, since the applet writes to it and it maps across in
`fromGlobals`. Removing the applet itself is still open.

`knes-applet-ui` plus `src/main/java/knes/launcher/AppletLauncher.java` keep applet-era
code first-class and produce removal warnings on modern JDKs. `Globals.appletMode`
defaults to `true`, which is a poor default for a headless-first project.

**Depends on:** C2.

---

## Wave G — agent harness

### G1. Richer observations — **M** — *partly done 2026-09-21*

`AgentObservation` gained `transitioning` and an opaque `locationId`, both from profile
signals. A caller detects a transition by diffing `locationId` between observations
rather than re-deriving the game's rules.

**Not done, and why:**

- *Separate dialog/menu state.* FF1 raises one flag for a map change, a dialog and an
  open menu alike. No verified RAM signal separates them, so a `menuOpen` field would be
  invented semantics — the same mistake A1 backed out of. It needs disassembly evidence
  first.
- *Collision/passability hints.* These need tile data, which nothing on the
  `EmulatorToolset` port exposes. The agent's pathfinders read maps through
  `MapSession`, well above the port. Either the port grows a tile-reading operation or
  this stays agent-side; that is a port decision, not an observation one.

### G2. Benchmark tasks with explicit win conditions — **M**

Reach Coneria, buy a weapon, equip it, exit town, survive a battle, reach the next
landmark. Each needs a machine-checkable predicate, reusable by the Reviewer.

### G3. Replayable decision traces — **M** — *done 2026-09-21*

`TurnLog` already carried observation, decision and verifier result; what it could not do
is replay, because the tools it records are vision-driven. `ReplayRecorder` wraps any
`EmulatorToolset` and records the layer below: buttons per frame, as a `knes-replay`
script (D2). A run reproduces exactly, with no model involved.

**Finding: `press` does not hold.** The MCP `press` tool says buttons "stay held until
released". They do not: `step`, `tap` and `sequence` all go through
`controller.setButtons`, which releases everything else first. A hold only survives
`advanceFrames`, the one call that moves frames without touching the controller.

The recorder mirrors the real behaviour, not the documented one — a recorder that
believed the docs would emit replays that do not reproduce the run. Whether to fix the
behaviour or the documentation is open: changing it alters what any agent relying on
`press` does, and there is no smoke run in CI to catch that.

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
