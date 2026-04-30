# E2E Game Testing with Headless Emulator

## Goals

- Verify the emulator runs real games correctly by testing observable game behavior
- Load a ROM (Super Mario Bros), inject controller inputs, and assert on game state via memory reads
- Tests are headless (no UI), deterministic (step-based), and CI-friendly (skip if ROM not present)

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Execution model | Headless step-based (`cpu.step()`) | Deterministic, no threading, no timing flakiness |
| Input injection | `TestInputHandler` implementing `InputHandler` | Direct control of button state, no AWT/Compose dependency |
| Assertions | Read NES RAM addresses | Precise, deterministic, standard approach (TAS/speedrun tools use this) |
| ROM distribution | Not committed; env var / system property / default path | Copyright — SMB ROM can't be in the repo |
| Missing ROM behavior | Skip test via Kotest `assume()` | CI stays green without ROMs |
| Test framework | Kotest FunSpec (same as existing tests) | Consistency with existing test suite |

## Architecture

### EmulatorTestHarness

Reusable wrapper for headless game testing. Located at `knes-emulator/src/test/kotlin/knes/emulator/e2e/EmulatorTestHarness.kt`.

**Construction:**
- Creates headless NES with no-op GUI, `TestInputHandler`, and frame-counting `imageReady` callback
- Disables `Globals.appletMode`, `Globals.enableSound`, `Globals.palEmulation`

**Public API:**
```kotlin
class EmulatorTestHarness(romPath: String) {
    val nes: NES
    var frameCount: Int

    fun advanceFrames(n: Int)
    fun advanceUntil(maxFrames: Int, condition: () -> Boolean): Boolean
    fun pressButton(key: Int)
    fun releaseButton(key: Int)
    fun readMemory(addr: Int): Int
}
```

**Frame advance mechanism:**
`advanceFrames(n)` calls `cpu.step()` in a loop. Each time the PPU completes a frame, `imageReady` fires and increments `frameCount`. The method stops when N new frames have been rendered. This naturally matches NES timing (~29780 CPU cycles per frame) without hardcoding cycle counts.

**`advanceUntil(maxFrames, condition)`** advances frames one at a time until `condition()` returns true or `maxFrames` is reached. Returns whether the condition was met. Useful for waiting for game state transitions that take a variable number of frames.

### TestInputHandler

Simple `InputHandler` implementation with `pressButton(key)` / `releaseButton(key)` methods. Returns `0x41` (pressed) or `0x40` (not pressed). Defined inline in `EmulatorTestHarness`.

### ROM Path Resolution

Checked in order:
1. System property: `knes.test.rom.smb`
2. Environment variable: `KNES_TEST_ROM_SMB`
3. Default path: `../roms/smb.nes` (relative to project working directory)

If none resolves to an existing file, tests are skipped via `io.kotest.assumptions.assumeThat`.

## Test Scenarios

### File: `knes-emulator/src/test/kotlin/knes/emulator/e2e/SuperMarioBrosTest.kt`

### Test 1: Title screen to gameplay

**Steps:**
1. Load Super Mario Bros ROM
2. Advance ~120 frames — title screen loads and demo starts
3. Assert game mode at `$0770` indicates title/demo state
4. Press Start button
5. Advance ~120 frames — game transitions to gameplay
6. Assert game mode at `$0770` changed (no longer title/demo)

### Test 2: Mario walks right

**Steps:**
1. Load ROM, navigate past title screen (press Start, advance frames)
2. Wait for gameplay to be active using `advanceUntil` checking `$0770`
3. Read Mario X position at `$0086`, store as `initialX`
4. Press and hold Right button
5. Advance ~60 frames (1 second of gameplay)
6. Read Mario X position at `$0086` again
7. Assert new X position > `initialX`
8. Release Right button

## SMB Memory Map (Relevant Addresses)

| Address | Description | Values |
|---------|-------------|--------|
| `$0770` | Game engine state | 0 = title/loading, various non-zero = gameplay active |
| `$0086` | Player X position (on-screen) | 0-255 |
| `$00CE` | Player Y position (on-screen) | 0-255 |
| `$075A` | Lives remaining | 0-based count |
| `$000E` | Player horizontal speed | Increases when moving |

## File Structure

```
knes-emulator/src/test/kotlin/knes/emulator/e2e/
    EmulatorTestHarness.kt      # Reusable headless emulator harness
    SuperMarioBrosTest.kt       # SMB E2E test scenarios
```

No new dependencies needed. No build.gradle changes.
