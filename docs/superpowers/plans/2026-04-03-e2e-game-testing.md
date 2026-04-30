# E2E Game Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Test real game behavior (Super Mario Bros) by running the emulator headless, injecting inputs, and asserting on game state via NES RAM reads.

**Architecture:** A reusable `EmulatorTestHarness` wraps the NES with a `TestInputHandler` and frame-counting callback. Tests load a ROM (path from env var, skipped if absent), advance frames via `cpu.step()`, inject button presses, and read memory to verify game behavior.

**Tech Stack:** Kotest 6.1.4, knes-emulator, headless NES (no UI dependencies)

---

## File Map

### New Files

| File | Purpose |
|------|---------|
| `knes-emulator/src/test/kotlin/knes/emulator/e2e/EmulatorTestHarness.kt` | Reusable headless emulator: load ROM, advance frames, inject input, read memory |
| `knes-emulator/src/test/kotlin/knes/emulator/e2e/SuperMarioBrosTest.kt` | SMB E2E tests: title→start, walk right |

---

### Task 1: Create EmulatorTestHarness

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/e2e/EmulatorTestHarness.kt`

- [ ] **Step 1: Create the harness file**

```kotlin
package knes.emulator.e2e

import knes.emulator.Memory
import knes.emulator.NES
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.io.File

class EmulatorTestHarness(romPath: String) {

    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    private val inputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = keyStates[padKey]
    }

    var frameCount: Int = 0
        private set

    val nes: NES

    init {
        Globals.appletMode = false
        Globals.enableSound = false
        Globals.palEmulation = false

        val gui = object : GUI {
            override fun sendErrorMsg(message: String) {}
            override fun sendDebugMessage(message: String) {}
            override fun destroy() {}
            override fun getJoy1(): InputHandler = inputHandler
            override fun getJoy2(): InputHandler? = null
            override fun getTimer(): HiResTimer = HiResTimer()
            override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
                frameCount++
            }
        }

        nes = NES(gui)

        val loaded = nes.loadRom(romPath)
        if (!loaded) {
            throw IllegalArgumentException("Failed to load ROM: $romPath")
        }

        // Clear zero-page RAM for deterministic behavior
        for (i in 0 until 0x0800) {
            nes.cpuMemory.write(i, 0x00.toShort())
        }
    }

    fun advanceFrames(n: Int) {
        val targetFrame = frameCount + n
        while (frameCount < targetFrame) {
            nes.cpu.step()
        }
    }

    fun advanceUntil(maxFrames: Int, condition: () -> Boolean): Boolean {
        val startFrame = frameCount
        while (frameCount - startFrame < maxFrames) {
            nes.cpu.step()
            if (condition()) return true
        }
        return false
    }

    fun pressButton(key: Int) {
        keyStates[key] = 0x41
    }

    fun releaseButton(key: Int) {
        keyStates[key] = 0x40
    }

    fun readMemory(addr: Int): Int {
        return nes.cpuMemory.load(addr).toInt() and 0xFF
    }

    companion object {
        fun findSmb(): String? {
            // 1. System property
            System.getProperty("knes.test.rom.smb")?.let {
                if (File(it).exists()) return it
            }
            // 2. Environment variable
            System.getenv("KNES_TEST_ROM_SMB")?.let {
                if (File(it).exists()) return it
            }
            // 3. Default path
            val defaultPath = File("roms/smb.nes")
            if (defaultPath.exists()) return defaultPath.absolutePath

            return null
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :knes-emulator:compileTestKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/e2e/EmulatorTestHarness.kt
git commit -m "Add EmulatorTestHarness for headless E2E game testing"
```

---

### Task 2: Create SuperMarioBrosTest

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/e2e/SuperMarioBrosTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package knes.emulator.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldNotBe

class SuperMarioBrosTest : FunSpec({

    val romPath = EmulatorTestHarness.findSmb()

    beforeEach {
        if (romPath == null) {
            throw io.kotest.assumptions.AssumptionFailedException(
                "SMB ROM not found. Set KNES_TEST_ROM_SMB env var or place ROM at roms/smb.nes"
            )
        }
    }

    test("title screen transitions to gameplay when Start is pressed") {
        val h = EmulatorTestHarness(romPath!!)

        // Wait for title screen to load
        h.advanceFrames(120)

        // Verify we're on the title/demo screen
        // $0770 = game engine state, during title screen it cycles through demo states
        val titleState = h.readMemory(0x0770)

        // Press Start to begin the game
        h.pressButton(knes.emulator.input.InputHandler.KEY_START)
        h.advanceFrames(5)
        h.releaseButton(knes.emulator.input.InputHandler.KEY_START)

        // Wait for game to transition to gameplay
        val transitioned = h.advanceUntil(300) {
            // Game engine state changes when gameplay begins
            // World/level data gets initialized, player state becomes active
            h.readMemory(0x0770) != titleState
        }

        transitioned shouldNotBe false
    }

    test("Mario moves right when Right button is held") {
        val h = EmulatorTestHarness(romPath!!)

        // Navigate past title screen
        h.advanceFrames(120)
        h.pressButton(knes.emulator.input.InputHandler.KEY_START)
        h.advanceFrames(5)
        h.releaseButton(knes.emulator.input.InputHandler.KEY_START)

        // Wait for gameplay to be fully active
        h.advanceFrames(180)

        // Read initial X position
        val initialX = h.readMemory(0x0086)

        // Hold Right for 60 frames (1 second)
        h.pressButton(knes.emulator.input.InputHandler.KEY_RIGHT)
        h.advanceFrames(60)
        h.releaseButton(knes.emulator.input.InputHandler.KEY_RIGHT)

        // Verify Mario moved right
        val finalX = h.readMemory(0x0086)
        finalX shouldBeGreaterThan initialX
    }
})
```

- [ ] **Step 2: Run tests without ROM to verify skip behavior**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.e2e.SuperMarioBrosTest"`

Expected: BUILD SUCCESSFUL — tests skipped (not failed) with message about missing ROM

- [ ] **Step 3: Run tests WITH ROM to verify they pass**

Run: `KNES_TEST_ROM_SMB=/path/to/your/smb.nes ./gradlew :knes-emulator:test --tests "knes.emulator.e2e.SuperMarioBrosTest" --info`

Expected: Both tests PASS

Note: If tests fail, the memory addresses or frame counts may need adjustment. Common issues:
- `$0770` might need a different check — try printing the value at various points to find the right transition
- Frame counts may need to be higher if the game takes longer to load
- The `advanceUntil` condition may need tuning based on actual game state values

- [ ] **Step 4: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/e2e/SuperMarioBrosTest.kt
git commit -m "Add Super Mario Bros E2E tests (title→start, walk right)"
```

---

### Task 3: Verify Full Suite & Clean Up

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL — all existing tests pass, SMB tests skipped (unless ROM is available)

- [ ] **Step 2: Run full suite with ROM if available**

Run: `KNES_TEST_ROM_SMB=/path/to/your/smb.nes ./gradlew test`

Expected: BUILD SUCCESSFUL — all tests pass including SMB E2E tests

- [ ] **Step 3: Commit any fixes**

If any adjustments were needed (frame counts, memory addresses), commit them now.
