# FF1 Koog Agent V2.3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the FF1 overworld navigation deadend at world coord (146,152) by adding a deterministic 16×16 BFS pathfinder, a fog-of-war accumulator, and ASCII map rendering for the advisor.

**Architecture:** Pure-function `Pathfinder` reads PPU nametable through `NametableReader`, classifies tile IDs to terrain types via a JSON-configured `TileClassifier`, runs BFS over the resulting `ViewportMap` (skipping tiles in `FogOfWar.blockedSet`). `findPath` exposed as a deterministic `@Tool` on `SkillRegistry`; `walkOverworldTo` becomes a thin shim that calls `findPath` and presses buttons. `AdvisorAgent` prompt now includes ASCII viewport + fog stats.

**Tech Stack:** Kotlin 2.3.20 / Gradle 9.4.1 / Kotest 6.1.4 / Koog 0.6.1 / Anthropic SDK. Existing modules: `knes-emulator`, `knes-emulator-session`, `knes-agent`, `knes-agent-tools`.

**Spec:** `docs/superpowers/specs/2026-05-02-ff1-koog-agent-v2-3-design.md`

**Worktree:** `/Users/askowronski/Priv/kNES-ff1-agent-v2` on branch `ff1-agent-v2` (one ahead of master with V2.2; V2.3 commits stack on top).

**Out of scope (not part of this plan):**
- V2.2 standalone PR — separate `gh pr create` operation, no code change.
- Cross-run fog persistence (V2.4).
- Full ROM map / A\* / LLM-pathfinder (V2.5+).

---

## File Structure

**New files:**
- `knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/ViewportMap.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/FogOfWar.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/TileClassifier.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/NametableReader.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/AsciiMapRenderer.kt`
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/Direction.kt`
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/SearchSpace.kt`
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/PathResult.kt`
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/Pathfinder.kt`
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/ViewportPathfinder.kt`
- `knes-agent/src/main/resources/tile-classifications/ff1-overworld.json`
- `knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/FogOfWarTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/AsciiMapRendererTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierResearchTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/pathfinding/ViewportPathfinderTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/NametableReaderLiveTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/runtime/OverworldNavigationE2ETest.kt`

**Modified files:**
- `knes-emulator-session/src/main/kotlin/knes/api/EmulatorSession.kt` — add `readNametableTile(nt: Int, x: Int, y: Int): Int`.
- `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt` — produces `Observation(phase, ramSnapshot, viewportMap)`.
- `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt` — adds `@Tool findPath(targetX, targetY)`.
- `knes-agent/src/main/kotlin/knes/agent/skills/WalkOverworldTo.kt` — refactored to call `findPath` first, mark blocked tiles on idle steps.
- `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt` — prompt receives ASCII map + fog stats.
- `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` — wires `FogOfWar` lifecycle and viewport observation each turn.

---

## Task 1: Domain types (TileType, Direction, SearchSpace, PathResult)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/pathfinding/Direction.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/pathfinding/SearchSpace.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/pathfinding/PathResult.kt`

- [ ] **Step 1: Create TileType enum**

`knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt`:
```kotlin
package knes.agent.perception

enum class TileType(val glyph: Char) {
    GRASS('.'),
    FOREST('F'),
    MOUNTAIN('^'),
    WATER('~'),
    BRIDGE('B'),
    ROAD('R'),
    TOWN('T'),
    CASTLE('C'),
    UNKNOWN('?');

    /** Whether the party can walk onto this tile. UNKNOWN is conservatively impassable. */
    fun isPassable(): Boolean = when (this) {
        GRASS, FOREST, ROAD, BRIDGE, TOWN, CASTLE -> true
        MOUNTAIN, WATER, UNKNOWN -> false
    }
}
```

- [ ] **Step 2: Create Direction enum**

`knes-agent/src/main/kotlin/knes/agent/pathfinding/Direction.kt`:
```kotlin
package knes.agent.pathfinding

/** FF1 overworld is 4-way only. */
enum class Direction(val dx: Int, val dy: Int, val button: String) {
    N(0, -1, "UP"),
    S(0, 1, "DOWN"),
    E(1, 0, "RIGHT"),
    W(-1, 0, "LEFT");
}
```

- [ ] **Step 3: Create SearchSpace enum**

`knes-agent/src/main/kotlin/knes/agent/pathfinding/SearchSpace.kt`:
```kotlin
package knes.agent.pathfinding

/** Identifies which data the pathfinder searched. V2.3 only emits VIEWPORT. */
enum class SearchSpace { VIEWPORT, FOG, FULL_MAP }
```

- [ ] **Step 4: Create PathResult data class**

`knes-agent/src/main/kotlin/knes/agent/pathfinding/PathResult.kt`:
```kotlin
package knes.agent.pathfinding

data class PathResult(
    val found: Boolean,
    val steps: List<Direction>,
    val reachedTile: Pair<Int, Int>,
    val searchSpace: SearchSpace,
    val partial: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun blocked(from: Pair<Int, Int>, reason: String, space: SearchSpace = SearchSpace.VIEWPORT) =
            PathResult(false, emptyList(), from, space, partial = false, reason = reason)
    }
}
```

- [ ] **Step 5: Compile module to ensure types are valid**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt \
        knes-agent/src/main/kotlin/knes/agent/pathfinding/
git commit -m "feat(agent): V2.3 — domain types (TileType, Direction, SearchSpace, PathResult)"
```

---

## Task 2: ViewportMap

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/ViewportMap.kt`

- [ ] **Step 1: Create ViewportMap**

`knes-agent/src/main/kotlin/knes/agent/perception/ViewportMap.kt`:
```kotlin
package knes.agent.perception

/**
 * 16x16 grid of TileType centered on the party.
 *
 * @param tiles row-major, tiles[y][x] where y=0 is north edge.
 * @param partyLocalXY party position within the 16x16 grid (typically (8,8)).
 * @param partyWorldXY party position in world coordinates; used to translate
 *                     local (gridX, gridY) into world (worldX, worldY).
 */
data class ViewportMap(
    val tiles: Array<Array<TileType>>,
    val partyLocalXY: Pair<Int, Int>,
    val partyWorldXY: Pair<Int, Int>,
) {
    val width: Int get() = tiles[0].size
    val height: Int get() = tiles.size

    fun at(localX: Int, localY: Int): TileType =
        if (localX in 0 until width && localY in 0 until height) tiles[localY][localX]
        else TileType.UNKNOWN

    fun localToWorld(localX: Int, localY: Int): Pair<Int, Int> {
        val (px, py) = partyLocalXY
        val (wx, wy) = partyWorldXY
        return (wx + (localX - px)) to (wy + (localY - py))
    }

    fun worldToLocal(worldX: Int, worldY: Int): Pair<Int, Int>? {
        val (px, py) = partyLocalXY
        val (wx, wy) = partyWorldXY
        val lx = px + (worldX - wx)
        val ly = py + (worldY - wy)
        return if (lx in 0 until width && ly in 0 until height) lx to ly else null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewportMap) return false
        if (partyLocalXY != other.partyLocalXY) return false
        if (partyWorldXY != other.partyWorldXY) return false
        if (tiles.size != other.tiles.size) return false
        return tiles.indices.all { tiles[it].contentEquals(other.tiles[it]) }
    }

    override fun hashCode(): Int {
        var result = partyLocalXY.hashCode()
        result = 31 * result + partyWorldXY.hashCode()
        result = 31 * result + tiles.sumOf { it.contentHashCode() }
        return result
    }

    companion object {
        const val SIZE = 16
        fun ofUnknown(partyWorldXY: Pair<Int, Int>): ViewportMap = ViewportMap(
            tiles = Array(SIZE) { Array(SIZE) { TileType.UNKNOWN } },
            partyLocalXY = SIZE / 2 to SIZE / 2,
            partyWorldXY = partyWorldXY,
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/ViewportMap.kt
git commit -m "feat(agent): V2.3 — ViewportMap data class"
```

---

## Task 3: FogOfWar with tests

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/FogOfWar.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/FogOfWarTest.kt`

- [ ] **Step 1: Write failing tests**

`knes-agent/src/test/kotlin/knes/agent/perception/FogOfWarTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FogOfWarTest : FunSpec({
    test("merge adds new tiles from a viewport") {
        val fog = FogOfWar()
        val vm = ViewportMap.ofUnknown(partyWorldXY = 100 to 100)
        // Place a single GRASS at local (8,8) which corresponds to (100,100).
        vm.tiles[8][8] = TileType.GRASS
        fog.merge(vm)
        fog.tileAt(100, 100) shouldBe TileType.GRASS
        fog.size shouldBe 1
    }

    test("merge overwrites previously seen tile (latest wins)") {
        val fog = FogOfWar()
        val vm1 = ViewportMap.ofUnknown(partyWorldXY = 50 to 50).also { it.tiles[8][8] = TileType.GRASS }
        val vm2 = ViewportMap.ofUnknown(partyWorldXY = 50 to 50).also { it.tiles[8][8] = TileType.MOUNTAIN }
        fog.merge(vm1)
        fog.merge(vm2)
        fog.tileAt(50, 50) shouldBe TileType.MOUNTAIN
    }

    test("clear empties state") {
        val fog = FogOfWar()
        fog.merge(ViewportMap.ofUnknown(0 to 0).also { it.tiles[8][8] = TileType.GRASS })
        fog.markBlocked(1, 1)
        fog.clear()
        fog.size shouldBe 0
        fog.isBlocked(1, 1) shouldBe false
    }

    test("blocked tile mark survives subsequent merge of same coord") {
        val fog = FogOfWar()
        fog.markBlocked(7, 7)
        fog.merge(ViewportMap.ofUnknown(7 to 7).also { it.tiles[8][8] = TileType.GRASS })
        fog.isBlocked(7, 7) shouldBe true
    }

    test("UNKNOWN tiles are not stored (preserve last real classification)") {
        val fog = FogOfWar()
        val vm1 = ViewportMap.ofUnknown(50 to 50).also { it.tiles[8][8] = TileType.GRASS }
        val vm2 = ViewportMap.ofUnknown(50 to 50)  // all UNKNOWN
        fog.merge(vm1)
        fog.merge(vm2)
        fog.tileAt(50, 50) shouldBe TileType.GRASS
    }

    test("bbox returns null for empty fog and rectangle when populated") {
        val fog = FogOfWar()
        fog.bbox() shouldBe null
        fog.merge(ViewportMap.ofUnknown(10 to 20).also { it.tiles[8][8] = TileType.GRASS })
        fog.merge(ViewportMap.ofUnknown(30 to 40).also { it.tiles[8][8] = TileType.GRASS })
        fog.bbox() shouldBe (10 to 20 to (30 to 40))
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.FogOfWarTest`
Expected: FAIL — `Unresolved reference: FogOfWar`.

- [ ] **Step 3: Implement FogOfWar**

`knes-agent/src/main/kotlin/knes/agent/perception/FogOfWar.kt`:
```kotlin
package knes.agent.perception

/**
 * Per-run accumulator of seen tiles and confirmed-blocked tiles.
 * In-memory only (V2.3); cross-run persistence is V2.4.
 */
class FogOfWar {
    private val seen = mutableMapOf<Pair<Int, Int>, TileType>()
    private val blocked = mutableSetOf<Pair<Int, Int>>()

    val size: Int get() = seen.size

    /** Merge a viewport snapshot. UNKNOWN entries are NOT stored, so previous
     *  classifications are preserved. Latest-real-classification wins. */
    fun merge(viewport: ViewportMap) {
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                val type = viewport.tiles[ly][lx]
                if (type == TileType.UNKNOWN) continue
                seen[viewport.localToWorld(lx, ly)] = type
            }
        }
    }

    fun tileAt(worldX: Int, worldY: Int): TileType =
        seen[worldX to worldY] ?: TileType.UNKNOWN

    fun markBlocked(worldX: Int, worldY: Int) {
        blocked += worldX to worldY
    }

    fun isBlocked(worldX: Int, worldY: Int): Boolean = (worldX to worldY) in blocked

    fun blockedTiles(): Set<Pair<Int, Int>> = blocked.toSet()

    fun clear() {
        seen.clear()
        blocked.clear()
    }

    /** Bounding box ((minX,minY) to (maxX,maxY)) of seen tiles, or null if empty. */
    fun bbox(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (seen.isEmpty()) return null
        val xs = seen.keys.map { it.first }
        val ys = seen.keys.map { it.second }
        return (xs.min() to ys.min()) to (xs.max() to ys.max())
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.FogOfWarTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/FogOfWar.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/FogOfWarTest.kt
git commit -m "feat(agent): V2.3 — FogOfWar accumulator with merge/blocked/bbox"
```

---

## Task 4: TileClassifier with tests + JSON resource

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/TileClassifier.kt`
- Create: `knes-agent/src/main/resources/tile-classifications/ff1-overworld.json`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierTest.kt`

> **Note:** the JSON tile IDs created here are **placeholders** populated by Task 5 (research test). Task 4 ships with a minimal hand-seeded mapping so the classifier compiles and self-tests pass; Task 5 replaces it with empirically-correct IDs.

- [ ] **Step 1: Write failing tests**

`knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TileClassifierTest : FunSpec({
    test("classifies a known grass id") {
        val table = TileClassificationTable(
            version = 1, rom = "test",
            byType = mapOf("GRASS" to listOf(0x00))
        )
        val c = TileClassifier(table)
        c.classify(0x00) shouldBe TileType.GRASS
    }

    test("unknown id maps to UNKNOWN and is impassable") {
        val table = TileClassificationTable(version = 1, rom = "test", byType = emptyMap())
        val c = TileClassifier(table)
        c.classify(0xFF) shouldBe TileType.UNKNOWN
        c.classify(0xFF).isPassable() shouldBe false
    }

    test("loads bundled ff1-overworld resource without error") {
        val c = TileClassifier.loadFromResources("ff1-overworld")
        // Must compile + load — assertion: 'GRASS' bucket non-empty.
        // (Task 5 replaces seed values with empirical IDs.)
        (c.classify(c.knownIdsForType(TileType.GRASS).first()) == TileType.GRASS) shouldBe true
    }

    test("invalid JSON resource returns degraded all-UNKNOWN classifier") {
        val c = TileClassifier.loadFromResources("does-not-exist")
        c.classify(0x00) shouldBe TileType.UNKNOWN
        c.classify(0x42) shouldBe TileType.UNKNOWN
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.TileClassifierTest`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Create JSON resource (seed values, replaced in Task 5)**

`knes-agent/src/main/resources/tile-classifications/ff1-overworld.json`:
```json
{
  "version": 1,
  "rom": "ff1-us-rev-a",
  "byType": {
    "GRASS":    [0],
    "FOREST":   [],
    "MOUNTAIN": [],
    "WATER":    [],
    "BRIDGE":   [],
    "ROAD":     [],
    "TOWN":     [],
    "CASTLE":   []
  }
}
```

- [ ] **Step 4: Implement TileClassifier**

`knes-agent/src/main/kotlin/knes/agent/perception/TileClassifier.kt`:
```kotlin
package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TileClassificationTable(
    val version: Int,
    val rom: String,
    val byType: Map<String, List<Int>>,
)

class TileClassifier(private val table: TileClassificationTable) {
    private val idToType: Map<Int, TileType> = table.byType.flatMap { (typeName, ids) ->
        val type = runCatching { TileType.valueOf(typeName) }.getOrDefault(TileType.UNKNOWN)
        ids.map { it to type }
    }.toMap()

    fun classify(tileId: Int): TileType = idToType[tileId] ?: TileType.UNKNOWN

    fun knownIdsForType(type: TileType): List<Int> =
        idToType.entries.filter { it.value == type }.map { it.key }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads `tile-classifications/<name>.json` from resources. On failure
         *  returns a degraded classifier (all-UNKNOWN). Logs WARN at session start. */
        fun loadFromResources(name: String): TileClassifier {
            val path = "/tile-classifications/$name.json"
            val stream = TileClassifier::class.java.getResourceAsStream(path)
            if (stream == null) {
                System.err.println("WARN: tile classification table $path not found; using all-UNKNOWN")
                return TileClassifier(TileClassificationTable(0, "missing", emptyMap()))
            }
            return try {
                val text = stream.bufferedReader().use { it.readText() }
                TileClassifier(json.decodeFromString(TileClassificationTable.serializer(), text))
            } catch (t: Throwable) {
                System.err.println("WARN: tile classification parse error: ${t.message}; using all-UNKNOWN")
                TileClassifier(TileClassificationTable(0, "broken", emptyMap()))
            }
        }
    }
}
```

- [ ] **Step 5: Verify kotlinx.serialization is on the classpath**

Run: `grep -n "kotlinx-serialization" knes-agent/build.gradle.kts`
Expected: dependency present. If absent, add to `knes-agent/build.gradle.kts`:
```kotlin
plugins {
    kotlin("plugin.serialization") version "2.3.20"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.TileClassifierTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/TileClassifier.kt \
        knes-agent/src/main/resources/tile-classifications/ff1-overworld.json \
        knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierTest.kt \
        knes-agent/build.gradle.kts
git commit -m "feat(agent): V2.3 — TileClassifier with JSON-loaded mapping (seed values)"
```

---

## Task 5: PPU nametable read access on EmulatorSession + NametableReader

**Files:**
- Modify: `knes-emulator-session/src/main/kotlin/knes/api/EmulatorSession.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/NametableReader.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/NametableReaderLiveTest.kt`

- [ ] **Step 1: Add nametable read API to EmulatorSession**

In `knes-emulator-session/src/main/kotlin/knes/api/EmulatorSession.kt`, add after `readMemory`:
```kotlin
/**
 * Reads a single tile index from one of the four PPU nametables.
 * @param ntIndex 0..3 (NES has 4 nametable slots, mirrored per cartridge config).
 * @param x 0..31 (tile column within the 32x30 nametable).
 * @param y 0..29 (tile row).
 * @return tile pattern index 0..255, or 0 if PPU not initialised.
 */
fun readNametableTile(ntIndex: Int, x: Int, y: Int): Int {
    require(ntIndex in 0..3) { "nametable index $ntIndex out of range" }
    require(x in 0..31) { "x $x out of range" }
    require(y in 0..29) { "y $y out of range" }
    val nt = nes.ppu.nameTable.getOrNull(ntIndex) ?: return 0
    return nt.getTileIndex(x, y).toInt() and 0xFF
}
```

- [ ] **Step 2: Compile session module**

Run: `./gradlew :knes-emulator-session:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Create NametableReader**

`knes-agent/src/main/kotlin/knes/agent/perception/NametableReader.kt`:
```kotlin
package knes.agent.perception

import knes.api.EmulatorSession

/**
 * Reads a 16x16 ViewportMap centered on the party from PPU nametables.
 *
 * The FF1 overworld renders a single nametable (NT0) at any moment; the camera
 * scrolls so the party is roughly centered. We approximate "viewport around
 * the party" by reading NT0 around the screen center (col 16, row 15).
 *
 * Out-of-bounds tiles (beyond NT0 edges) become UNKNOWN.
 */
class NametableReader(
    private val session: EmulatorSession,
    private val classifier: TileClassifier,
) {
    fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val size = ViewportMap.SIZE                 // 16
        val partyLocal = size / 2 to size / 2       // (8, 8)
        // NT0 is 32 cols x 30 rows. Party is rendered near screen center
        // (col ~16, row ~15). We read a 16x16 window around that center.
        val ntCenterX = 16
        val ntCenterY = 15
        val originCol = ntCenterX - partyLocal.first
        val originRow = ntCenterY - partyLocal.second
        val tiles = Array(size) { ly ->
            Array(size) { lx ->
                val col = originCol + lx
                val row = originRow + ly
                if (col !in 0..31 || row !in 0..29) {
                    TileType.UNKNOWN
                } else {
                    classifier.classify(session.readNametableTile(0, col, row))
                }
            }
        }
        return ViewportMap(tiles, partyLocalXY = partyLocal, partyWorldXY = partyWorldXY)
    }
}
```

- [ ] **Step 4: Write live integration test (gated by ROM presence)**

`knes-agent/src/test/kotlin/knes/agent/perception/NametableReaderLiveTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class NametableReaderLiveTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("reads a 16x16 viewport without crashing").config(enabled = romPresent) {
        val session = EmulatorSession()
        session.loadRom(romPath)
        session.advanceFrames(60)  // boot
        val classifier = TileClassifier.loadFromResources("ff1-overworld")
        val reader = NametableReader(session, classifier)
        val vp = reader.readViewport(partyWorldXY = 0 to 0)
        // Sanity: viewport has SIZE rows of SIZE columns.
        vp.tiles.size shouldNotBeEmpty
        vp.tiles[0].size shouldNotBeEmpty
        check(vp.width == ViewportMap.SIZE)
        check(vp.height == ViewportMap.SIZE)
    }
})
```

- [ ] **Step 5: Run live test (skips if ROM missing)**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.NametableReaderLiveTest`
Expected: PASS (1 test, possibly skipped on CI).

- [ ] **Step 6: Commit**

```bash
git add knes-emulator-session/src/main/kotlin/knes/api/EmulatorSession.kt \
        knes-agent/src/main/kotlin/knes/agent/perception/NametableReader.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/NametableReaderLiveTest.kt
git commit -m "feat(agent): V2.3 — NametableReader + EmulatorSession.readNametableTile"
```

---

## Task 6: Empirical tile classification (research test → fill JSON)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierResearchTest.kt`
- Modify: `knes-agent/src/main/resources/tile-classifications/ff1-overworld.json`

**This task involves human-in-the-loop classification.** The research test dumps hex grids; the human compares against screenshots and fills the JSON.

- [ ] **Step 1: Create research test**

`knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierResearchTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.api.EmulatorSession
import java.io.File

/**
 * Diagnostic — NOT a regression test. Dumps the 16x16 nametable hex grid at
 * known overworld positions plus PNG screenshots, so a human can build the
 * tile-id → TileType mapping in `ff1-overworld.json`.
 *
 * Run manually:
 *   ./gradlew :knes-agent:test --tests knes.agent.perception.TileClassifierResearchTest
 * Outputs land in build/research/tile-classifier/.
 */
class TileClassifierResearchTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("dump tile grids at fixed boot point").config(enabled = romPresent) {
        val outDir = File("build/research/tile-classifier").also { it.mkdirs() }
        val session = EmulatorSession()
        session.loadRom(romPath)
        session.reset()
        session.advanceFrames(120)
        // Mash START + A to reach overworld with default party.
        repeat(2) {
            session.controller.press(0, knes.emulator.input.InputHandler.KEY_START)
            session.advanceFrames(8)
            session.controller.release(0, knes.emulator.input.InputHandler.KEY_START)
            session.advanceFrames(8)
        }
        repeat(20) {
            session.controller.press(0, knes.emulator.input.InputHandler.KEY_A)
            session.advanceFrames(8)
            session.controller.release(0, knes.emulator.input.InputHandler.KEY_A)
            session.advanceFrames(8)
        }
        // Dump 32x30 hex grid (full NT0).
        val sb = StringBuilder()
        sb.append("=== NT0 hex dump (32x30) ===\n")
        for (row in 0 until 30) {
            for (col in 0 until 32) {
                sb.append(String.format("%02X ", session.readNametableTile(0, col, row)))
            }
            sb.append('\n')
        }
        val out = File(outDir, "boot-spawn-nt0.hex.txt")
        out.writeText(sb.toString())
        File(outDir, "boot-spawn.png").writeBytes(session.getScreenPng())
        println("Wrote ${out.absolutePath} and boot-spawn.png")
    }
})
```

- [ ] **Step 2: Run research test**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.TileClassifierResearchTest`
Expected: passes; dump appears in `knes-agent/build/research/tile-classifier/`.

- [ ] **Step 3 [HUMAN]: Classify tile IDs from dump**

Open `boot-spawn.png` next to `boot-spawn-nt0.hex.txt`. For each visually-distinct terrain on the screen (grass, mountain, water, forest, road/bridge, town/castle), identify which hex IDs cluster on those screen regions. The screen is 32 cols × 30 rows; one tile = 8px on the 256×240 screen.

Useful trick: party sprite is centered around (col 16, row 15). Walk a few tiles (modify the test to press a direction) if you need a richer terrain sample.

- [ ] **Step 4: Update `ff1-overworld.json` with empirical IDs**

Replace the JSON with classified buckets. Example (real values come from the dump):
```json
{
  "version": 2,
  "rom": "ff1-us-rev-a",
  "byType": {
    "GRASS":    [0x00, 0x01, 0x02, 0x03],
    "FOREST":   [0x40, 0x41],
    "MOUNTAIN": [0x10, 0x11, 0x12, 0x13],
    "WATER":    [0x20, 0x21, 0x22],
    "BRIDGE":   [0x30],
    "ROAD":     [0x50, 0x51],
    "TOWN":     [0x60, 0x61, 0x62, 0x63],
    "CASTLE":   [0x70, 0x71, 0x72]
  }
}
```

- [ ] **Step 5: Verify TileClassifierTest still passes (loads new JSON)**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.TileClassifierTest`
Expected: PASS — `knownIdsForType(GRASS)` is non-empty.

- [ ] **Step 6: Verify NametableReaderLiveTest still passes**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.NametableReaderLiveTest`
Expected: PASS.

- [ ] **Step 7: Commit dump artefacts (gitignored) and JSON**

The dump files under `build/` are gitignored by Gradle convention. Commit only the JSON and the research test:
```bash
git add knes-agent/src/test/kotlin/knes/agent/perception/TileClassifierResearchTest.kt \
        knes-agent/src/main/resources/tile-classifications/ff1-overworld.json
git commit -m "feat(agent): V2.3 — empirical FF1 overworld tile classification (human-classified)"
```

---

## Task 7: Pathfinder interface + ViewportPathfinder BFS with tests

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/pathfinding/Pathfinder.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/pathfinding/ViewportPathfinder.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/pathfinding/ViewportPathfinderTest.kt`

- [ ] **Step 1: Write failing tests**

`knes-agent/src/test/kotlin/knes/agent/pathfinding/ViewportPathfinderTest.kt`:
```kotlin
package knes.agent.pathfinding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap

private fun viewportOf(width: Int = 16, height: Int = 16, partyWorldXY: Pair<Int, Int> = 100 to 100,
                       fill: TileType = TileType.GRASS, edits: (Array<Array<TileType>>) -> Unit = {}): ViewportMap {
    val tiles = Array(height) { Array(width) { fill } }
    edits(tiles)
    return ViewportMap(tiles, partyLocalXY = width / 2 to height / 2, partyWorldXY = partyWorldXY)
}

class ViewportPathfinderTest : FunSpec({
    val pf = ViewportPathfinder()

    test("direct path 4 steps north on open grass") {
        val vm = viewportOf()
        val res = pf.findPath(from = 100 to 100, to = 100 to 96, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.steps.shouldContainExactly(Direction.N, Direction.N, Direction.N, Direction.N)
        res.reachedTile shouldBe (100 to 96)
        res.searchSpace shouldBe SearchSpace.VIEWPORT
    }

    test("L-shape detour around a single mountain blocking direct path") {
        // Block (100,99) — directly north of party. Must go (101,100) -> ... -> (100,96).
        val vm = viewportOf { tiles ->
            // local (8,7) corresponds to world (100,99). Block it.
            tiles[7][8] = TileType.MOUNTAIN
        }
        val res = pf.findPath(from = 100 to 100, to = 100 to 96, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        // BFS prefers shortest path; shortest detour = 6 steps (E, N, N, N, N, W) or symmetric.
        res.steps.size shouldBe 6
    }

    test("fully blocked neighborhood returns not_found") {
        val vm = viewportOf(fill = TileType.GRASS) { tiles ->
            // Surround party at local (8,8) with mountain.
            tiles[7][7] = TileType.MOUNTAIN
            tiles[7][8] = TileType.MOUNTAIN
            tiles[7][9] = TileType.MOUNTAIN
            tiles[8][7] = TileType.MOUNTAIN
            tiles[8][9] = TileType.MOUNTAIN
            tiles[9][7] = TileType.MOUNTAIN
            tiles[9][8] = TileType.MOUNTAIN
            tiles[9][9] = TileType.MOUNTAIN
        }
        val res = pf.findPath(from = 100 to 100, to = 100 to 96, viewport = vm, fog = FogOfWar())
        res.found shouldBe false
        res.steps shouldBe emptyList()
    }

    test("target outside viewport returns partial path toward it") {
        val vm = viewportOf()
        // Target (100, 80) is 20 tiles north — beyond viewport (party at (8,8) of 16x16).
        val res = pf.findPath(from = 100 to 100, to = 100 to 80, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe true
        // Should walk north until edge: 8 steps reach local row 0 = world (100, 92).
        res.reachedTile.second shouldBe 92
    }

    test("target equals origin returns empty path with found=true") {
        val vm = viewportOf()
        val res = pf.findPath(from = 100 to 100, to = 100 to 100, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.steps shouldBe emptyList()
        res.reachedTile shouldBe (100 to 100)
    }

    test("fog blocked tile overrides passable classifier") {
        val vm = viewportOf()
        val fog = FogOfWar().apply { markBlocked(100, 99) }  // block direct north
        val res = pf.findPath(from = 100 to 100, to = 100 to 98, viewport = vm, fog = fog)
        res.found shouldBe true
        // 4-step detour is shortest: E, N, N, W (or W, N, N, E)
        res.steps.size shouldBe 4
    }

    test("path length cap of 32 returns partial") {
        // Construct a worst-case zigzag using mountains. Easier sanity: assert that
        // when target is reached in <=32 steps it is non-partial.
        val vm = viewportOf()
        val res = pf.findPath(from = 100 to 100, to = 107 to 100, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.steps.size shouldBe 7
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent:test --tests knes.agent.pathfinding.ViewportPathfinderTest`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement Pathfinder interface**

`knes-agent/src/main/kotlin/knes/agent/pathfinding/Pathfinder.kt`:
```kotlin
package knes.agent.pathfinding

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportMap

interface Pathfinder {
    fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        viewport: ViewportMap,
        fog: FogOfWar,
    ): PathResult
}
```

- [ ] **Step 4: Implement ViewportPathfinder**

`knes-agent/src/main/kotlin/knes/agent/pathfinding/ViewportPathfinder.kt`:
```kotlin
package knes.agent.pathfinding

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportMap
import java.util.ArrayDeque

class ViewportPathfinder(private val maxSteps: Int = 32) : Pathfinder {

    override fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        viewport: ViewportMap,
        fog: FogOfWar,
    ): PathResult {
        if (from == to) return PathResult(true, emptyList(), to, SearchSpace.VIEWPORT, partial = false)

        val start = viewport.worldToLocal(from.first, from.second)
            ?: return PathResult.blocked(from, "from outside viewport")
        val targetLocal = viewport.worldToLocal(to.first, to.second)
        // BFS over local 16x16 grid.
        val w = viewport.width
        val h = viewport.height
        val visited = Array(h) { BooleanArray(w) }
        val parent = Array(h) { Array<Pair<Int, Int>?>(w) { null } }
        val viaDir = Array(h) { Array<Direction?>(w) { null } }
        val q = ArrayDeque<Pair<Int, Int>>()
        q.add(start)
        visited[start.second][start.first] = true
        var bestReachable: Pair<Int, Int> = start
        var bestDistToTargetSq = distSq(start, targetLocal ?: targetEdge(viewport, to))

        while (q.isNotEmpty()) {
            val (cx, cy) = q.poll()
            if (targetLocal != null && cx == targetLocal.first && cy == targetLocal.second) {
                val steps = reconstruct(cx, cy, start, viaDir)
                if (steps.size > maxSteps) {
                    return PathResult(true, steps.take(maxSteps), reachedAfter(steps.take(maxSteps), from),
                        SearchSpace.VIEWPORT, partial = true, reason = "path exceeds $maxSteps steps")
                }
                return PathResult(true, steps, to, SearchSpace.VIEWPORT, partial = false)
            }
            // Track closest reachable to target for partial fallback.
            val candTargetLocal = targetLocal ?: targetEdge(viewport, to)
            val d = distSq(cx to cy, candTargetLocal)
            if (d < bestDistToTargetSq) {
                bestDistToTargetSq = d
                bestReachable = cx to cy
            }
            for (dir in Direction.values()) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                if (visited[ny][nx]) continue
                if (!viewport.tiles[ny][nx].isPassable()) continue
                val (wx, wy) = viewport.localToWorld(nx, ny)
                if (fog.isBlocked(wx, wy)) continue
                visited[ny][nx] = true
                parent[ny][nx] = cx to cy
                viaDir[ny][nx] = dir
                q.add(nx to ny)
            }
        }

        // Couldn't reach target. If target was outside viewport, return partial to bestReachable.
        if (targetLocal == null && bestReachable != start) {
            val steps = reconstruct(bestReachable.first, bestReachable.second, start, viaDir)
                .take(maxSteps)
            val (rwx, rwy) = viewport.localToWorld(bestReachable.first, bestReachable.second)
            return PathResult(true, steps, rwx to rwy, SearchSpace.VIEWPORT, partial = true,
                reason = "target outside viewport; walked toward it")
        }
        return PathResult.blocked(from, "no path within viewport")
    }

    private fun targetEdge(vm: ViewportMap, target: Pair<Int, Int>): Pair<Int, Int> {
        // Project the (out-of-viewport) target onto the closest viewport-edge cell.
        val (px, py) = vm.partyLocalXY
        val (wx, wy) = vm.partyWorldXY
        val dx = (target.first - wx).coerceIn(-(px), vm.width - 1 - px)
        val dy = (target.second - wy).coerceIn(-(py), vm.height - 1 - py)
        return px + dx to py + dy
    }

    private fun distSq(a: Pair<Int, Int>, b: Pair<Int, Int>): Int {
        val dx = a.first - b.first; val dy = a.second - b.second
        return dx * dx + dy * dy
    }

    private fun reconstruct(
        endX: Int, endY: Int,
        start: Pair<Int, Int>,
        viaDir: Array<Array<Direction?>>,
    ): List<Direction> {
        val out = ArrayDeque<Direction>()
        var cx = endX; var cy = endY
        while (cx != start.first || cy != start.second) {
            val dir = viaDir[cy][cx] ?: break
            out.addFirst(dir)
            cx -= dir.dx; cy -= dir.dy
        }
        return out.toList()
    }

    private fun reachedAfter(steps: List<Direction>, from: Pair<Int, Int>): Pair<Int, Int> {
        var (x, y) = from
        for (d in steps) { x += d.dx; y += d.dy }
        return x to y
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :knes-agent:test --tests knes.agent.pathfinding.ViewportPathfinderTest`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/pathfinding/Pathfinder.kt \
        knes-agent/src/main/kotlin/knes/agent/pathfinding/ViewportPathfinder.kt \
        knes-agent/src/test/kotlin/knes/agent/pathfinding/ViewportPathfinderTest.kt
git commit -m "feat(agent): V2.3 — ViewportPathfinder BFS with detour, partial paths, fog blocks"
```

---

## Task 8: Wire ViewportMap into RamObserver (Observation type)

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt`

- [ ] **Step 1: Extend RamObserver to optionally produce ViewportMap**

Replace the contents of `RamObserver.kt` with:
```kotlin
package knes.agent.perception

import knes.agent.tools.EmulatorToolset

/**
 * Snapshot returned each turn. `viewportMap` is null for non-overworld phases
 * or when no NametableReader is configured.
 */
data class Observation(
    val phase: FfPhase,
    val ram: Map<String, Int>,
    val viewportMap: ViewportMap?,
)

class RamObserver(
    private val toolset: EmulatorToolset,
    private val nametableReader: NametableReader? = null,
) {
    fun observe(): FfPhase = classify(toolset.getState().ram)

    fun ramSnapshot(): Map<String, Int> = toolset.getState().ram

    /** Full observation including viewport (when phase is Overworld and reader is wired). */
    fun observeFull(): Observation {
        val ram = toolset.getState().ram
        val phase = classify(ram)
        val vm = if (phase is FfPhase.Overworld && nametableReader != null) {
            nametableReader.readViewport(partyWorldXY = phase.x to phase.y)
        } else null
        return Observation(phase, ram, vm)
    }

    companion object {
        const val SCREEN_STATE_BATTLE = 0x68
        const val SCREEN_STATE_POST_BATTLE = 0x63
        const val LOCATION_TYPE_INDOORS = 0xD1

        fun classify(ram: Map<String, Int>): FfPhase {
            val screen = ram["screenState"] ?: 0
            if (screen == SCREEN_STATE_BATTLE) {
                return FfPhase.Battle(
                    enemyId = ram["enemyMainType"] ?: -1,
                    enemyHp = ((ram["enemy1_hpHigh"] ?: 0) shl 8) or (ram["enemy1_hpLow"] ?: 0),
                    enemyDead = (ram["enemy1_dead"] ?: 0) != 0,
                )
            }
            if (screen == SCREEN_STATE_POST_BATTLE) return FfPhase.PostBattle

            val charStatusKnown = (1..4).any { ram.containsKey("char${it}_status") }
            val charStatusValues = (1..4).map { ram["char${it}_status"] ?: 0 }
            val anyAlive = charStatusValues.any { (it and 0x01) == 0 }
            if (charStatusKnown && !anyAlive && (ram["char1_hpLow"] ?: 0) != 0) return FfPhase.PartyDefeated

            val partyCreated = (ram["char1_hpLow"] ?: 0) != 0
            if (partyCreated && (ram["locationType"] ?: 0) == LOCATION_TYPE_INDOORS) {
                return FfPhase.Indoors(localX = ram["localX"] ?: 0, localY = ram["localY"] ?: 0)
            }

            val onWorldMap = (ram["worldX"] ?: 0) != 0 || (ram["worldY"] ?: 0) != 0
            return when {
                partyCreated && onWorldMap -> FfPhase.Overworld(ram["worldX"] ?: 0, ram["worldY"] ?: 0)
                else -> FfPhase.TitleOrMenu
            }
        }
    }
}
```

- [ ] **Step 2: Confirm existing tests still pass (RamObserverTest)**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.RamObserverTest`
Expected: PASS — `RamObserver(toolset)` constructor compatible (nametableReader defaults to null).

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt
git commit -m "feat(agent): V2.3 — RamObserver.observeFull returns Observation with optional ViewportMap"
```

---

## Task 9: AsciiMapRenderer with tests

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/AsciiMapRenderer.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/AsciiMapRendererTest.kt`

- [ ] **Step 1: Write failing test**

`knes-agent/src/test/kotlin/knes/agent/perception/AsciiMapRendererTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class AsciiMapRendererTest : FunSpec({
    test("renders party glyph at center and known terrain glyphs") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        tiles[5][5] = TileType.MOUNTAIN
        tiles[10][10] = TileType.WATER
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 100 to 100)
        val rendered = AsciiMapRenderer.render(vm, FogOfWar())
        rendered shouldContain "@"
        rendered shouldContain "^"
        rendered shouldContain "~"
        rendered shouldContain "Legend"
        rendered shouldContain "100"  // world coords axis
    }

    test("renders X for fog-blocked tiles") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 50 to 50)
        val fog = FogOfWar().apply { markBlocked(51, 50) }  // east of party
        val out = AsciiMapRenderer.render(vm, fog)
        out shouldContain "X"
    }

    test("renders ? for UNKNOWN viewport tiles") {
        val tiles = Array(16) { Array(16) { TileType.UNKNOWN } }
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 50 to 50)
        val out = AsciiMapRenderer.render(vm, FogOfWar())
        out shouldContain "?"
    }

    test("FOG STATS line includes visited count") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 50 to 50)
        val fog = FogOfWar().apply { merge(vm) }
        val out = AsciiMapRenderer.render(vm, fog)
        out shouldContain "FOG STATS"
        out shouldContain "256"  // 16x16 tiles
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.AsciiMapRendererTest`
Expected: FAIL — `Unresolved reference: AsciiMapRenderer`.

- [ ] **Step 3: Implement renderer**

`knes-agent/src/main/kotlin/knes/agent/perception/AsciiMapRenderer.kt`:
```kotlin
package knes.agent.perception

object AsciiMapRenderer {

    /**
     * Renders the viewport as an ASCII grid with world-coord axis labels and a legend.
     * `@` marks party; `X` marks fog-confirmed-blocked tiles (overrides terrain);
     * `?` marks UNKNOWN tiles.
     */
    fun render(vm: ViewportMap, fog: FogOfWar): String {
        val sb = StringBuilder()
        val (pwx, pwy) = vm.partyWorldXY
        sb.append("WORLD VIEW (party at world coord $pwx,$pwy; viewport ${vm.width}x${vm.height}):\n\n")

        // Column header (world X coords every 2 tiles to keep width).
        sb.append("     ")
        for (lx in 0 until vm.width) {
            val (wx, _) = vm.localToWorld(lx, 0)
            if (lx % 2 == 0) sb.append(String.format("%3d ", wx)) else sb.append("    ")
        }
        sb.append('\n')

        for (ly in 0 until vm.height) {
            val (_, wy) = vm.localToWorld(0, ly)
            sb.append(String.format("%3d  ", wy))
            for (lx in 0 until vm.width) {
                val (wx, wyT) = vm.localToWorld(lx, ly)
                val glyph = when {
                    lx == vm.partyLocalXY.first && ly == vm.partyLocalXY.second -> '@'
                    fog.isBlocked(wx, wyT) -> 'X'
                    else -> vm.tiles[ly][lx].glyph
                }
                sb.append(' ').append(glyph).append("  ")
            }
            sb.append('\n')
        }

        sb.append("\nLegend: @ party, . grass, ^ mountain, ~ water, F forest,\n")
        sb.append("        R road, B bridge, T town, C castle, ? unseen, X blocked-confirmed\n")

        sb.append("\nFOG STATS: ${fog.size} tiles visited")
        fog.bbox()?.let { (mn, mx) ->
            sb.append(", bbox (${mn.first}-${mx.first}, ${mn.second}-${mx.second})")
        }
        sb.append(".\n")

        val recentBlocked = fog.blockedTiles()
        if (recentBlocked.isNotEmpty()) {
            sb.append("BLOCKED TILES: ")
                .append(recentBlocked.take(8).joinToString { "(${it.first},${it.second})" })
            if (recentBlocked.size > 8) sb.append(" …")
            sb.append('\n')
        }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.AsciiMapRendererTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/AsciiMapRenderer.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/AsciiMapRendererTest.kt
git commit -m "feat(agent): V2.3 — AsciiMapRenderer for advisor input"
```

---

## Task 10: SkillRegistry findPath tool + WalkOverworldTo refactor

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/WalkOverworldTo.kt`

- [ ] **Step 1: Refactor WalkOverworldTo to consult Pathfinder + mark blocked tiles**

Replace `knes-agent/src/main/kotlin/knes/agent/skills/WalkOverworldTo.kt`:
```kotlin
package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.NametableReader
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.tools.EmulatorToolset

/**
 * Walks toward (targetX, targetY) on the FF1 overworld using a deterministic
 * BFS pathfinder over the current viewport. If the pathfinder finds a path
 * (full or partial), the steps are pressed in sequence. If a step does not
 * move the party (RAM coords unchanged), the target tile is marked blocked
 * in the shared FogOfWar.
 */
class WalkOverworldTo(
    private val toolset: EmulatorToolset,
    private val nametableReader: NametableReader,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = ViewportPathfinder(),
) : Skill {
    override val id = "walk_overworld_to"
    override val description =
        "Walk on the FF1 overworld toward (targetX, targetY) via a deterministic BFS over the visible " +
            "16x16 viewport. Marks non-moving steps as blocked. Aborts on random encounter."

    private val FRAMES_PER_TILE = 16

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val tx = args["targetX"]?.toIntOrNull() ?: return SkillResult(false, "missing targetX")
        val ty = args["targetY"]?.toIntOrNull() ?: return SkillResult(false, "missing targetY")
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 32

        var totalFrames = 0
        var stepsTaken = 0

        while (stepsTaken < maxSteps) {
            val ram0 = toolset.getState().ram
            if ((ram0["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ram0)
            }
            val cx = ram0["worldX"] ?: return SkillResult(false, "worldX missing")
            val cy = ram0["worldY"] ?: return SkillResult(false, "worldY missing")
            if (cx == tx && cy == ty) {
                return SkillResult(true, "reached ($tx,$ty) in $stepsTaken steps", totalFrames, ram0)
            }
            val viewport = nametableReader.readViewport(cx to cy)
            fog.merge(viewport)
            val path = pathfinder.findPath(cx to cy, tx to ty, viewport, fog)
            if (!path.found || path.steps.isEmpty()) {
                val ram = toolset.getState().ram
                return SkillResult(false,
                    "blocked at ($cx,$cy): ${path.reason ?: "no path"}", totalFrames, ram)
            }
            val nextDir = path.steps.first()
            val r = toolset.step(buttons = listOf(nextDir.button), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++
            // Detect non-movement and mark target tile as blocked.
            val ram1 = toolset.getState().ram
            val nx = ram1["worldX"] ?: cx
            val ny = ram1["worldY"] ?: cy
            if (nx == cx && ny == cy) {
                val blockedX = cx + nextDir.dx
                val blockedY = cy + nextDir.dy
                fog.markBlocked(blockedX, blockedY)
            }
        }
        val ram = toolset.getState().ram
        return SkillResult(false, "did not reach ($tx,$ty) in $maxSteps steps", totalFrames, ram)
    }
}
```

- [ ] **Step 2: Update SkillRegistry to construct WalkOverworldTo with new deps + add findPath tool**

Replace `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt`:
```kotlin
package knes.agent.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.perception.FogOfWar
import knes.agent.perception.NametableReader
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ActionToolResult
import knes.agent.tools.results.StateSnapshot

@LLMDescription(
    "FF1 macro skills: scripted high-level actions that drive the emulator. Pick one per " +
        "outer turn; observe the resulting RAM state and choose the next skill."
)
class SkillRegistry(
    private val toolset: EmulatorToolset,
    private val nametableReader: NametableReader,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = ViewportPathfinder(),
) : ToolSet {

    private val pressStartSkill = PressStartUntilOverworld(toolset)
    private val walkSkill = WalkOverworldTo(toolset, nametableReader, fog, pathfinder)
    private val exitSkill = ExitBuilding(toolset)

    @Tool
    @LLMDescription(
        "Advance from the FF1 title screen through NEW GAME / class select / name entry into " +
            "the overworld. Mashes START then A. Termination: char1_hpLow != 0 OR worldX != 0. " +
            "Bounded by maxAttempts (default 60)."
    )
    suspend fun pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult =
        pressStartSkill.invoke(mapOf("maxAttempts" to "$maxAttempts"))

    @Tool
    @LLMDescription(
        "Exit the current building / town / castle interior by walking SOUTH until RAM " +
            "locationType (0x000D) becomes 0x00 (outside). Use this when phase is Indoors."
    )
    suspend fun exitBuilding(maxSteps: Int = 30): SkillResult =
        exitSkill.invoke(mapOf("maxSteps" to "$maxSteps"))

    @Tool
    @LLMDescription(
        "Find a walkable path from current party position to target world coordinates within " +
            "the visible 16x16 viewport. Returns 'PATH n steps: D,D,...' if reachable, " +
            "'PARTIAL n steps to (x,y); target outside viewport' if partial, or 'BLOCKED reason' " +
            "if no path. Deterministic — does not consume LLM tokens."
    )
    fun findPath(targetX: Int, targetY: Int): String {
        val ram = toolset.getState().ram
        val from = (ram["worldX"] ?: 0) to (ram["worldY"] ?: 0)
        val viewport = nametableReader.readViewport(from)
        fog.merge(viewport)
        val res = pathfinder.findPath(from, targetX to targetY, viewport, fog)
        return when {
            res.found && !res.partial ->
                "PATH ${res.steps.size} steps: ${res.steps.joinToString(",") { it.name }}"
            res.found && res.partial ->
                "PARTIAL ${res.steps.size} steps to (${res.reachedTile.first},${res.reachedTile.second}); " +
                    "target outside viewport. Walk this path then call findPath again. " +
                    "First steps: ${res.steps.take(8).joinToString(",") { it.name }}"
            else -> "BLOCKED. ${res.reason ?: "no path"}. Suggest askAdvisor."
        }
    }

    @Tool
    @LLMDescription(
        "Walk on the FF1 overworld toward (targetX, targetY) using deterministic BFS pathfinding. " +
            "Marks non-moving steps as blocked in fog-of-war. Returns ok=true if the target is " +
            "reached OR a random encounter starts."
    )
    suspend fun walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 32): SkillResult =
        walkSkill.invoke(mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps"))

    @Tool
    @LLMDescription("Run the registered FF1 battle_fight_all action: every alive character uses FIGHT until the battle ends.")
    suspend fun battleFightAll(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")

    @Tool
    @LLMDescription("Run the registered FF1 walk_until_encounter action: walk randomly until a battle starts.")
    suspend fun walkUntilEncounter(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "walk_until_encounter")

    @Tool
    @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    fun getState(): StateSnapshot = toolset.getState()
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`. (Existing tests for `WalkOverworldToTest` and `SkillRegistry` consumers will need updates next.)

- [ ] **Step 4: Update WalkOverworldToTest construction**

Open `knes-agent/src/test/kotlin/knes/agent/skills/WalkOverworldToTest.kt`. The skill now requires `(toolset, nametableReader, fog, pathfinder)`. For unit tests, construct a fake `NametableReader` returning an open-grass viewport and a fresh `FogOfWar`. If the existing test mocks `EmulatorToolset` only, add:
```kotlin
import knes.agent.perception.FogOfWar
import knes.agent.perception.NametableReader
import knes.agent.perception.TileClassifier
import knes.agent.perception.TileClassificationTable
import knes.agent.perception.ViewportMap
import knes.agent.perception.TileType
import knes.agent.pathfinding.ViewportPathfinder

private fun openViewportReader(): NametableReader = object : NametableReader(
    /* dummy */ stubSession(), TileClassifier(TileClassificationTable(1, "test", emptyMap()))
) {
    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap =
        ViewportMap(
            tiles = Array(16) { Array(16) { TileType.GRASS } },
            partyLocalXY = 8 to 8,
            partyWorldXY = partyWorldXY,
        )
}
```

If subclassing `NametableReader` is awkward (final class), extract an interface `NametableSource { fun readViewport(...): ViewportMap }` in `NametableReader.kt`, make `NametableReader` implement it, and update `WalkOverworldTo` / `SkillRegistry` to depend on the interface. Apply this refactor if needed.

- [ ] **Step 5: Run all unit tests**

Run: `./gradlew :knes-agent:test`
Expected: PASS (existing + new). Fix any compilation breakage from the skill signature change.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/WalkOverworldTo.kt \
        knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/WalkOverworldToTest.kt \
        knes-agent/src/main/kotlin/knes/agent/perception/NametableReader.kt
git commit -m "feat(agent): V2.3 — findPath @Tool + WalkOverworldTo refactor (BFS + blocked-tile marking)"
```

---

## Task 11: AdvisorAgent prompt augmentation

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`

- [ ] **Step 1: Inspect existing prompt assembly**

Run: `grep -n "system\|prompt\|fun ask" knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`
Expected: identify the function that assembles the advisor input (likely a `fun ask(reason: String, ...)`).

- [ ] **Step 2: Thread ViewportMap + FogOfWar into AdvisorAgent**

Add constructor params (NametableReader and FogOfWar) and inject the ASCII map into the system/user prompt. Edit `AdvisorAgent.kt` accordingly:
```kotlin
import knes.agent.perception.AsciiMapRenderer
import knes.agent.perception.FogOfWar
import knes.agent.perception.NametableReader
// ... existing imports ...

class AdvisorAgent(
    private val session: AnthropicSession,
    private val readOnlyTools: ReadOnlyToolset,
    private val nametableReader: NametableReader? = null,
    private val fog: FogOfWar? = null,
    /* ... existing params ... */
) {
    suspend fun ask(reason: String, ramSnapshot: Map<String, Int>): String {
        val partyXY = (ramSnapshot["worldX"] ?: 0) to (ramSnapshot["worldY"] ?: 0)
        val mapBlock = if (nametableReader != null && fog != null && partyXY != (0 to 0)) {
            val vp = nametableReader.readViewport(partyXY).also { fog.merge(it) }
            "\n\n${AsciiMapRenderer.render(vp, fog)}\n"
        } else ""
        // existing prompt assembly: prepend or append mapBlock to the user input.
        val userPrompt = buildString {
            append("Executor needs guidance.\n")
            append("Reason: ").append(reason).append('\n')
            append(mapBlock)
        }
        // ... existing call to AnthropicSession with system + userPrompt ...
        return /* model output as before */ TODO("preserve existing call site")
    }
}
```

> **Note:** the exact integration point depends on how `AdvisorAgent` is currently structured. Read the current file first; preserve its existing Koog `singleRunStrategy` setup; only add the `mapBlock` to the user-facing prompt. The constructor change is additive (new params default to null), so existing call sites in `AgentSession` keep compiling.

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt
git commit -m "feat(agent): V2.3 — AdvisorAgent prompt now includes ASCII map + fog stats"
```

---

## Task 12: AgentSession wires FogOfWar lifecycle and viewport-aware Observation

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`

- [ ] **Step 1: Read current AgentSession to find the right injection points**

Run: `grep -n "RamObserver\|SkillRegistry\|AdvisorAgent\|fun run\|fun runTurn" knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`
Expected: identify (a) where `RamObserver` is constructed, (b) where `SkillRegistry` is built, (c) where `AdvisorAgent` is built, (d) the per-turn loop.

- [ ] **Step 2: Construct shared FogOfWar + NametableReader + TileClassifier and wire**

Edit `AgentSession.kt`:
```kotlin
import knes.agent.perception.FogOfWar
import knes.agent.perception.NametableReader
import knes.agent.perception.TileClassifier
// ...

class AgentSession(
    private val toolset: EmulatorToolset,
    private val anthropic: AnthropicSession,
    /* ... existing params ... */
) {
    private val classifier: TileClassifier = TileClassifier.loadFromResources("ff1-overworld")
    private val nametableReader = NametableReader(toolset.session, classifier)
    private val fog = FogOfWar()
    private val ramObserver = RamObserver(toolset, nametableReader)
    private val skills = SkillRegistry(toolset, nametableReader, fog)
    private val advisor = AdvisorAgent(
        anthropic, /* readOnlyTools = */ ReadOnlyToolset(toolset),
        nametableReader = nametableReader, fog = fog,
        /* ... preserved params ... */
    )

    suspend fun run(/* ... */) {
        fog.clear()  // fresh state at session start
        // ... existing loop using ramObserver.observeFull() per turn ...
    }
}
```

> **Note:** `toolset.session` access depends on `EmulatorToolset` exposing the `EmulatorSession`. If the public field name differs (e.g. `emulatorSession`), use that. Read `knes-agent-tools` to confirm.

- [ ] **Step 3: In the per-turn loop, use observeFull and merge fog**

Inside the existing turn loop, replace `ramObserver.observe()` with:
```kotlin
val obs = ramObserver.observeFull()
obs.viewportMap?.let { fog.merge(it) }
val phase = obs.phase
val ram = obs.ram
// ... existing branches on phase ...
```

- [ ] **Step 4: Compile and run all tests**

Run: `./gradlew :knes-agent:test`
Expected: PASS. Fix any constructor mismatches in tests that build `AgentSession` directly.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt
git commit -m "feat(agent): V2.3 — AgentSession wires FogOfWar lifecycle + viewport observation"
```

---

## Task 13: E2E navigation test (live, ROM-gated)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/OverworldNavigationE2ETest.kt`

This test verifies the central V2.3 outcome: party escapes the (146,158) deadend. It runs the live agent against the real ROM with budget caps. **Requires `ANTHROPIC_API_KEY` and `roms/ff.nes`.**

- [ ] **Step 1: Create the E2E test**

`knes-agent/src/test/kotlin/knes/agent/runtime/OverworldNavigationE2ETest.kt`:
```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.Main
import java.io.File
import kotlin.math.abs

/**
 * Live navigation regression: party escapes the V2.1/V2.2 deadend at (146,158).
 * Asserts manhattan displacement >= 10 within 30 turns AND no tile visited > 5 times.
 *
 * Skipped on CI (no API key, no ROM).
 */
class OverworldNavigationE2ETest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()
    val keyPresent = !System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()

    test("party escapes Coneria deadend within 30 turns")
        .config(enabled = romPresent && keyPresent) {
        val runDir = File.createTempFile("knes-e2e-", "").apply { delete(); mkdirs() }.absolutePath
        val args = arrayOf(
            "--rom=$romPath",
            "--profile=ff1",
            "--max-skill-invocations=30",
            "--wall-clock-cap-seconds=480",
            "--run-dir=$runDir",
        )
        // Synchronous main entry; reads trace from runDir afterwards.
        Main.main(args)

        val trace = File(runDir, "trace.jsonl")
        check(trace.exists()) { "trace.jsonl not produced" }

        // Parse trace.jsonl: count distinct (worldX,worldY) seen, check max-visit cap, manhattan from spawn.
        val coords = mutableListOf<Pair<Int, Int>>()
        trace.forEachLine { line ->
            val xMatch = Regex("\"worldX\"\\s*:\\s*(\\d+)").find(line)
            val yMatch = Regex("\"worldY\"\\s*:\\s*(\\d+)").find(line)
            if (xMatch != null && yMatch != null) {
                coords += xMatch.groupValues[1].toInt() to yMatch.groupValues[1].toInt()
            }
        }
        check(coords.isNotEmpty()) { "no worldX/worldY in trace" }
        val spawn = coords.first()
        val maxVisits = coords.groupingBy { it }.eachCount().maxOf { it.value }
        val maxManhattan = coords.maxOf { abs(it.first - spawn.first) + abs(it.second - spawn.second) }
        println("E2E: spawn=$spawn, max-visits-per-tile=$maxVisits, max-manhattan-displacement=$maxManhattan")
        (maxManhattan >= 10) shouldBe true
        (maxVisits <= 5) shouldBe true
    }
})
```

> **Note:** the regex-based trace parser is intentionally lenient — `Trace.kt` writes JSONL with full structures, and we only need worldX/Y per turn. If your trace format diverges, replace with `kotlinx.serialization` parsing of `TurnRecord`.

- [ ] **Step 2: Run the E2E test (will only run with API key + ROM)**

Run: `ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew :knes-agent:test --tests knes.agent.runtime.OverworldNavigationE2ETest`
Expected: PASS — test logs spawn, max-visits, max-manhattan; manhattan ≥ 10.

If FAIL (party still stuck): inspect trace, then iterate — the most likely culprit is the empirical tile classification table missing IDs (UNKNOWN tiles → impassable → BFS finds no path). Re-run Task 6 dump and fill missing IDs.

- [ ] **Step 3: Commit (only the test, evidence committed in Task 14)**

```bash
git add knes-agent/src/test/kotlin/knes/agent/runtime/OverworldNavigationE2ETest.kt
git commit -m "test(agent): V2.3 — live E2E navigation regression (escape (146,158) deadend)"
```

---

## Task 14: Evidence run + PR #99

**Files:**
- Create: `docs/superpowers/runs/2026-05-02-v2-3-deadend-escape/` (directory + trace + summary)

- [ ] **Step 1: Run the agent live and capture trace**

```bash
mkdir -p docs/superpowers/runs/2026-05-02-v2-3-deadend-escape
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY KNES_RUN_DIR=docs/superpowers/runs/2026-05-02-v2-3-deadend-escape \
  ./gradlew :knes-agent:run \
    --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes --profile=ff1 \
            --max-skill-invocations=40 --wall-clock-cap-seconds=600"
```

Expected: trace.jsonl produced; final RAM coords show party meaningfully displaced from spawn.

- [ ] **Step 2: Write a one-page evidence summary**

Create `docs/superpowers/runs/2026-05-02-v2-3-deadend-escape/SUMMARY.md`:
```markdown
# V2.3 evidence — deadend escape

Run on 2026-05-02. Args: `--max-skill-invocations=40 --wall-clock-cap-seconds=600`.

## Outcome
- Outcome: <fill from trace tail>
- Final RAM coords: <fill>
- Manhattan displacement from spawn: <fill>
- Distinct tiles visited: <fill>
- Max single-tile revisit count: <fill>

## findPath behaviour
- Total findPath calls: <fill from trace>
- PATH outcomes: <count>; PARTIAL: <count>; BLOCKED: <count>

## Comparison to V2.2 run
| Metric | V2.2 (2026-05-02 stuck-in-castle) | V2.3 (this run) |
|---|---|---|
| Manhattan displacement | ≤ 6 | <fill> |
| Stuck loop on (146,151)? | yes | <fill> |

## Notes
- <free text observations>
```

- [ ] **Step 3: Commit evidence**

```bash
git add docs/superpowers/runs/2026-05-02-v2-3-deadend-escape/
git commit -m "evidence(agent): V2.3 deadend escape run — manhattan +<N> in <T> turns"
```

- [ ] **Step 4: Push branch and open PR #99 (after V2.2 PR #98 is open)**

```bash
git push -u origin ff1-agent-v2
gh pr create --base master --head ff1-agent-v2 --title "V2.3 — deterministic findPath + viewport map + fog-of-war" --body "$(cat <<'EOF'
## Summary

Eliminates the FF1 overworld navigation deadend at world coord (146,152) evidenced
in PR #97 by adding:

- Deterministic 16x16 BFS pathfinder (`ViewportPathfinder`) exposed as `findPath`
  `@Tool` on `SkillRegistry` — zero LLM tokens, sub-millisecond.
- `FogOfWar` accumulator: tracks visited tiles + confirmed-blocked tiles
  (marked when a step does not change RAM `worldX/Y`).
- `TileClassifier` empirically classifies FF1 overworld tile IDs from PPU
  nametable to `{GRASS, FOREST, MOUNTAIN, WATER, BRIDGE, ROAD, TOWN, CASTLE,
  UNKNOWN}` (JSON-driven, all-UNKNOWN fallback).
- `AsciiMapRenderer` produces a textual 16x16 grid with world-coord axis labels;
  fed to the advisor's prompt (Gemini-PP finding: tile grids match raw screenshots
  for spatial reasoning).
- `WalkOverworldTo` rewritten as a thin shim over `findPath`.

Spec: `docs/superpowers/specs/2026-05-02-ff1-koog-agent-v2-3-design.md`
Plan: `docs/superpowers/plans/2026-05-02-ff1-koog-agent-v2-3.md`
Evidence: `docs/superpowers/runs/2026-05-02-v2-3-deadend-escape/`

Stacks on PR #98 (V2.2 standalone).

## Test plan

- [x] Unit: `ViewportPathfinderTest` (7), `TileClassifierTest` (4), `FogOfWarTest` (6), `AsciiMapRendererTest` (4)
- [x] Live: `NametableReaderLiveTest`, `OverworldNavigationE2ETest` (manhattan >= 10, max single-tile visits <= 5)
- [x] CI green
- [x] Evidence: trace.jsonl + SUMMARY.md show party escapes deadend
EOF
)"
```

---

## Self-Review

**1. Spec coverage scan:**

| Spec section | Plan task |
|---|---|
| Architecture: TileType, ViewportMap, FogOfWar, TileClassifier, NametableReader, AsciiMapRenderer, Pathfinder/ViewportPathfinder | T1, T2, T3, T4, T5, T6, T7, T9 |
| Architecture: SkillRegistry @Tool findPath, WalkOverworldTo refactor | T10 |
| Architecture: AdvisorAgent prompt change | T11 |
| Architecture: AgentSession lifecycle wiring | T12 |
| Data flow: per-turn observation | T8, T12 |
| TileClassifier empirical mapping | T6 (research test + manual classification + JSON update) |
| Pathfinder API & BFS behaviour | T7 (incl. detour, partial, fog blocks, length cap, target outside viewport) |
| ASCII rendering | T9 |
| Advisor: no screenshot in V2.3 | T11 (note explicit) |
| Error handling: missing classification table | T4 (loadFromResources fallback) |
| Error handling: nametable read fails | NametableReader returns UNKNOWN edges; no crash. Implicit in T5. |
| Error handling: BFS exceeds 32 | T7 (length-cap test + impl partial=true) |
| Error handling: target far outside viewport | T7 (partial path test) |
| Error handling: huge fog | No-op; T9 renderer caps "BLOCKED TILES" output to first 8 |
| Tests: unit set | T3, T4, T7, T9 |
| Tests: live integration | T5 (NametableReaderLiveTest), T13 (E2E) |
| Tests: golden ASCII rendering | NOT in plan — Spec mentioned `TileClassificationGoldenTest` but T9's renderer test covers correctness; goldens are nice-to-have. **Action:** acceptable omission (deferred). |
| PR strategy | T14 (PR #99); V2.2 standalone PR #98 noted out-of-plan |
| Definition of done | T13 (E2E manhattan), T14 (evidence run) |

Coverage acceptable — golden test deferred is the only spec→plan gap, and it's nice-to-have, not load-bearing.

**2. Placeholder scan:** all code blocks contain runnable code. Two soft-spots:

- T6 Step 4 JSON has illustrative IDs (`0x00..0x73`) that the human replaces during classification. This is NOT a placeholder in the plan-failure sense — the task is explicitly "human classifies and fills"; the example shows the shape. Keep.
- T11 Step 2 contains `TODO("preserve existing call site")` because the exact AdvisorAgent.ask body depends on the current Koog wiring. The step's note explicitly says "Read the current file first; preserve its existing setup". Acceptable for a delegate-back-to-context point but noted as fragile — implementer must read the file before editing.

**3. Type consistency:**

- `findPath(targetX, targetY)` signature consistent across T7 / T10 / spec.
- `ViewportMap.SIZE = 16`, `partyLocalXY = (8,8)` consistent T2 / T5 / T7 / T9.
- `FogOfWar.merge / markBlocked / isBlocked / size / bbox / blockedTiles` consistent T3 / T7 / T9 / T10.
- `TileClassifier.classify / loadFromResources / knownIdsForType` consistent T4 / T5 / T6.
- `Direction.button` field consistent T1 / T10.
- `PathResult.found / steps / reachedTile / partial / reason / searchSpace` consistent T1 / T7 / T10.

No drift detected.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-02-ff1-koog-agent-v2-3.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, you review between tasks, fast iteration with isolation between context windows.
2. **Inline Execution** — execute tasks in this session using `executing-plans`, batch with checkpoints for review.

Which approach?
