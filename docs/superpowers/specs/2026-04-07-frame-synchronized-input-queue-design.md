# Frame-Synchronized Input Queue

**Date**: 2026-04-07
**Status**: Proposed
**Problem**: Short button presses via MCP `step` tool don't reliably register in shared mode

## Problem

In shared mode, the Compose UI drives the NES CPU on its own thread at 60fps. When MCP calls `step(buttons: ["A"], frames: 5)`, the API thread sets button state via `ApiController.setButtons()` and then waits for 5 frames to pass. There is no synchronization between the API thread writing button state and the NES CPU thread reading it via `$4016` joypad polling. Short presses race against the NES's per-frame input polling and get missed.

## Solution

A frame-synchronized input queue that guarantees each enqueued button state is visible to the NES for exactly one frame. The queue is consumed at frame boundaries (in `imageReady`), ensuring the NES sees the intended input during the subsequent frame's CPU execution.

## Architecture

### New: `InputQueue` (in `knes-api`)

```kotlin
data class FrameInput(val buttons: Set<Int>)  // NES InputHandler key indices

class InputQueue {
    private val queue: ConcurrentLinkedQueue<FrameInput>
    @Volatile var currentFrame: FrameInput? = null
        private set
    private var completionLatch: CountDownLatch? = null

    fun enqueue(inputs: List<FrameInput>): CountDownLatch
    fun advanceFrame()   // count down latch, pop next entry into currentFrame
    fun isPressed(padKey: Int): Boolean  // check currentFrame
    val isActive: Boolean  // true when currentFrame is non-null
}
```

- `enqueue()` sets `currentFrame` to the first entry immediately (so it's visible during the current frame's remaining CPU execution), queues the rest, and returns a `CountDownLatch(inputs.size)`.
- `advanceFrame()` counts down the latch (for the frame that just completed), then pops the next entry into `currentFrame` (or sets null if queue is empty). Called once per frame from `imageReady`.
- `isPressed()` returns `currentFrame?.buttons?.contains(padKey) == true`.
- Thread safety: `enqueue` is called from the API/Ktor thread; `advanceFrame` is called from the UI thread (via `imageReady`). `currentFrame` is `@Volatile` so writes from either thread are visible to the other. The `ConcurrentLinkedQueue` handles concurrent access to the queue itself.

### Modified: `ApiController`

- Add `val inputQueue = InputQueue()` field.
- `getKeyState(padKey)`: merge persistent `keyStates[padKey]` OR `inputQueue.isPressed(padKey)` — either being pressed = pressed (0x41).
- New `fun onFrameBoundary()`: calls `inputQueue.advanceFrame()`.
- New `fun enqueueSteps(steps: List<StepRequest>): CountDownLatch`: converts `StepRequest` list to `FrameInput` list, calls `inputQueue.enqueue()`.

### Modified: Frame boundary wiring

In `ComposeMain.kt`, the `LaunchedEffect(apiRunning)` block already wires `screenView.onApiFrameCallback`. Extend this callback to also call `apiServer.session.controller.onFrameBoundary()`:

```kotlin
screenView.onApiFrameCallback = { buffer ->
    apiServer.session.controller.onFrameBoundary()
    apiServer.session.updateFrameBuffer(buffer)
}
```

`onFrameBoundary()` is called on the UI thread, same thread as CPU execution — no race condition.

### Modified: `/step` route in `ApiServer.kt`

Replace the current `setButtons` + `advanceFrames` pattern:

```kotlin
post("/step") {
    // ... validation ...
    if (session.shared) {
        val latch = session.controller.enqueueSteps(steps)
        val timeoutMs = steps.sumOf { it.frames } * 50L + 5000L
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            // timeout error
        }
    } else {
        // standalone: setButtons + advanceFrames (existing behavior)
        for (step in steps) {
            session.controller.setButtons(step.buttons)
            session.advanceFrames(step.frames)
        }
    }
    call.respond(StepResponse(session.frameCount, session.getWatchedState()))
}
```

### Modified: `NesEmulatorSession` (MCP standalone mode)

Add same `InputQueue` for consistency. In the `step()` method's `while (frameCount < target)` loop, call `onFrameBoundary()` when `frameCount` increments (detected via the `imageReady` callback incrementing `frameCount`).

### Unchanged: `press`/`release`

Persistent button holds via `keyStates` in `ApiController` continue unchanged. They merge with queue input in `getKeyState` — either source being pressed = pressed. This means `press("A")` still works for holding a button across multiple `step` calls or while the game runs freely.

## Data Flow

### `step(["A"], 5)` in shared mode:

```
MCP step(["A"], 5)
  → controller.enqueueSteps([StepRequest(["A"], 5)])
  → InputQueue: currentFrame = {A}, queue = [{A}, {A}, {A}, {A}], latch = CountDownLatch(5)
  → latch.await()

UI thread, frame N:
  CPU runs → game reads $4016 → getKeyState(KEY_A) → currentFrame has A → 0x41 ✓
  PPU done → imageReady → onFrameBoundary → latch.countDown(), pop next

UI thread, frame N+4 (last queued frame):
  CPU runs → sees A ✓
  PPU done → imageReady → onFrameBoundary → latch.countDown() (reaches 0), currentFrame = null

MCP: latch.await() returns → respond with state
```

### `StepSequence` (press A 5 frames, wait 10, press A 5):

```
Flattened: 5×{A} + 10×{} + 5×{A} = 20 FrameInput entries
Single latch = CountDownLatch(20)
Queue consumed over 20 frames
```

## Edge Cases

- **Queue already active when new step arrives**: The `/step` handler awaits its latch, so concurrent step calls from the same MCP session are serialized (MCP is request-response). If a second HTTP client hits `/step` while the first is waiting, the second enqueue appends to the queue. Each `enqueue` call returns its own latch tracking only its entries. `advanceFrame` counts down the oldest active latch first (FIFO).
- **Keyboard input during queue playback**: Merged. If the user holds a keyboard button while the queue is active, `getKeyState` returns pressed if either source says pressed. This is the same merge behavior as today.
- **Empty buttons in step**: `step([], 30)` enqueues 30 × `FrameInput(emptySet())`. Queue is active but no buttons pressed — this correctly represents "advance 30 frames with no API buttons" while still letting keyboard input through.
- **Game paused/not running**: `imageReady` won't be called, `advanceFrame` won't fire, latch times out. Same behavior as current `advanceFrames` timeout.

## Testing

- Unit test `InputQueue`: enqueue, advanceFrame, isPressed, latch completion
- Unit test `ApiController.getKeyState` merging queue + persistent holds
- Integration test: `/step` with short press registers in frame count
- E2E test: step sequence navigates FF1/SMB menu reliably
