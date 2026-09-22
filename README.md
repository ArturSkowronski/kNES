# kNES - Kotlin NES Emulator

![image](https://github.com/user-attachments/assets/a2cc58bf-5a42-4f47-9b54-cfa6630cdb25)

kNES is a Nintendo Entertainment System (NES) emulator written in Kotlin, forked from the vNES Java emulator. This project was created primarily for fun and educational purposes, allowing developers to learn about emulation techniques and NES hardware while enjoying classic games.

![Java CI](https://github.com/ArturSkowronski/kNES/actions/workflows/build.yml/badge.svg)

## About This Project

kNES is a reimplementation and extension of the vNES emulator (originally developed by Brian F. R.) in Kotlin. The project aims to:

- Provide a modern, Kotlin-based NES emulator
- Serve as an educational resource for those interested in emulation
- Demonstrate different UI implementation approaches in the JVM ecosystem
- Give an LLM a machine it can actually drive — deterministic stepping, semantic observations, replayable traces
- Have fun with retro gaming and programming!

This project is distributed under the GNU General Public License v3.0 (GPL-3.0), ensuring it remains free and open source.

## Supported Mappers

| Mapper | Name | Games |
|--------|------|-------|
| 0 | NROM | Super Mario Bros, Donkey Kong, Pac-Man, ~250 games |
| 1 | MMC1/SxROM | Final Fantasy, The Legend of Zelda, Metroid, Mega Man 2, ~680 games |

A ROM with any other mapper still loads, on an NROM substitute — but it says so, through
`NES.isMapperSupported` and in the message `load_rom` returns. It used to substitute
silently and then produce nonsense.

## Controls

| Key | NES Button |
|-----|-----------|
| Z | A |
| X | B |
| Enter | Start |
| Space | Select |
| Arrow keys | D-pad |

Gamepad (Switch Joy-Con, Xbox-style controllers) also supported.

## Project Structure

The project is organized into the following modules:

- **knes-emulator**: Core emulator — CPU (6502), PPU, PAPU, memory, and mappers (NROM, MMC1).
- **knes-controllers**: Input handling — keyboard, gamepad (Switch Joy-Con, Xbox-style).
- **knes-compose-ui**: Jetpack Compose Desktop UI (primary, recommended).
- **knes-skiko-ui**: Skiko-based hardware-accelerated rendering UI.
- **knes-terminal-ui**: Terminal-based UI (text-based interface) — slow AF, but freaking fun.
- **knes-emulator-session**: the narrow session API over the core — load, step, savestate, replay.
- **knes-debug**: game profiles (named RAM addresses) and their semantics — phase rules, landmarks, signals.
- **knes-api**: REST API server for AI agents, TAS tools, and automation ([docs](knes-api/README.md)).
- **knes-mcp**: MCP server — an LLM drives the emulator over the Model Context Protocol, in-process or through the REST API.
- **knes-agent-tools**: the `EmulatorToolset` port both MCP modes and the agent share.
- **knes-agent**: an LLM plays Final Fantasy and Super Mario Bros on the emulator ([docs](knes-agent/README.md)). One `OPENAI_API_KEY`, or a local 4B model through [SemIf](docs/typed-decisions.md).
- **knes-applet-ui**: Java Applet-based UI (legacy).

https://github.com/user-attachments/assets/9036ae9a-3be8-43ec-8050-3a47b29d1648

### KotlinConf 2025 Presentation: Build your own NES Emulator with Kotlin (click to play)

[![Build your own NES Emulator with Kotlin | Artur Skowroński](https://img.youtube.com/vi/4A6aLK2KznU/hqdefault.jpg)](https://www.youtube.com/watch?v=4A6aLK2KznU)


## Building and Running

### Prerequisites

- Java 17 (every module targets 17; the Gradle toolchain fetches it if missing)
- Gradle 9.4+ (included via wrapper)

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew run
```

This will launch the main application, which allows choosing between the different UI implementations.

### Running Specific UIs

```bash
# Compose UI (recommended)
./gradlew :knes-compose-ui:run

# Terminal UI
./gradlew :knes-terminal-ui:run

# Skiko UI
./gradlew :knes-skiko-ui:run
```

### REST API Server

Run the emulator as a headless REST API for AI agents, TAS tools, and automation:

```bash
./gradlew :knes-api:run   # starts on port 6502
```

```bash
# Load a ROM
curl -X POST localhost:6502/rom -H 'Content-Type: application/json' \
  -d '{"path": "/path/to/game.nes"}'

# Step 60 frames holding RIGHT
curl -X POST localhost:6502/step -H 'Content-Type: application/json' \
  -d '{"buttons": ["RIGHT"], "frames": 60}'

# Get screenshot
curl localhost:6502/screen -o frame.png

# Get game state
curl localhost:6502/state
```

12 endpoints: `/step`, `/screen`, `/state`, `/watch`, `/press`, `/release`, `/fm2`, and more. Full docs in [knes-api/README.md](knes-api/README.md).

### MCP Server

Let an LLM drive the emulator over the Model Context Protocol:

```bash
./gradlew :knes-mcp:installDist          # in-process emulator
./gradlew :knes-mcp:installDist --remote # drive the Compose UI's emulator instead
```

Fourteen tools — `load_rom`, `step`, `tap`, `sequence`, `observe`, `apply_profile` and
friends — plus read-only resources the model can pull without spending a tool call:

| Resource | What |
|---|---|
| `knes://emulator/state` | frame, watched RAM, CPU registers, held buttons |
| `knes://emulator/profiles` | game profiles the backend can apply |
| `knes://profiles/watched-ram` | named addresses per game, with meaning |
| `knes://profiles/semantics` | phase rules, landmarks, signals |
| `knes://emulator/trace` | the instructions the CPU most recently executed |

`observe` is the one to reach for: it returns a semantic reading — phase, position,
location, whether the engine is mid-transition — rather than raw bytes.

### An LLM plays Final Fantasy

```bash
export OPENAI_API_KEY=sk-...
./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=200"
```

One key is the whole setup; `GEMINI_API_KEY` works the same way. From a cold boot the
agent creates a party, walks into Coneria, finds the weapon shop and buys — four
campaign milestones with no human input. Details and the model roles in
[knes-agent/README.md](knes-agent/README.md).

Game knowledge lives in `profiles/<id>.json`, not in the agent: phase rules, landmark
anchors and the signals tools ask about are data, so teaching it another game is a JSON
edit rather than a code change.

### A local 4B model plays, without writing a word

The agent can also decide a turn as a **typed decision** — a choice over options declared
before the model is asked — instead of by generating JSON. That is the interface pattern
Jev introduced and [SemIf](https://github.com/TheoLeeCJ/SemIf) (formerly OpenJev)
implements openly; kNES is wired against SemIf, running **Qwen3.5-4B locally**. A tool
that does not exist cannot be picked, because it was never on the menu.

```bash
tools/live_demo.sh smb      # Super Mario Bros
tools/live_demo.sh ff1      # Final Fantasy
```

Then **http://localhost:9876/live**: the screen, a controller lighting each button as it
goes down, and the ranking the model produced. `tools/record_run.py` turns a finished run
into a video in the same windows.

Measured on an M5 Pro with the pixel backend — the screen itself as the evidence, no RAM
digest: **~400 ms a decision**, and the same agent plays both games from their own
profiles. The structure is Minecraft's `GoalSelector`: goals say whether they can run, and
the ones that can become the declared options. Full notes in
[docs/typed-decisions.md](docs/typed-decisions.md).

## Architecture

The emulator uses a modular architecture with a clear separation between the core emulator functionality and the UI. This allows for different UI implementations to be used with the same core emulator.

### Core Emulator

The core emulator is contained in the `knes-emulator` module and provides the following components:

- **CPU**: 6502 processor — all 56 official opcodes, cycle-accurate
- **PPU**: Picture Processing Unit — background tiles, sprites, scrolling, palette
- **PAPU**: Audio — square, triangle, noise, and DMC channels
- **Memory**: 64KB CPU address space with mirroring
- **Mappers**: NROM (Mapper 0) and MMC1 (Mapper 1) with PRG/CHR bank switching
- **Timing**: one `ConsoleClock` advances PPU and APU by what the CPU just ran; NTSC and PAL differ only in a `ConsoleTiming` value

### Deterministic execution and debugging

The emulator is driven, not just run. One execution model: free-running is `stepInstruction()`
called in a loop, which is what stepped execution already was.

- `stepInstruction()`, `stepCpuCycles(n)`, `stepFrame()` — each returns the CPU cycles it took
- `frameCount` and an `onFrame` hook, owned by the instance rather than by whatever host is attached
- `InstructionTrace` — a ring of program counter / opcode / cycles, off by default
- Breakpoints and watchpoints via `runUntilStop()`; watchpoints are RAM-only, because reading a register like `$2002` would change the run being observed
- Savestates carry a ROM identity, so loading one from a different cartridge fails loudly
- `knes-replay` — a diffable text format for input scripts, with determinism golden tests

### Testing

800+ automated tests covering every layer:
- CPU instruction tests (all opcodes, all addressing modes)
- PPU register and rendering logic tests
- PAPU audio channel tests
- MMC1 mapper unit tests
- **nestest.nes** — kNES passes both the official and the unofficial opcode suites
- Free-running execution: the emulation thread's start/stop/restart contract
- Savestate round-trip and replay determinism (golden tests)
- Super Mario Bros E2E game tests (headless, input injection, RAM assertions)
- REST API and MCP protocol tests (tool contract, resources, structured results)
- Compose Desktop UI smoke tests

Some tests need a commercial ROM (MMC1 bank switching, PPU rendering, the campaign
benchmark). Those ROMs cannot be redistributed, so the suites **skip themselves** when
`roms/` is absent — CI stays green and a developer with the files gets the coverage.

```bash
./gradlew test
```

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license. This project is a reimplementation and extension of that work.
