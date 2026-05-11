# KnesSave — unified agent save format v1

**Status:** approved (brainstorming complete), ready for planning
**Author:** Artur (with Claude, 2026-05-10)
**Module:** `knes-agent-tools` (shared between MCP and agent)

## Problem

Today the agent persists state across savestate-load cycles via TWO sibling files:

- `*.savestate` — raw emulator bytes (NES RAM/PPU/CPU snapshot)
- `*.actions.json` — `AgentScratchpad` (per-action notebook; the agent's "where I came from" memory)

This dual-file format works for `KNES_FF1_LOAD_SAVESTATE` boot but:

- MCP has no `save_session`/`load_session` tools at all
- The pair must travel together; copying one without the other silently breaks restore
- There's no place to persist higher-level state: current intent, decision log, landmarks, visited-tile bitmap

We want MCP to expose a single save/load operation that produces ONE self-contained JSON file with everything the agent needs to resume from cold.

## Target format

```json
{
  "schemaVersion": 1,
  "createdAtMs": 1715342400000,
  "rom": "ff1.nes",
  "emulatorState": "<base64 of raw .savestate bytes>",
  "currentIntent": "leave Pravoka south",
  "recentMoves":  [/* last 8 move attempts with outcome */],
  "decisionLog":  [/* last 30 reasoning steps */],
  "landmarks":    { "kings": [...], "shops": [...], "inns": [...], "bridges": [...] },
  "visitedMinimap": { "width": 32, "height": 32, "bitsBase64": "..." }
}
```

The file is self-contained: base64-inline emulator bytes mean no sister files, no path-rewriting on copy/move.

## Decisions (with rationale)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Wrapper, not replacement.** `KnesSave` is a serialization shell; `AgentScratchpad` and `LandmarkMemory` stay as the in-memory authority. | Existing skills (boot, BuyAtShop, EquipWeapon) already write to those classes. Rewriting them is out of scope and risks regressing hard-won fixes (e.g. spec-5 4/4 buy). |
| D2 | **Base64 inline for `emulatorState`.** | User explicit pick. Eliminates the dual-file drift class of bugs and makes MCP transfer trivial. ~50–200KB per save is fine. |
| D3 | **Visited tracking = FF1 overworld minimap (32×32 BitSet).** | Interiors are tiny and already covered by `LandmarkMemory`. Minimap RAM read is cheap. |
| D4 | **Code lives in `knes-agent-tools`.** | Shared by MCP (`knes-mcp`) and agent (`knes-agent`). No duplication. |
| D5 | **`MinimapTracker` is a stub in v1.** | Class + bitmap encode/decode shipped now. RAM→bitmap integration deferred to a follow-up spec. v1 saves zeros if no tracker is wired. |
| D6 | **`decisionLog` rides on `AgentScratchpad` via `kind="decision"`.** | One ordered stream by `seq` = one source of truth. No new persistence path. |
| D7 | **Defaults: 8 recent moves, 30 recent decisions.** | Fits an LLM prompt budget. Configurable per save call. |

## Components

### 1. Data classes — `knes-agent-tools/src/main/kotlin/knes/agent/tools/save/KnesSave.kt`

```kotlin
@Serializable
data class KnesSave(
    val schemaVersion: Int = 1,
    val createdAtMs: Long,
    val rom: String,
    val emulatorState: String,         // base64
    val currentIntent: String,
    val recentMoves: List<MoveEntry>,
    val decisionLog: List<DecisionEntry>,
    val landmarks: LandmarksSnapshot,
    val visitedMinimap: VisitedMinimap,
)

@Serializable
data class MoveEntry(
    val seq: Int, val tMs: Long,
    val dir: String,                   // UP/DOWN/LEFT/RIGHT
    val smPre: List<Int>?, val smPost: List<Int>?,
    val moved: Boolean?,
    val mapflagsPost: Int?,
    val note: String? = null,
)

@Serializable
data class DecisionEntry(
    val seq: Int, val tMs: Long,
    val phase: String,                 // "buy", "exit", "walk-vision", ...
    val reasoning: String,             // one-line summary of why
    val action: String,                // what we did
    val outcome: String? = null,       // observed result (filled in next tick if known)
)

@Serializable
data class LandmarksSnapshot(
    val kings: List<LandmarkRef> = emptyList(),
    val shops: List<LandmarkRef> = emptyList(),
    val inns: List<LandmarkRef> = emptyList(),
    val bridges: List<LandmarkRef> = emptyList(),
    val other: List<LandmarkRef> = emptyList(),
)

@Serializable
data class LandmarkRef(
    val mapId: Int, val x: Int, val y: Int,
    val label: String,
)

@Serializable
data class VisitedMinimap(
    val width: Int = 32, val height: Int = 32,
    val bitsBase64: String,            // BitSet 32x32 = 128 bytes raw
)
```

### 2. Codec — `SaveFormatCodec`

```kotlin
object SaveFormatCodec {
    fun encode(
        emulatorStateBytes: ByteArray,
        rom: String,
        intent: String,
        scratchpad: AgentScratchpad,
        landmarks: LandmarkMemory,
        minimap: MinimapTracker,
        recentMovesN: Int = 8,
        decisionLogN: Int = 30,
    ): KnesSave

    fun decode(save: KnesSave): RestoredState
    // RestoredState = { emulatorStateBytes, intent, scratchpad, landmarks, minimap }

    fun toJson(save: KnesSave): String     // pretty-printed
    fun fromJson(json: String): KnesSave
}
```

Codec responsibilities:
- base64 encode/decode of `emulatorState`
- pull last-N `kind in {"tap","walk"}` entries from `AgentScratchpad` → `recentMoves`
- pull last-M `kind == "decision"` entries → `decisionLog`
- snapshot `LandmarkMemory` grouped by type
- snapshot `MinimapTracker.toBitSet()` → base64

### 3. `AgentScratchpad` extension

Add a typed helper (no schema change — `kind` field already accepts arbitrary strings):

```kotlin
fun recordDecision(phase: String, reasoning: String, action: String, outcome: String? = null) {
    record(kind = "decision", summary = "$phase: $action — $reasoning", note = outcome)
}
fun recentMoves(n: Int = 8): List<MoveEntry>     // filters kind in {tap, walk}
fun recentDecisions(n: Int = 30): List<DecisionEntry>
```

Wiring call sites is out of scope for this spec — v1 just ships the API; existing skills adopt it incrementally.

### 4. `MinimapTracker` (stub in v1)

```kotlin
class MinimapTracker(val width: Int = 32, val height: Int = 32) {
    private val bits = java.util.BitSet(width * height)
    fun markVisited(x: Int, y: Int) { bits.set(y * width + x) }
    fun toBitSet(): BitSet = bits
    fun toBase64(): String
    companion object { fun fromBase64(s: String, w: Int, h: Int): MinimapTracker }
}
```

No RAM-read integration in v1. The agent constructs a tracker, optionally feeds it, codec serializes whatever's in it.

### 5. MCP tools — added to both `McpServer.kt` and `RemoteRestBridge.kt`

- **`save_session(path: String, intent: String? = null)`**
  - Reads emulator bytes via existing savestate API
  - Pulls scratchpad / landmarks / minimap from the active `AgentSession`
  - Writes `KnesSave` JSON to `path`
  - Returns `{ok, bytesWritten, summary}`

- **`load_session(path: String)`**
  - Parses `KnesSave` from `path`
  - Decodes base64 → tmp `.savestate` file → loads into emulator
  - Hydrates `AgentScratchpad` (replays entries by seq), `LandmarkMemory`, `MinimapTracker` into the active session
  - Returns `{ok, rom, intent, frameCount, summary}`

For the in-process MCP (`McpServer.kt`) we hold an `AgentSession` directly. For the REST bridge (`RemoteRestBridge.kt`) we add `/session/save` and `/session/load` endpoints that the bridge calls.

## Testing

Unit tests in `knes-agent-tools`:

- **Round-trip:** `encode(...).let { fromJson(toJson(it)) }` equals original (golden bytes preserved through base64).
- **Pruning:** scratchpad with 100 entries → `recentMoves(8)` returns the 8 most recent of the right `kind`.
- **Empty minimap:** stub tracker produces 128 bytes of zeros, base64 round-trips.
- **Landmark grouping:** `LandmarkMemory` with mixed types maps to the right `LandmarksSnapshot` buckets.
- **Schema version mismatch:** loading `schemaVersion = 2` from a v1 codec fails loudly (no silent ignore).

MCP integration test:

- Boot ROM → step 60 frames → `save_session("/tmp/test.knes-save.json")` → reset → `load_session(...)` → assert frame count and a watched RAM byte match pre-save.

## Out of scope (explicitly)

- Wiring `recordDecision` into BuyAtShop / WalkInteriorVision / ExitInterior — separate spec(s).
- Reading FF1 minimap RAM into `MinimapTracker` — separate spec.
- Migrating existing `.savestate` + `.actions.json` fixtures to `KnesSave` format — leave the fixtures alone; both formats coexist.
- Compression, encryption, signing.
- Schema version migration code (v1 is the first version; migrations come when v2 lands).

## Acceptance

1. `KnesSave` data classes serialize/deserialize via kotlinx.serialization.
2. `SaveFormatCodec.encode` + `decode` round-trip preserves emulator bytes exactly.
3. `save_session` / `load_session` MCP tools work via the in-process MCP (`createMcpServer`).
4. Round-trip test: save → reset → load → emulator at same frame, RAM matches, scratchpad/landmarks restored.
5. No regression in spec-5 buy fixtures (`SaveStateRoundTripTest` still green).
