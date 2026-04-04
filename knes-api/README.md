# kNES API Server

REST API for controlling the kNES emulator programmatically — built for AI agents, reinforcement learning, TAS (Tool-Assisted Speedrun) tools, and automation.

## Quick Start

```bash
# Start the server
./gradlew :knes-api:run

# Load a ROM
curl -X POST localhost:8080/rom \
  -H 'Content-Type: application/json' \
  -d '{"path": "/path/to/game.nes"}'

# Walk Mario right for 2 seconds
curl -X POST localhost:8080/step \
  -H 'Content-Type: application/json' \
  -d '{"buttons": ["RIGHT"], "frames": 120}'

# Get a screenshot
curl localhost:8080/screen -o frame.png
```

The server starts on port 8080 by default. Override with `KNES_PORT` environment variable.

## API Reference

### Emulator Lifecycle

#### `GET /health`
Health check and emulator status.

```bash
curl localhost:8080/health
```
```json
{"status": "ok", "romLoaded": true, "frames": 5400}
```

#### `POST /rom`
Load a NES ROM file.

```bash
curl -X POST localhost:8080/rom \
  -H 'Content-Type: application/json' \
  -d '{"path": "/absolute/path/to/game.nes"}'
```
```json
{"status": "loaded", "romLoaded": true, "frames": 0}
```

#### `POST /reset`
Reset the emulator to power-on state.

```bash
curl -X POST localhost:8080/reset
```

---

### Core Agent API

#### `POST /step`
**The primary endpoint.** Send button state, advance N frames, get observation back. This follows the [Gymnasium](https://gymnasium.farama.org/) `step()` pattern used by RL frameworks.

```bash
# Hold RIGHT + A for 10 frames
curl -X POST localhost:8080/step \
  -H 'Content-Type: application/json' \
  -d '{"buttons": ["RIGHT", "A"], "frames": 10}'
```
```json
{"frame": 130, "ram": {"playerX": 45, "lives": 2}}
```

**Batch variant** — execute a sequence of input changes atomically:

```bash
curl -X POST localhost:8080/step \
  -H 'Content-Type: application/json' \
  -d '{
    "sequence": [
      {"buttons": ["RIGHT"], "frames": 60},
      {"buttons": ["RIGHT", "A"], "frames": 10},
      {"buttons": [], "frames": 30}
    ]
  }'
```

**Parameters:**
- `buttons` — Array of button names to hold. Valid: `A`, `B`, `START`, `SELECT`, `UP`, `DOWN`, `LEFT`, `RIGHT`. Empty array = no buttons.
- `frames` — Number of frames to advance (default: 1). NES runs at 60fps, so 60 frames = 1 second.

**Response** includes the current frame count and values of any [watched RAM addresses](#post-watch).

#### `POST /watch`
Configure RAM addresses to include in `/step` and `/state` responses. Use game-specific memory maps to observe game variables directly.

```bash
curl -X POST localhost:8080/watch \
  -H 'Content-Type: application/json' \
  -d '{
    "addresses": {
      "playerX": "0x0086",
      "playerY": "0x00CE",
      "lives": "0x075A",
      "world": "0x075F",
      "level": "0x0760",
      "gameState": "0x0770",
      "score": "0x07DD"
    }
  }'
```

After configuring, `/step` and `/state` responses include named values:
```json
{"frame": 200, "ram": {"playerX": 120, "playerY": 192, "lives": 2, "world": 0, "level": 0}}
```

---

### Observation

#### `GET /screen`
Current frame as PNG image (256x240 pixels, native NES resolution).

```bash
curl localhost:8080/screen -o frame.png
```

#### `GET /screen/base64`
Current frame as base64-encoded PNG in JSON — useful for API clients that can't handle binary.

```bash
curl localhost:8080/screen/base64
```
```json
{"frame": 200, "image": "iVBORw0KGgo..."}
```

#### `GET /state`
Full emulator state snapshot: CPU registers, watched RAM, held buttons.

```bash
curl localhost:8080/state
```
```json
{
  "frame": 200,
  "ram": {"playerX": 120, "lives": 2},
  "buttons": ["RIGHT"],
  "cpu": {"pc": 32768, "a": 0, "x": 5, "y": 0, "sp": 253}
}
```

---

### Stateful Button Control

For real-time agents that manage their own timing — press/release buttons independently of frame stepping.

#### `POST /press`
```bash
curl -X POST localhost:8080/press \
  -H 'Content-Type: application/json' \
  -d '{"buttons": ["RIGHT", "A"]}'
```
```json
{"status": "ok", "held": ["A", "RIGHT"]}
```

#### `POST /release`
```bash
curl -X POST localhost:8080/release \
  -H 'Content-Type: application/json' \
  -d '{"buttons": ["RIGHT"]}'
```
```json
{"status": "ok", "held": ["A"]}
```

#### `POST /release-all`
```bash
curl -X POST localhost:8080/release-all
```
```json
{"status": "ok", "held": []}
```

---

### TAS Compatibility

#### `POST /fm2`
Execute input from [FM2 format](https://fceux.com/web/FM2.html) — the standard TAS movie format used by the FCEUX emulator. Each line represents one frame of input.

```bash
curl -X POST localhost:8080/fm2 \
  -H 'Content-Type: text/plain' \
  -d '|0|R......A|........|
|0|R.......|........|
|0|........|........|'
```
```json
{"framesExecuted": 3, "frame": 203}
```

**FM2 button order per controller:** `RLDUTSBA` (Right, Left, Down, Up, sTart, Select, B, A). A dot means not pressed, the letter means pressed.

This enables playback of existing TAS recordings directly through the API.

---

## Button Names

| API Name | NES Button | Default Keyboard |
|----------|-----------|-----------------|
| `A` | A | Z |
| `B` | B | X |
| `START` | Start | Enter |
| `SELECT` | Select | Space |
| `UP` | D-pad Up | Arrow Up |
| `DOWN` | D-pad Down | Arrow Down |
| `LEFT` | D-pad Left | Arrow Left |
| `RIGHT` | D-pad Right | Arrow Right |

---

## Example: AI Agent Session

A complete session controlling Super Mario Bros:

```bash
# 1. Load the ROM
curl -X POST localhost:8080/rom \
  -H 'Content-Type: application/json' \
  -d '{"path": "/games/smb.nes"}'

# 2. Configure RAM watches for game variables
curl -X POST localhost:8080/watch \
  -H 'Content-Type: application/json' \
  -d '{"addresses": {"x": "0x0086", "y": "0x00CE", "lives": "0x075A", "state": "0x0770"}}'

# 3. Wait for title screen (2 seconds)
curl -X POST localhost:8080/step \
  -H 'Content-Type: application/json' \
  -d '{"buttons": [], "frames": 120}'

# 4. Press Start to begin
curl -X POST localhost:8080/step \
  -H 'Content-Type: application/json' \
  -d '{"buttons": ["START"], "frames": 5}'

# 5. Wait for gameplay to start
curl -X POST localhost:8080/step \
  -H 'Content-Type: application/json' \
  -d '{"buttons": [], "frames": 180}'

# 6. Agent loop: observe → decide → act
curl -X POST localhost:8080/step \
  -H 'Content-Type: application/json' \
  -d '{"buttons": ["RIGHT"], "frames": 1}'
# → {"frame": 306, "ram": {"x": 41, "y": 192, "lives": 2, "state": 1}}

# 7. Get a screenshot for vision-based agents
curl localhost:8080/screen -o frame.png
```

---

## Architecture

### Design Principles

The API server is a **pure external layer** — it makes zero changes to the emulator core or controller modules. Input is injected through `ControllerProvider`, the same interface used by the keyboard and gamepad controllers.

```
HTTP Client (AI agent / TAS tool / script)
    |
    v
ApiServer (Ktor routes)
    |
    v
EmulatorSession (NES lifecycle)
    |
    v
ApiController implements ControllerProvider
    |
    v
NES <-- reads button state via getKeyState() during emulation
```

### Key Components

- **`ApiController`** — Implements `ControllerProvider` (same interface as `KeyboardController` and `GamepadController`). The NES polls `getKeyState()` during emulation — it doesn't know or care that the inputs come from HTTP requests.
- **`EmulatorSession`** — Headless NES wrapper. Runs the CPU synchronously via `cpu.step()` with frame counting through the PPU's `imageReady` callback. Same proven pattern as the E2E test harness.
- **`ApiServer`** — Ktor routes that translate HTTP requests into `EmulatorSession` operations.

### Execution Model

The emulator runs **synchronously** — `POST /step` blocks until the requested frames have been rendered. This is intentional:
- Deterministic: same inputs always produce the same outputs
- No race conditions between HTTP requests and emulation
- Agent can reason about exact frame counts
- Same approach used by Gymnasium/Stable-Retro

---

## Inspiration & Alternatives

### Why a REST API?

The goal was to make the emulator controllable by **any client** — AI agents (Python, JS, Go), TAS tools, web dashboards, LLM tool-use, or simple curl scripts. REST is the lowest common denominator: every language has an HTTP client.

### Considered Alternatives

| Approach | Why Not Chosen |
|----------|---------------|
| **Python binding (gym-retro style)** | Requires Python + native bindings. kNES is JVM-native — a REST API is more natural and language-agnostic. |
| **gRPC** | Better performance for high-frequency agents, but adds protobuf complexity and tooling. REST is sufficient for 60fps frame stepping. Can be added later if needed. |
| **WebSocket streaming** | Good for real-time observation but adds state management complexity. The synchronous step model is simpler and sufficient for most agents. Could be added as a complementary channel. |
| **Direct JVM library** | Lowest latency, but locks clients to JVM. The `EmulatorSession` class can still be used directly from Kotlin/Java without the HTTP layer. |
| **Embedded in Compose UI** | Would couple the API to the desktop app. Separate module keeps concerns clean — the API server is headless by design. |

### Prior Art

- **[OpenAI Gym Retro](https://github.com/openai/retro) / [Stable-Retro](https://stable-retro.farama.org/)** — Python library wrapping Libretro emulators with `env.step(action) → observation`. Our `POST /step` follows the same pattern over HTTP.
- **[NousResearch/pokemon-agent](https://github.com/NousResearch/pokemon-agent)** — FastAPI server wrapping PyBoy with `/action`, `/state`, `/screenshot` endpoints. Direct inspiration for our endpoint design.
- **[FCEUX FM2 format](https://fceux.com/web/FM2.html)** — The de facto standard for NES TAS input recording. Our `/fm2` endpoint accepts this format directly.
- **[Gymnasium API](https://gymnasium.farama.org/)** — The standard RL environment interface. Our step-based model mirrors `env.step(action)`.

### Why Ktor?

- **Kotlin-native** — same language as the rest of kNES, same JetBrains ecosystem
- **Embedded** — no external server needed, starts in milliseconds
- **Lightweight** — Netty engine, ~5MB dependency footprint
- **Test-friendly** — `testApplication` API for in-process HTTP testing without starting a real server
