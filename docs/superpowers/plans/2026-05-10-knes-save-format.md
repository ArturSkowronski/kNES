# KnesSave v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a unified `KnesSave` JSON format and MCP `save_session` / `load_session` tools that round-trip emulator state plus agent context (intent, recent moves, decision log, landmarks, visited-minimap bitmap) in a single self-contained file.

**Architecture:** Pure data classes + codec live in `knes-agent-tools` (zero `knes-agent` deps). Projection adapters that wrap `AgentScratchpad` / `LandmarkMemory` live in `knes-agent`. MCP (`knes-mcp`) wires the codec to its existing `EmulatorSession.saveState()`/`loadState()` and exposes two new tools. `MinimapTracker` is a stub class in v1 — RAM→bitmap integration is a separate spec.

**Tech Stack:** Kotlin 1.9+, kotlinx-serialization-json 1.6.3, JUnit 5 (existing in `knes-agent-tools`), MCP Kotlin SDK 0.8.3.

---

## Module map

```
knes-agent-tools/src/main/kotlin/knes/agent/tools/save/
  ├── KnesSave.kt           (data classes — Task 1)
  ├── SaveFormatCodec.kt    (JSON + base64 — Task 2)
  └── MinimapTracker.kt     (stub bitmap — Task 3)
knes-agent-tools/src/test/kotlin/knes/agent/tools/save/
  ├── KnesSaveTest.kt
  ├── SaveFormatCodecTest.kt
  └── MinimapTrackerTest.kt

knes-agent/src/main/kotlin/knes/agent/save/
  ├── ScratchpadProjection.kt   (Task 4)
  └── LandmarkProjection.kt     (Task 5)
knes-agent/src/test/kotlin/knes/agent/save/
  ├── ScratchpadProjectionTest.kt
  └── LandmarkProjectionTest.kt

knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt   (Task 6 + 7 — modify)
knes-mcp/src/test/kotlin/knes/mcp/McpSaveSessionTest.kt   (Task 8 — integration)
```

---

## Task 1: `KnesSave` data classes

**Files:**
- Create: `knes-agent-tools/src/main/kotlin/knes/agent/tools/save/KnesSave.kt`
- Test: `knes-agent-tools/src/test/kotlin/knes/agent/tools/save/KnesSaveTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent-tools/src/test/kotlin/knes/agent/tools/save/KnesSaveTest.kt`:

```kotlin
package knes.agent.tools.save

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KnesSaveTest {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    @Test
    fun `KnesSave round-trips through JSON`() {
        val save = KnesSave(
            schemaVersion = 1,
            createdAtMs = 1_715_342_400_000L,
            rom = "ff1.nes",
            emulatorState = "AAEC",
            currentIntent = "leave Pravoka south",
            recentMoves = listOf(
                MoveEntry(seq = 1, tMs = 100, dir = "DOWN", smPre = listOf(7, 8),
                    smPost = listOf(7, 9), moved = true, mapflagsPost = 1, note = null)
            ),
            decisionLog = listOf(
                DecisionEntry(seq = 2, tMs = 110, phase = "exit",
                    reasoning = "south door visible", action = "tap DOWN", outcome = null)
            ),
            landmarks = LandmarksSnapshot(
                kings = listOf(LandmarkRef(mapId = 1, x = 16, y = 8, label = "King of Coneria"))
            ),
            visitedMinimap = VisitedMinimap(width = 32, height = 32, bitsBase64 = "AA=="),
        )
        val encoded = json.encodeToString(KnesSave.serializer(), save)
        val decoded = json.decodeFromString(KnesSave.serializer(), encoded)
        assertEquals(save, decoded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-agent-tools:test --tests "knes.agent.tools.save.KnesSaveTest"`
Expected: FAIL with "Unresolved reference: KnesSave".

- [ ] **Step 3: Write the data classes**

Create `knes-agent-tools/src/main/kotlin/knes/agent/tools/save/KnesSave.kt`:

```kotlin
package knes.agent.tools.save

import kotlinx.serialization.Serializable

@Serializable
data class KnesSave(
    val schemaVersion: Int = 1,
    val createdAtMs: Long,
    val rom: String,
    val emulatorState: String,
    val currentIntent: String,
    val recentMoves: List<MoveEntry> = emptyList(),
    val decisionLog: List<DecisionEntry> = emptyList(),
    val landmarks: LandmarksSnapshot = LandmarksSnapshot(),
    val visitedMinimap: VisitedMinimap = VisitedMinimap(bitsBase64 = ""),
)

@Serializable
data class MoveEntry(
    val seq: Int,
    val tMs: Long,
    val dir: String,
    val smPre: List<Int>? = null,
    val smPost: List<Int>? = null,
    val moved: Boolean? = null,
    val mapflagsPost: Int? = null,
    val note: String? = null,
)

@Serializable
data class DecisionEntry(
    val seq: Int,
    val tMs: Long,
    val phase: String,
    val reasoning: String,
    val action: String,
    val outcome: String? = null,
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
    val mapId: Int,
    val x: Int,
    val y: Int,
    val label: String,
)

@Serializable
data class VisitedMinimap(
    val width: Int = 32,
    val height: Int = 32,
    val bitsBase64: String,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-agent-tools:test --tests "knes.agent.tools.save.KnesSaveTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add knes-agent-tools/src/main/kotlin/knes/agent/tools/save/KnesSave.kt \
        knes-agent-tools/src/test/kotlin/knes/agent/tools/save/KnesSaveTest.kt
git commit -m "feat(save): add KnesSave v1 data classes with JSON round-trip"
```

---

## Task 2: `SaveFormatCodec` — base64 + JSON helpers

**Files:**
- Create: `knes-agent-tools/src/main/kotlin/knes/agent/tools/save/SaveFormatCodec.kt`
- Test: `knes-agent-tools/src/test/kotlin/knes/agent/tools/save/SaveFormatCodecTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `knes-agent-tools/src/test/kotlin/knes/agent/tools/save/SaveFormatCodecTest.kt`:

```kotlin
package knes.agent.tools.save

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaveFormatCodecTest {
    private val sampleBytes = byteArrayOf(0x00, 0x01, 0x7f, 0x42, -1, -128)

    @Test
    fun `encode wraps emulator bytes as base64`() {
        val save = SaveFormatCodec.encode(
            emulatorStateBytes = sampleBytes,
            rom = "ff1.nes",
            intent = "leave Coneria",
            recentMoves = emptyList(),
            decisionLog = emptyList(),
            landmarks = LandmarksSnapshot(),
            visitedMinimap = VisitedMinimap(bitsBase64 = ""),
        )
        assertEquals("ff1.nes", save.rom)
        assertEquals("leave Coneria", save.currentIntent)
        assertEquals(1, save.schemaVersion)
        assertTrue(save.createdAtMs > 0)
        assertArrayEquals(sampleBytes, SaveFormatCodec.decodeEmulatorBytes(save))
    }

    @Test
    fun `JSON round-trip preserves emulator bytes exactly`() {
        val save = SaveFormatCodec.encode(
            emulatorStateBytes = sampleBytes,
            rom = "ff1.nes",
            intent = "x",
            recentMoves = emptyList(),
            decisionLog = emptyList(),
            landmarks = LandmarksSnapshot(),
            visitedMinimap = VisitedMinimap(bitsBase64 = ""),
        )
        val json = SaveFormatCodec.toJson(save)
        val parsed = SaveFormatCodec.fromJson(json)
        assertArrayEquals(sampleBytes, SaveFormatCodec.decodeEmulatorBytes(parsed))
    }

    @Test
    fun `schema version mismatch fails loudly`() {
        val forged = SaveFormatCodec.toJson(SaveFormatCodec.encode(
            emulatorStateBytes = byteArrayOf(0), rom = "x", intent = "x",
            recentMoves = emptyList(), decisionLog = emptyList(),
            landmarks = LandmarksSnapshot(),
            visitedMinimap = VisitedMinimap(bitsBase64 = ""),
        )).replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertThrows(IllegalStateException::class.java) { SaveFormatCodec.fromJson(forged) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent-tools:test --tests "knes.agent.tools.save.SaveFormatCodecTest"`
Expected: FAIL with "Unresolved reference: SaveFormatCodec".

- [ ] **Step 3: Implement the codec**

Create `knes-agent-tools/src/main/kotlin/knes/agent/tools/save/SaveFormatCodec.kt`:

```kotlin
package knes.agent.tools.save

import kotlinx.serialization.json.Json
import java.util.Base64

object SaveFormatCodec {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(
        emulatorStateBytes: ByteArray,
        rom: String,
        intent: String,
        recentMoves: List<MoveEntry>,
        decisionLog: List<DecisionEntry>,
        landmarks: LandmarksSnapshot,
        visitedMinimap: VisitedMinimap,
        createdAtMs: Long = System.currentTimeMillis(),
    ): KnesSave = KnesSave(
        schemaVersion = SUPPORTED_SCHEMA_VERSION,
        createdAtMs = createdAtMs,
        rom = rom,
        emulatorState = Base64.getEncoder().encodeToString(emulatorStateBytes),
        currentIntent = intent,
        recentMoves = recentMoves,
        decisionLog = decisionLog,
        landmarks = landmarks,
        visitedMinimap = visitedMinimap,
    )

    fun decodeEmulatorBytes(save: KnesSave): ByteArray =
        Base64.getDecoder().decode(save.emulatorState)

    fun toJson(save: KnesSave): String = json.encodeToString(KnesSave.serializer(), save)

    fun fromJson(text: String): KnesSave {
        val parsed = json.decodeFromString(KnesSave.serializer(), text)
        check(parsed.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported KnesSave schemaVersion=${parsed.schemaVersion} (supported=$SUPPORTED_SCHEMA_VERSION)"
        }
        return parsed
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-agent-tools:test --tests "knes.agent.tools.save.SaveFormatCodecTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent-tools/src/main/kotlin/knes/agent/tools/save/SaveFormatCodec.kt \
        knes-agent-tools/src/test/kotlin/knes/agent/tools/save/SaveFormatCodecTest.kt
git commit -m "feat(save): SaveFormatCodec — base64 + JSON with schema-version guard"
```

---

## Task 3: `MinimapTracker` stub

**Files:**
- Create: `knes-agent-tools/src/main/kotlin/knes/agent/tools/save/MinimapTracker.kt`
- Test: `knes-agent-tools/src/test/kotlin/knes/agent/tools/save/MinimapTrackerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `knes-agent-tools/src/test/kotlin/knes/agent/tools/save/MinimapTrackerTest.kt`:

```kotlin
package knes.agent.tools.save

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinimapTrackerTest {
    @Test
    fun `empty tracker round-trips via base64`() {
        val tracker = MinimapTracker()
        val snap = tracker.toSnapshot()
        val restored = MinimapTracker.fromSnapshot(snap)
        assertEquals(32, restored.width)
        assertEquals(32, restored.height)
        assertFalse(restored.isVisited(0, 0))
    }

    @Test
    fun `markVisited and isVisited are consistent across base64 round-trip`() {
        val tracker = MinimapTracker()
        tracker.markVisited(3, 7)
        tracker.markVisited(31, 31)
        val restored = MinimapTracker.fromSnapshot(tracker.toSnapshot())
        assertTrue(restored.isVisited(3, 7))
        assertTrue(restored.isVisited(31, 31))
        assertFalse(restored.isVisited(0, 0))
    }

    @Test
    fun `out-of-bounds coords are ignored, not crashing`() {
        val tracker = MinimapTracker()
        tracker.markVisited(-1, 0)
        tracker.markVisited(0, 32)
        tracker.markVisited(32, 0)
        val restored = MinimapTracker.fromSnapshot(tracker.toSnapshot())
        assertFalse(restored.isVisited(0, 0))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent-tools:test --tests "knes.agent.tools.save.MinimapTrackerTest"`
Expected: FAIL with "Unresolved reference: MinimapTracker".

- [ ] **Step 3: Implement the tracker**

Create `knes-agent-tools/src/main/kotlin/knes/agent/tools/save/MinimapTracker.kt`:

```kotlin
package knes.agent.tools.save

import java.util.BitSet
import java.util.Base64

class MinimapTracker(
    val width: Int = 32,
    val height: Int = 32,
    private val bits: BitSet = BitSet(width * height),
) {
    fun markVisited(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        bits.set(y * width + x)
    }

    fun isVisited(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return bits.get(y * width + x)
    }

    fun toSnapshot(): VisitedMinimap {
        val byteCount = (width * height + 7) / 8
        val raw = bits.toByteArray()
        val padded = ByteArray(byteCount)
        System.arraycopy(raw, 0, padded, 0, minOf(raw.size, byteCount))
        return VisitedMinimap(
            width = width,
            height = height,
            bitsBase64 = Base64.getEncoder().encodeToString(padded),
        )
    }

    companion object {
        fun fromSnapshot(snap: VisitedMinimap): MinimapTracker {
            val bytes = if (snap.bitsBase64.isEmpty()) ByteArray(0)
                else Base64.getDecoder().decode(snap.bitsBase64)
            val bs = BitSet.valueOf(bytes)
            return MinimapTracker(width = snap.width, height = snap.height, bits = bs)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-agent-tools:test --tests "knes.agent.tools.save.MinimapTrackerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent-tools/src/main/kotlin/knes/agent/tools/save/MinimapTracker.kt \
        knes-agent-tools/src/test/kotlin/knes/agent/tools/save/MinimapTrackerTest.kt
git commit -m "feat(save): MinimapTracker stub with BitSet ↔ base64"
```

---

## Task 4: `ScratchpadProjection` — adapter on `AgentScratchpad`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/save/ScratchpadProjection.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/save/ScratchpadProjectionTest.kt`

Note: `AgentScratchpad.ScratchpadEntry` is already `@Serializable` and lives in `knes.agent.runtime` (see `knes-agent/src/main/kotlin/knes/agent/runtime/AgentScratchpad.kt`). This adapter does NOT modify the scratchpad's schema — it only projects entries into `MoveEntry` / `DecisionEntry` and adds a `recordDecision` helper.

- [ ] **Step 1: Write the failing tests**

Create `knes-agent/src/test/kotlin/knes/agent/save/ScratchpadProjectionTest.kt`:

```kotlin
package knes.agent.save

import knes.agent.runtime.AgentScratchpad
import knes.agent.tools.save.DecisionEntry
import knes.agent.tools.save.MoveEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScratchpadProjectionTest {
    @Test
    fun `recentMoves filters to tap and walk, returns last N in order`() {
        val pad = AgentScratchpad.newSession()
        repeat(12) { i ->
            pad.record(kind = "tap", summary = "step $i", dir = "DOWN",
                smPre = i to 0, smPost = i + 1 to 0)
        }
        pad.record(kind = "phase", summary = "shop_reached")
        pad.record(kind = "decision", summary = "buy: pick char3 — cheapest")

        val moves: List<MoveEntry> = pad.recentMoves(n = 5)
        assertEquals(5, moves.size)
        assertEquals(listOf(7, 8, 9, 10, 11), moves.map { it.smPre!![0] })
        assertEquals("DOWN", moves[0].dir)
    }

    @Test
    fun `recentDecisions filters to decision kind, returns last M`() {
        val pad = AgentScratchpad.newSession()
        pad.record(kind = "tap", summary = "x", dir = "UP")
        repeat(40) { i ->
            pad.recordDecision(phase = "buy", reasoning = "r$i", action = "a$i")
        }
        val decisions: List<DecisionEntry> = pad.recentDecisions(n = 30)
        assertEquals(30, decisions.size)
        assertEquals("buy", decisions.first().phase)
        assertEquals("r10", decisions.first().reasoning)
        assertEquals("r39", decisions.last().reasoning)
    }

    @Test
    fun `recordDecision appends with kind=decision`() {
        val pad = AgentScratchpad.newSession()
        pad.recordDecision(phase = "exit", reasoning = "south door", action = "tap DOWN")
        val all = pad.all()
        assertEquals(1, all.size)
        assertEquals("decision", all[0].kind)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent:test --tests "knes.agent.save.ScratchpadProjectionTest"`
Expected: FAIL with "Unresolved reference: recentMoves" / "recordDecision".

- [ ] **Step 3: Implement the projection**

Create `knes-agent/src/main/kotlin/knes/agent/save/ScratchpadProjection.kt`:

```kotlin
package knes.agent.save

import knes.agent.runtime.AgentScratchpad
import knes.agent.runtime.ScratchpadEntry
import knes.agent.tools.save.DecisionEntry
import knes.agent.tools.save.MoveEntry

/**
 * Convention: a "decision" entry stores reasoning/action/outcome inside the
 * existing summary/note fields with this delimiter, so the scratchpad schema
 * stays unchanged.
 *
 *   summary = "<phase>: <action> — <reasoning>"
 *   note    = outcome (nullable)
 */
private const val DECISION_DELIM = " — "

fun AgentScratchpad.recordDecision(
    phase: String,
    reasoning: String,
    action: String,
    outcome: String? = null,
) {
    record(
        kind = "decision",
        summary = "$phase: $action$DECISION_DELIM$reasoning",
        note = outcome,
    )
}

fun AgentScratchpad.recentMoves(n: Int = 8): List<MoveEntry> =
    all().asReversed()
        .filter { it.kind == "tap" || it.kind == "walk" }
        .take(n)
        .reversed()
        .map { it.toMoveEntry() }

fun AgentScratchpad.recentDecisions(n: Int = 30): List<DecisionEntry> =
    all().asReversed()
        .filter { it.kind == "decision" }
        .take(n)
        .reversed()
        .map { it.toDecisionEntry() }

private fun ScratchpadEntry.toMoveEntry(): MoveEntry = MoveEntry(
    seq = seq, tMs = tMs,
    dir = dir ?: "",
    smPre = smPre, smPost = smPost,
    moved = moved,
    mapflagsPost = mapflagsPost,
    note = note,
)

private fun ScratchpadEntry.toDecisionEntry(): DecisionEntry {
    val (phaseAndAction, reasoning) = summary.split(DECISION_DELIM, limit = 2)
        .let { if (it.size == 2) it[0] to it[1] else it[0] to "" }
    val (phase, action) = phaseAndAction.split(": ", limit = 2)
        .let { if (it.size == 2) it[0] to it[1] else "" to phaseAndAction }
    return DecisionEntry(
        seq = seq, tMs = tMs,
        phase = phase, reasoning = reasoning, action = action,
        outcome = note,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-agent:test --tests "knes.agent.save.ScratchpadProjectionTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/save/ScratchpadProjection.kt \
        knes-agent/src/test/kotlin/knes/agent/save/ScratchpadProjectionTest.kt
git commit -m "feat(save): ScratchpadProjection — recentMoves/recentDecisions + recordDecision"
```

---

## Task 5: `LandmarkProjection` — adapter on `LandmarkMemory`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/save/LandmarkProjection.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/save/LandmarkProjectionTest.kt`

`LandmarkMemory` lives in `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt`. Its `Landmark.kind` is `LandmarkKind` enum (TOWN_ENTRY, NPC_KING, NPC_SHOPKEEPER, NPC_INNKEEPER, etc.). The snapshot groups kings/shops/inns/bridges/other.

- [ ] **Step 1: Write the failing tests**

Create `knes-agent/src/test/kotlin/knes/agent/save/LandmarkProjectionTest.kt`:

```kotlin
package knes.agent.save

import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LandmarkProjectionTest {
    @Test
    fun `toSnapshot groups landmarks by kind`(@TempDir tmp: File) {
        val mem = LandmarkMemory(file = File(tmp, "lm.json"))
        mem.record(Landmark(id = "k1", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 16, localY = 8, note = "King of Coneria"))
        mem.record(Landmark(id = "s1", kind = LandmarkKind.NPC_SHOPKEEPER,
            mapId = 0, localX = 12, localY = 18, note = "weapon shop"))
        mem.record(Landmark(id = "i1", kind = LandmarkKind.NPC_INNKEEPER,
            mapId = 0, localX = 14, localY = 6, note = "Coneria inn"))
        mem.record(Landmark(id = "t1", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 153, worldY = 162, note = "Coneria entry"))

        val snap = mem.toSnapshot()
        assertEquals(1, snap.kings.size)
        assertEquals("King of Coneria", snap.kings[0].label)
        assertEquals(16, snap.kings[0].x)
        assertEquals(1, snap.shops.size)
        assertEquals(1, snap.inns.size)
        assertEquals(1, snap.other.size)
        assertEquals("Coneria entry", snap.other[0].label)
        assertEquals(153, snap.other[0].x)
    }

    @Test
    fun `roundtrip — toSnapshot then applySnapshot rebuilds same kinds`(@TempDir tmp: File) {
        val src = LandmarkMemory(file = File(tmp, "src.json"))
        src.record(Landmark(id = "k1", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 16, localY = 8, note = "King"))
        val snap = src.toSnapshot()

        val dst = LandmarkMemory(file = File(tmp, "dst.json"))
        dst.applySnapshot(snap)
        val all = dst.all()
        assertEquals(1, all.size)
        assertEquals(LandmarkKind.NPC_KING, all[0].kind)
        assertEquals(16, all[0].localX)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent:test --tests "knes.agent.save.LandmarkProjectionTest"`
Expected: FAIL with "Unresolved reference: toSnapshot" / "applySnapshot".

- [ ] **Step 3: Implement the projection**

Create `knes-agent/src/main/kotlin/knes/agent/save/LandmarkProjection.kt`:

```kotlin
package knes.agent.save

import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.save.LandmarkRef
import knes.agent.tools.save.LandmarksSnapshot

fun LandmarkMemory.toSnapshot(): LandmarksSnapshot {
    val grouped = all().groupBy { it.bucket() }
    return LandmarksSnapshot(
        kings  = grouped["kings"].orEmpty().map { it.toRef() },
        shops  = grouped["shops"].orEmpty().map { it.toRef() },
        inns   = grouped["inns"].orEmpty().map { it.toRef() },
        bridges = grouped["bridges"].orEmpty().map { it.toRef() },
        other  = grouped["other"].orEmpty().map { it.toRef() },
    )
}

fun LandmarkMemory.applySnapshot(snap: LandmarksSnapshot) {
    fun add(refs: List<LandmarkRef>, kind: LandmarkKind, prefix: String) {
        for ((idx, r) in refs.withIndex()) {
            record(Landmark(
                id = "$prefix-${r.mapId}-${r.x}-${r.y}-$idx",
                kind = kind,
                mapId = r.mapId, localX = r.x, localY = r.y,
                note = r.label,
            ))
        }
    }
    add(snap.kings, LandmarkKind.NPC_KING, "king")
    add(snap.shops, LandmarkKind.NPC_SHOPKEEPER, "shop")
    add(snap.inns, LandmarkKind.NPC_INNKEEPER, "inn")
    // "bridges" and "other" don't have dedicated LandmarkKind values in v1;
    // restored as UNKNOWN so they remain visible but flagged.
    add(snap.bridges, LandmarkKind.UNKNOWN, "bridge")
    add(snap.other, LandmarkKind.UNKNOWN, "other")
}

private fun Landmark.bucket(): String = when (kind) {
    LandmarkKind.NPC_KING -> "kings"
    LandmarkKind.NPC_SHOPKEEPER -> "shops"
    LandmarkKind.NPC_INNKEEPER -> "inns"
    else -> "other"
}

private fun Landmark.toRef(): LandmarkRef = LandmarkRef(
    mapId = mapId ?: -1,
    x = localX ?: worldX ?: 0,
    y = localY ?: worldY ?: 0,
    label = note.ifBlank { id },
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-agent:test --tests "knes.agent.save.LandmarkProjectionTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/save/LandmarkProjection.kt \
        knes-agent/src/test/kotlin/knes/agent/save/LandmarkProjectionTest.kt
git commit -m "feat(save): LandmarkProjection — group LandmarkMemory into snapshot buckets"
```

---

## Task 6: MCP `save_session` tool

**Files:**
- Modify: `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt` (add new tool registration after `reset`, around line 355+)

`EmulatorSession` is from `knes.api` (lives in `knes-emulator-session/.../EmulatorSession.kt`). It exposes `saveState(): ByteArray` (line 128) and `loadState(bytes: ByteArray): Boolean` (line 142). The session field is already bound at `McpServer.kt:36`.

The MCP server does not have a `knes-agent` dependency. So v1 `save_session` writes a "thin" save: emulator bytes + intent (from the tool arg) + empty agent fields. Hydrating scratchpad/landmarks is the agent's job and happens on a separate code path.

- [ ] **Step 1: Read the existing tool-registration pattern**

Run: `grep -n "addTool" knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt | head -10`

Expected: lines showing existing `server.addTool(name = "...", ...) { request -> ... }` blocks. You'll add a new one after the `reset` tool.

- [ ] **Step 2: Add the tool**

In `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`, append a new tool registration AFTER the `reset` tool block. The pattern follows existing tools (see `load_rom` at line ~54). Insert before the final `return server` of `createMcpServer()`:

```kotlin
    // save_session — write KnesSave v1 JSON containing emulator bytes (base64)
    // plus optional intent. Agent-derived fields (moves/decisions/landmarks/
    // minimap) are empty in MCP-driven saves; the agent populates them on its
    // own save path.
    server.addTool(
        name = "save_session",
        description = "Save the current emulator state to a KnesSave v1 JSON file. Returns the path written.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute path for the .knes-save.json output file")
                }
                putJsonObject("intent") {
                    put("type", "string")
                    put("description", "Optional one-line description of the agent's current intent")
                }
                putJsonObject("rom") {
                    put("type", "string")
                    put("description", "Optional ROM name for sanity check on load (defaults to 'unknown.nes')")
                }
            },
            required = listOf("path")
        )
    ) { request ->
        val path = request.arguments?.get("path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Missing required parameter: path")),
                isError = true,
            )
        val intent = request.arguments?.get("intent")?.jsonPrimitive?.content ?: ""
        val rom = request.arguments?.get("rom")?.jsonPrimitive?.content ?: "unknown.nes"
        try {
            val bytes = session.saveState()
            val save = knes.agent.tools.save.SaveFormatCodec.encode(
                emulatorStateBytes = bytes,
                rom = rom,
                intent = intent,
                recentMoves = emptyList(),
                decisionLog = emptyList(),
                landmarks = knes.agent.tools.save.LandmarksSnapshot(),
                visitedMinimap = knes.agent.tools.save.VisitedMinimap(bitsBase64 = ""),
            )
            val text = knes.agent.tools.save.SaveFormatCodec.toJson(save)
            java.io.File(path).writeText(text)
            CallToolResult(content = listOf(TextContent(
                "save_session ok: wrote ${text.length} bytes to $path (rom=$rom, intent='$intent')"
            )))
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("save_session failed: ${e.message}")),
                isError = true,
            )
        }
    }
```

- [ ] **Step 3: Compile to verify no syntax errors**

Run: `./gradlew :knes-mcp:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt
git commit -m "feat(mcp): save_session tool writes KnesSave v1 JSON"
```

---

## Task 7: MCP `load_session` tool

**Files:**
- Modify: `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`

- [ ] **Step 1: Add the tool**

Immediately after the `save_session` block in `McpServer.kt`, add:

```kotlin
    // load_session — read a KnesSave v1 JSON file and restore emulator bytes.
    // Agent-derived fields are returned in the response payload but NOT applied
    // here (the agent has its own restoration path).
    server.addTool(
        name = "load_session",
        description = "Load a KnesSave v1 JSON file and restore emulator state. Agent context (intent, moves, decisions, landmarks, minimap) is returned in the response but not applied to any agent runtime.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute path to the .knes-save.json file to load")
                }
            },
            required = listOf("path")
        )
    ) { request ->
        val path = request.arguments?.get("path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Missing required parameter: path")),
                isError = true,
            )
        try {
            val text = java.io.File(path).readText()
            val save = knes.agent.tools.save.SaveFormatCodec.fromJson(text)
            val bytes = knes.agent.tools.save.SaveFormatCodec.decodeEmulatorBytes(save)
            val ok = session.loadState(bytes)
            if (!ok) {
                CallToolResult(
                    content = listOf(TextContent("load_session failed: emulator rejected state bytes")),
                    isError = true,
                )
            } else {
                CallToolResult(content = listOf(TextContent(
                    "load_session ok: rom=${save.rom} intent='${save.currentIntent}' " +
                    "moves=${save.recentMoves.size} decisions=${save.decisionLog.size}"
                )))
            }
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("load_session failed: ${e.message}")),
                isError = true,
            )
        }
    }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-mcp:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt
git commit -m "feat(mcp): load_session tool restores emulator state from KnesSave v1"
```

---

## Task 8: MCP integration test — save → reset → load round-trip

**Files:**
- Create: `knes-mcp/src/test/kotlin/knes/mcp/SaveSessionRoundTripTest.kt`

This is an integration test exercising the codec + emulator together. It uses `EmulatorSession` directly (the same instance the MCP server uses) — we test the underlying behavior; tool-protocol plumbing was already verified at compile time.

Test ROM availability: existing tests load FF1 via fixture path. Check for `KNES_FF1_ROM_PATH` env var (look at how `SaveStateRoundTripTest.kt` does it).

- [ ] **Step 1: Look up how existing tests find a ROM**

Run: `grep -n "ROM\|loadRom\|KNES_FF1" knes-agent/src/test/kotlin/knes/agent/perception/SaveStateRoundTripTest.kt | head -10`

Expected: pattern showing the env var or fixture path the test uses. Mirror it.

- [ ] **Step 2: Write the test**

Create `knes-mcp/src/test/kotlin/knes/mcp/SaveSessionRoundTripTest.kt`:

```kotlin
package knes.mcp

import knes.agent.tools.save.SaveFormatCodec
import knes.api.EmulatorSession
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SaveSessionRoundTripTest {
    @Test
    fun `save then load via codec restores identical emulator bytes`(@TempDir tmp: File) {
        val romPath = System.getenv("KNES_FF1_ROM_PATH")
        assumeTrue(romPath != null && File(romPath).exists(),
            "KNES_FF1_ROM_PATH not set or missing — skipping integration test")

        val session = EmulatorSession()
        session.loadRom(File(romPath!!))
        repeat(60) { session.step() }
        val preBytes = session.saveState()

        val saveFile = File(tmp, "rt.knes-save.json")
        val save = SaveFormatCodec.encode(
            emulatorStateBytes = preBytes,
            rom = "ff1.nes",
            intent = "integration test",
            recentMoves = emptyList(),
            decisionLog = emptyList(),
            landmarks = knes.agent.tools.save.LandmarksSnapshot(),
            visitedMinimap = knes.agent.tools.save.VisitedMinimap(bitsBase64 = ""),
        )
        saveFile.writeText(SaveFormatCodec.toJson(save))

        // Fresh session — simulates "reset → load"
        val session2 = EmulatorSession()
        session2.loadRom(File(romPath))
        val parsed = SaveFormatCodec.fromJson(saveFile.readText())
        val decodedBytes = SaveFormatCodec.decodeEmulatorBytes(parsed)
        assertArrayEquals(preBytes, decodedBytes,
            "base64 round-trip must preserve emulator bytes exactly")

        val ok = session2.loadState(decodedBytes)
        assertTrue(ok, "emulator must accept restored state bytes")

        val postBytes = session2.saveState()
        assertArrayEquals(preBytes, postBytes,
            "loaded state must produce identical saveState output")
        assertEquals("ff1.nes", parsed.rom)
        assertEquals("integration test", parsed.currentIntent)
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :knes-mcp:test --tests "knes.mcp.SaveSessionRoundTripTest"`
Expected: PASS (or SKIP cleanly if `KNES_FF1_ROM_PATH` is unset — `assumeTrue` skips, doesn't fail).

To actually exercise the test:
```bash
KNES_FF1_ROM_PATH=/path/to/ff1.nes ./gradlew :knes-mcp:test --tests "knes.mcp.SaveSessionRoundTripTest"
```
Expected: PASS.

- [ ] **Step 4: Verify no regression in scratchpad tests**

Run: `./gradlew :knes-agent:test --tests "knes.agent.perception.SaveStateRoundTripTest"`
Expected: PASS (this was green before our changes; we touch no scratchpad schema, so it stays green).

- [ ] **Step 5: Commit**

```bash
git add knes-mcp/src/test/kotlin/knes/mcp/SaveSessionRoundTripTest.kt
git commit -m "test(mcp): KnesSave round-trip preserves emulator bytes through save/load"
```

---

## Task 9: Full-suite verification

- [ ] **Step 1: Run all touched modules' tests**

Run: `./gradlew :knes-agent-tools:test :knes-agent:test :knes-mcp:test`
Expected: BUILD SUCCESSFUL, all tests pass (or skip cleanly when ROM env unset).

- [ ] **Step 2: Spec-5 regression check (per Spec invariants)**

Run: `./gradlew :knes-agent:test --tests "*ShopFlow*" --tests "*Buy*"`
Expected: existing buy-flow tests still green. No spec-5 4/4 regression.

- [ ] **Step 3: Final commit if any fixes needed**

If anything failed in Step 1 or 2, fix it and commit. If all green, no commit needed.

---

## Out of scope (reminders from spec)

- Wiring `recordDecision` into BuyAtShop / WalkInteriorVision / ExitInterior — follow-up specs.
- Reading FF1 minimap RAM into `MinimapTracker` — follow-up spec.
- Migrating existing `.savestate` + `.actions.json` fixtures — both formats coexist.
- REST-bridge equivalents (`/session/save`, `/session/load` in `RemoteRestBridge.kt`) — REST bridge is legacy (`--remote` flag); add only if/when needed.
- Schema-version migration code — v1 is the first version.

## Self-review (done before handing off)

- **Spec coverage:** D1 (wrapper) → all components keep AgentScratchpad/LandmarkMemory in place ✓. D2 (base64 inline) → Task 2 ✓. D3 (32×32 minimap) → Task 3 ✓. D4 (knes-agent-tools) → Tasks 1–3 ✓. D5 (stub minimap) → Task 3 explicit stub ✓. D6 (kind="decision") → Task 4 `recordDecision` ✓. D7 (defaults 8/30) → Task 4 default params ✓. Acceptance items 1–5 all covered by Tasks 1–9.
- **Placeholders:** none.
- **Type consistency:** `MoveEntry.dir` is `String` (non-null) — Task 4's `toMoveEntry` defaults to `""` when scratchpad entry has null `dir`, matching the data class. `recentMoves(n=8)` / `recentDecisions(n=30)` signatures match the call sites in Task 4 tests. `toSnapshot()` / `applySnapshot()` method names are consistent across Task 5 production and test code.
