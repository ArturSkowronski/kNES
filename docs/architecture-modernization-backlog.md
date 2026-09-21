# kNES Architecture Modernization Backlog

Date: 2026-09-06

## Market Baseline

The reference shape for a serious NES emulator is:

- A platform-neutral emulation core with CPU, PPU, APU, cartridge, mapper, memory bus, input ports, state serialization, and frame/audio output boundaries.
- UI/platform shells that host the core rather than being required by the core.
- Deterministic execution APIs: step instruction, step frame, reset, load ROM, save/load state, input replay.
- Accuracy validation through well-known CPU/PPU/APU test ROMs and compatibility lists, not only app smoke tests.
- Tooling surfaces for debugger, memory watch, trace logs, movie/TAS replay, screenshots, scripting or automation.
- MCP servers with explicit tool schemas, stable tool/resource contracts, thin handlers over domain services, structured JSON serialization, and observable protocol/transport failures.

References checked:

- MesenCE separates `Core`, `InteropDLL`, platform folders, and `UI`: https://github.com/nesdev-org/MesenCE
- FCEUX exposes emulator tooling such as Lua scripting and source-level debug builds: https://github.com/TASEmulators/fceux
- NESdev documentation and test ROM ecosystem remain the practical standard reference for CPU/PPU/APU/mapper behavior: https://www.nesdev.org/wiki/
- MCP servers expose capabilities through tools, resources, prompts, and transports defined by the Model Context Protocol specification: https://modelcontextprotocol.io/specification/

## Current Assessment

kNES is already stronger than a toy emulator:

- The repo has clear Gradle modules for core emulator, controllers, desktop UIs, API, MCP, debug helpers, and agent tooling.
- The core has CPU, PPU, PAPU, ROM parsing, mapper support for NROM/MMC1, state save/load, and useful unit/integration coverage.
- The API/agent surface is unusually good for automation: frame stepping, screenshots, watched RAM, savestates, profiles, MCP bridge.

The main architectural gap is that the "core" is not yet a clean core:

- `NES` was constructed from `GUI`, which made presentation concerns the host boundary.
- CPU, PPU, and PAPU contain large classes with mixed timing, state, debug, rendering, and legacy behavior.
- Runtime behavior still depends on global mutable `Globals`, which makes deterministic tests and multi-session hosting harder.
- Module dependencies point upward in places: `knes-api` and `knes-mcp` depend directly on several low-level modules instead of a narrow session/core API.
- Applet-era code is still first-class in the root app and creates removal warnings on modern JDKs.

The MCP layer is useful but not yet architecturally clean:

- `McpServer.kt` and `RemoteRestBridge.kt` duplicate tool registration instead of sharing one typed tool catalog.
- Local MCP calls go through `LocalEmulatorToolset`, while remote MCP calls manually adapt to REST endpoints; this makes feature parity fragile.
- MCP currently exposes tools only. ROM metadata, emulator state snapshots, profile definitions, and debug views would fit MCP resources better than repeated tool-only JSON blobs.
- Tool handlers still parse arguments inline and return mostly raw text, so schema validation, typed failures, and result evolution are weak.
- The legacy `NesEmulatorSession` duplicates headless session behavior and needs either migration to the shared session layer or removal.

## Backlog

### P0: Core Boundary

- Introduce a neutral `NesHost`/input/frame boundary and make `GUI` a legacy adapter.
- Move reusable headless host/session utilities out of anonymous API/MCP/test objects.
- Replace `Globals` runtime flags with per-instance `NesConfig`.
- Keep UI modules dependent on core, never core dependent on UI naming.

### P1: Deterministic Runtime

- Add explicit `stepInstruction`, `stepCpuCycles`, and `stepFrame` APIs at the `NES`/session layer.
- Centralize CPU/PPU/APU scheduling in one console-clock coordinator.
- Make frame boundaries observable without relying on applet mode flags.
- Make input latching and frame-boundary input updates part of the deterministic runtime contract.

### P1: State And Replay

- Version savestates with named component chunks.
- Include ROM identity, mapper id, region/timing mode, controller state, and config in savestate metadata.
- Add a small replay/movie format for deterministic input scripts independent from API JSON.
- Add golden tests for save/load/replay determinism.

### P1: MCP Contract Layer

- Extract a typed MCP tool catalog so local and remote modes register the same tool names, input schemas, defaults, and result shapes.
- Introduce a narrow backend port for emulator operations; implement it once for in-process sessions and once for REST transport.
- Replace all hand-built JSON payloads with `kotlinx.serialization` models or `JsonElement` builders.
- Add resources for stable read-only data: loaded ROM metadata, emulator state, active profile, watched RAM definitions, screenshots, and traces.
- Return structured machine-readable tool results first, with text summaries as a secondary compatibility layer.
- Add protocol-focused tests for escaping, required arguments, error mapping, local/remote parity, and screenshot image content.

### P1: Agent Harness

- Treat LLM play as instrumented agent control, not disassembly: expose screenshots, semantic RAM facts, short action primitives, memory, and verifier state.
- Make `observe` the primary perception call for agents; raw `get_state` and `get_screen` stay as debug/fallback tools.
- Move game-specific RAM interpretation into versioned profile semantics instead of scattering FF1 constants through runtime agents.
- Add location confidence, transition detection, collision/passability hints, and event/dialog/menu state to observations.
- Add benchmark tasks with explicit win conditions: reach Coneria, buy weapon, exit town, survive battle, reach next landmark.
- Log each observation, decision, action, and verifier result into replayable traces.

### P2: Accuracy

- Expand mapper support beyond NROM/MMC1 only after the scheduler boundary is clean.
- Add structured test-ROM fixtures for CPU, PPU timing, PPU rendering, APU frame counter, and mapper behavior.
- Track compatibility by ROM/test name and expected result, not ad hoc logs.
- Separate compatibility tests that require commercial ROMs from redistributable test ROMs.

### P2: Debug Tooling

- Promote memory watch, nametable reads, screenshots, CPU registers, and trace logging into a stable debug API.
- Add optional instruction trace ring buffer.
- Add breakpoints/watchpoints as core concepts, usable by API/MCP/UI.
- Keep LLM-agent strategy outside the emulator/debug contracts.

### P3: Module And Build Hygiene

- Extract shared Gradle convention configuration to reduce duplicate Kotlin/JVM settings.
- Decide whether Java 11 support is intentional; otherwise standardize on Java 17.
- Demote applet support to legacy/optional or remove it when compatibility permits.
- Keep one CI validation workflow and one documented local verification command.

## Started

- Added `NesHost` as the first neutral host port.
- Made legacy `GUI` extend `NesHost`.
- Changed `NES` to depend on `NesHost` instead of `GUI`.
- Added a regression test that proves `NES` can be constructed without a `GUI`.
- Added structured JSON posting to the MCP REST client.
- Replaced hand-built JSON payloads in the legacy MCP REST bridge for ROM loading, stepping, taps, sequences, action execution, and press/release calls.
- Extracted the shared MCP tool name, description, and input-schema catalog used by both in-process and legacy REST modes.
- Added a shared `AgentObservation` contract for instrumented agent play.
- Exposed `observe` through in-process MCP and the legacy REST bridge, with optional screenshot image content.
- Added versioned `ProfileSemantics` so phase rules, position field mappings, and landmark anchors live in profile JSON.
- Rewrote `AgentObservationBuilder` on top of profile semantics, removing the FF1 constants from the agent tooling.
- Gave both bundled profiles semantics, so the rules are exercised by more than one game.
