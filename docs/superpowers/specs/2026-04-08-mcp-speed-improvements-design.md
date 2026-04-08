# MCP Speed Improvements: Screenshot Flag, Tap, and Sequence Tools

**Date**: 2026-04-08
**Status**: Proposed
**Problem**: MCP gameplay is slow due to excessive tool call round-trips. A simple "press A and check screen" requires 3 calls. Menu navigation takes 10+ calls.

## Solution

Three changes to reduce tool call count by 3-10x:

1. Add `screenshot` flag to `step` — return screen + RAM in one response
2. New `tap` tool — repeated button presses in one call
3. New `sequence` tool — expose `StepSequence` as a dedicated MCP tool

## 1. Step Screenshot Flag

### REST API Changes

Modify `StepRequest` to include screenshot flag:

```kotlin
@Serializable data class StepRequest(
    val buttons: List<String> = emptyList(),
    val frames: Int = 1,
    val screenshot: Boolean = false
)
```

Modify `StepResponse` to optionally include screenshot:

```kotlin
@Serializable data class StepResponse(
    val frame: Int,
    val ram: Map<String, Int> = emptyMap(),
    val screenshot: String? = null  // base64 PNG when requested
)
```

The `/step` route handler populates `screenshot` from `session.getScreenBase64()` when the last step in the request has `screenshot = true`. For `StepSequence`, the screenshot flag on the sequence-level request controls whether to capture after all steps complete.

### MCP Tool Changes

Add `screenshot` parameter to the `step` tool schema:

```
step(buttons?: string[], frames?: int, screenshot?: boolean)
```

When `screenshot: true`, the MCP tool returns both:
- `TextContent` with JSON (frame, RAM values)
- `ImageContent` with the base64 PNG

When `screenshot: false` (default), behavior is unchanged — only `TextContent` with JSON.

### StepSequence Screenshot

`StepSequence` also gets a screenshot flag:

```kotlin
@Serializable data class StepSequence(
    val sequence: List<StepRequest>,
    val screenshot: Boolean = false
)
```

The screenshot is captured once after the entire sequence completes, not after each step.

## 2. Tap Tool

A convenience tool that presses a single button N times with configurable timing.

### REST API

New endpoint `POST /tap`:

```kotlin
@Serializable data class TapRequest(
    val button: String,
    val count: Int = 1,
    val pressFrames: Int = 5,
    val gapFrames: Int = 15,
    val screenshot: Boolean = false
)
```

Implementation: Build a `StepSequence` internally — `count` repetitions of `[{[button], pressFrames}, {[], gapFrames}]` — and feed it through the existing step machinery (queue in shared mode, `setButtons` + `advanceFrames` in standalone).

Response: Same `StepResponse` (frame, RAM, optional screenshot).

### MCP Tool

```
tap(button: string, count?: int, press_frames?: int, gap_frames?: int, screenshot?: boolean)
```

- `button` (required): Button name — A, B, START, SELECT, UP, DOWN, LEFT, RIGHT
- `count` (default 1): Number of presses
- `press_frames` (default 5): Frames to hold each press
- `gap_frames` (default 15): Frames to wait between presses
- `screenshot` (default false): Include screenshot in response

### Examples

| Action | Tool call | Frames | Old calls |
|--------|-----------|--------|-----------|
| Mash A through 5 dialogs | `tap("A", 5)` | 100 | 10 |
| Press START once | `tap("START")` | 20 | 2 |
| Fast dialog skip | `tap("A", 10, press_frames: 3, gap_frames: 10)` | 130 | 20 |

## 3. Sequence Tool

Exposes the existing `StepSequence` REST endpoint as a dedicated MCP tool. Currently the only way to send a sequence is via the `step` tool with a `{"sequence": [...]}` JSON body, which is undiscoverable.

### MCP Tool

```
sequence(steps: [{buttons: string[], frames: int}], screenshot?: boolean)
```

- `steps` (required): Array of `{buttons, frames}` entries
- `screenshot` (default false): Include screenshot after all steps complete

### REST API

No new endpoint needed — uses existing `POST /step` with `StepSequence` body. The `screenshot` field on `StepSequence` (added in section 1) controls screenshot capture.

### Examples

| Action | Tool call | Old calls |
|--------|-----------|-----------|
| Navigate down 2, select | `sequence([{DOWN,5},{[],10},{DOWN,5},{[],10},{A,5},{[],20}])` | 12 → 1 |
| Walk right then up | `sequence([{RIGHT,32},{UP,16}])` | 4 → 1 |
| Battle: all 4 chars FIGHT | `sequence([{A,5},{[],15}] * 8)` (4 confirms + 4 targets) | 16 → 1 |

## Impact

| Scenario | Before (calls) | After (calls) | Reduction |
|----------|----------------|---------------|-----------|
| Press A + check screen | 3 (step, wait, get_screen) | 1 (step w/ screenshot) | 67% |
| Mash through 5 dialogs + screen | 12 | 1 (tap w/ screenshot) | 92% |
| Navigate 3-item menu + select + screen | 10 | 1 (sequence w/ screenshot) | 90% |
| Walk 5 tiles + check | 7 | 1 (sequence w/ screenshot) | 86% |

## Files Affected

| Module | File | Change |
|--------|------|--------|
| knes-api | `ApiServer.kt` | Add `/tap` endpoint, modify `/step` to handle `screenshot` field in response |
| knes-api | `ApiServer.kt` (data classes) | Modify `StepRequest`, `StepResponse`, `StepSequence`; add `TapRequest` |
| knes-api | `ApiServerTest.kt` | Tests for `/tap`, screenshot in `/step` response |
| knes-mcp | `McpServer.kt` | Add `tap` tool, add `sequence` tool, add `screenshot` param to `step` tool |
| knes-mcp | `McpServer.kt` | Modify step response to return `ImageContent` when screenshot requested |

## Testing

- Unit test: `/step` with `screenshot: true` returns base64 image in response
- Unit test: `/step` with `screenshot: false` (default) returns no image
- Unit test: `/tap` with count=3 advances correct number of frames
- Unit test: `/tap` with custom press/gap frames
- Unit test: `StepSequence` with `screenshot: true`
- Integration: `tap` in shared mode uses InputQueue correctly
