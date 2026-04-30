# kNES API Server

## Goals

- Expose the emulator as a REST API for AI agents, TAS tools, and external clients
- Enable programmatic control: load ROMs, send inputs, read game state, capture screen
- Use the standard `ControllerProvider` interface — the API is just another controller
- New `knes-api` module — no changes to `knes-emulator` or `knes-controllers`

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Module | New `knes-api` | External layer, emulator stays dependency-free |
| HTTP framework | Ktor (embedded Netty) | Kotlin-native, JetBrains ecosystem, coroutine-friendly |
| Input interface | Implements `ControllerProvider` | Standard interface, same as keyboard/gamepad controllers |
| Execution model | Headless NES with `appletMode=true` | Same as E2E test harness — frame-based stepping |
| Frame format | PNG for screen, JSON for state | Standard, easy to consume from any language |

## Architecture

```
knes-api (new module)
├── depends on: knes-emulator, knes-controllers
├── ApiServer.kt          — Ktor server setup and routes
├── ApiController.kt      — ControllerProvider implementation (REST-driven)
├── EmulatorSession.kt    — Wraps NES lifecycle (load, run, step, state)
└── main()                — Entry point: start server on port 8080
```

The API server creates a headless NES instance with `ApiController` as the input handler. REST endpoints translate HTTP requests into `ControllerProvider` calls and NES operations.

```
HTTP Client (agent/TAS tool)
    ↓ REST
ApiServer (Ktor routes)
    ↓
EmulatorSession (NES lifecycle)
    ↓
ApiController implements ControllerProvider
    ↓
NES ← reads ApiController.getKeyState() during emulation
```

## API Endpoints

### Emulator Lifecycle

#### `POST /rom`
Load a ROM file. Body: multipart file upload or `{"path": "/absolute/path.nes"}`.

Response:
```json
{"status": "loaded", "mapper": 1, "prgBanks": 8, "chrBanks": 16}
```

#### `POST /reset`
Reset the emulator to power-on state.

Response: `{"status": "reset"}`

#### `GET /health`
Health check.

Response: `{"status": "ok", "romLoaded": true, "frames": 1234}`

### Core Agent API

#### `POST /step`
**The primary endpoint.** Send button state, advance N frames, get observation back.

Request:
```json
{
  "buttons": ["RIGHT", "A"],
  "frames": 1
}
```

- `buttons` — array of buttons to hold during these frames. Valid: `A`, `B`, `START`, `SELECT`, `UP`, `DOWN`, `LEFT`, `RIGHT`. Empty array = no buttons pressed.
- `frames` — how many frames to advance (default 1). At 60fps, 60 frames = 1 second.

Response:
```json
{
  "frame": 1235,
  "ram": {
    "0x0086": 120,
    "0x00CE": 192
  }
}
```

- `frame` — current frame count after stepping
- `ram` — values at watched addresses (configured via `/watch`)

The response is minimal by default. Use `/screen` or `/state` for richer data.

#### `POST /step` (batch variant)
Send a sequence of input changes across multiple frames:

```json
{
  "sequence": [
    {"buttons": ["RIGHT"], "frames": 60},
    {"buttons": ["RIGHT", "A"], "frames": 10},
    {"buttons": [], "frames": 30}
  ]
}
```

Executes the full sequence atomically. Response includes final frame count and RAM snapshot.

### Observation

#### `GET /screen`
Current frame as PNG image.

Response: `image/png` (256x240)

#### `GET /screen/base64`
Current frame as base64-encoded PNG in JSON.

Response:
```json
{"frame": 1235, "image": "iVBORw0KGgo..."}
```

#### `GET /state`
Full game state snapshot.

Response:
```json
{
  "frame": 1235,
  "ram": {"0x0086": 120, "0x00CE": 192, "0x075A": 2},
  "cpu": {"pc": 32768, "a": 0, "x": 5, "y": 0, "sp": 253},
  "buttons": ["RIGHT"]
}
```

#### `POST /watch`
Configure which RAM addresses to include in `/step` and `/state` responses.

Request:
```json
{
  "addresses": {
    "playerX": "0x0086",
    "playerY": "0x00CE",
    "lives": "0x075A",
    "score": "0x07DD",
    "world": "0x075F",
    "level": "0x0760",
    "gameState": "0x0770"
  }
}
```

Response: `{"status": "ok", "watching": 7}`

After this, `/step` and `/state` responses include named values:
```json
{
  "ram": {
    "playerX": 120,
    "playerY": 192,
    "lives": 2,
    "world": 0,
    "level": 0,
    "gameState": 1
  }
}
```

### Stateful Button Control

For real-time control or agents that manage their own timing:

#### `POST /press`
Press buttons (hold until released).

Request: `{"buttons": ["RIGHT", "A"]}`

Response: `{"status": "ok", "held": ["RIGHT", "A"]}`

#### `POST /release`
Release buttons.

Request: `{"buttons": ["RIGHT"]}`

Response: `{"status": "ok", "held": ["A"]}`

#### `POST /release-all`
Release all buttons.

Response: `{"status": "ok", "held": []}`

### TAS Compatibility

#### `POST /fm2`
Execute input from FM2 format (FCEUX movie format).

Request body (text/plain):
```
|0|R......A|........|
|0|R.......|........|
|0|........|........|
```

Each line = one frame. Button order: `RLDUTSBA`. Dot = not pressed, letter = pressed.

Response:
```json
{"framesExecuted": 3, "frame": 1238}
```

## ApiController (ControllerProvider)

```kotlin
class ApiController : ControllerProvider {
    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    fun pressButton(key: Int) { keyStates[key] = 0x41 }
    fun releaseButton(key: Int) { keyStates[key] = 0x40 }
    fun releaseAll() { keyStates.fill(0x40) }

    override fun setKeyState(keyCode: Int, isPressed: Boolean) {
        // Not used — API controls buttons directly via pressButton/releaseButton
    }

    override fun getKeyState(padKey: Int): Short = keyStates[padKey]
}
```

This implements `ControllerProvider` — the same interface used by `KeyboardController` and `GamepadController`. The NES reads button state from this during emulation, just like any other controller.

## EmulatorSession

Wraps the NES lifecycle. Internally similar to `EmulatorTestHarness` but designed for long-running server use.

```kotlin
class EmulatorSession {
    val apiController = ApiController()
    var nes: NES
    var frameCount: Int
    var watchedAddresses: Map<String, Int>  // name → address

    fun loadRom(path: String): Boolean
    fun reset()
    fun advanceFrames(n: Int)
    fun readMemory(addr: Int): Int
    fun getScreenPng(): ByteArray
    fun getWatchedState(): Map<String, Int>
}
```

## Button Names

Mapping from API string names to `InputHandler` constants:

| API Name | InputHandler Constant |
|----------|----------------------|
| `A` | `InputHandler.KEY_A` (0) |
| `B` | `InputHandler.KEY_B` (1) |
| `START` | `InputHandler.KEY_START` (2) |
| `SELECT` | `InputHandler.KEY_SELECT` (3) |
| `UP` | `InputHandler.KEY_UP` (4) |
| `DOWN` | `InputHandler.KEY_DOWN` (5) |
| `LEFT` | `InputHandler.KEY_LEFT` (6) |
| `RIGHT` | `InputHandler.KEY_RIGHT` (7) |

## Module Setup

New module `knes-api` with dependencies:
- `knes-emulator` (implementation)
- `knes-controllers` (implementation — for `ControllerProvider` interface)
- `io.ktor:ktor-server-core`
- `io.ktor:ktor-server-netty`
- `io.ktor:ktor-server-content-negotiation`
- `io.ktor:ktor-serialization-kotlinx-json`

## Running

```bash
./gradlew :knes-api:run
# Server starts on http://localhost:8080
```

## Example Agent Session

```bash
# Load ROM
curl -X POST localhost:8080/rom -H 'Content-Type: application/json' \
  -d '{"path": "/path/to/smb.nes"}'

# Watch game variables
curl -X POST localhost:8080/watch -H 'Content-Type: application/json' \
  -d '{"addresses": {"playerX": "0x0086", "world": "0x075F", "gameState": "0x0770"}}'

# Wait for title screen
curl -X POST localhost:8080/step -H 'Content-Type: application/json' \
  -d '{"buttons": [], "frames": 120}'

# Press Start
curl -X POST localhost:8080/step -H 'Content-Type: application/json' \
  -d '{"buttons": ["START"], "frames": 5}'

# Wait for game to start
curl -X POST localhost:8080/step -H 'Content-Type: application/json' \
  -d '{"buttons": [], "frames": 180}'

# Walk right for 2 seconds
curl -X POST localhost:8080/step -H 'Content-Type: application/json' \
  -d '{"buttons": ["RIGHT"], "frames": 120}'

# Check Mario's position
curl localhost:8080/state

# Get screenshot
curl localhost:8080/screen -o frame.png
```
