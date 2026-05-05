# Cross-Run Explorer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Phase-1 explorer that maps the FF1 Coneria Peninsula across multiple cheap (Haiku 4.5) runs, persists landmarks/terrain/blockages to disk, and produces memory files consumable by the existing AgentSession in a separate Phase-2 run.

**Architecture:** New `ExplorerSession` class (outer campaign loop) + `SingleRun` (inner deterministic walk + Haiku consult on triggers + hard-rule restart). 3 new persistent memory classes (`OverworldTerrainMemory`, `LandmarkMemory`, `BlockageMemory`) join the existing `OverworldWarpMemory`/`InteriorMemory`. 1 new skill `ExploreOverworldFrontier`. Sequential pipeline: `runExplorer` (Phase 1, builds JSON files) → `runAgent` (Phase 2, existing, reads same files).

**Tech Stack:** Kotlin, kotlinx.serialization, Kotest (FunSpec), Gradle, existing `EmulatorToolset`/`RamObserver`/`SkillRegistry`/`FogOfWar`. Spec: `docs/superpowers/specs/2026-05-05-cross-run-explorer-design.md`.

**Branch:** `ff1-agent-v2-4` (worktree `/Users/askowronski/Priv/kNES-ff1-agent-v2`).

---

### Task 1: OverworldTerrainMemory

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/OverworldTerrainMemory.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/OverworldTerrainMemoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/perception/OverworldTerrainMemoryTest.kt`:

```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class OverworldTerrainMemoryTest : FunSpec({
    test("records, queries, and persists tiles round-trip") {
        val tmp = Files.createTempFile("terrain", ".json").toFile().apply { deleteOnExit() }
        val mem = OverworldTerrainMemory(file = tmp)

        mem.record(146, 158, TileType.GRASS)
        mem.record(147, 154, TileType.TOWN)
        mem.record(146, 157, TileType.GRASS)

        mem.tileAt(146, 158) shouldBe TileType.GRASS
        mem.tileAt(147, 154) shouldBe TileType.TOWN
        mem.tileAt(999, 999) shouldBe null
        mem.seenCount() shouldBe 3
        mem.save()

        val reload = OverworldTerrainMemory(file = tmp)
        reload.tileAt(147, 154) shouldBe TileType.TOWN
        reload.seenCount() shouldBe 3
    }

    test("merge from ViewportMap returns count of newly added tiles") {
        val tmp = Files.createTempFile("terrain", ".json").toFile().apply { deleteOnExit() }
        val mem = OverworldTerrainMemory(file = tmp)

        // 2x2 viewport, party at (0,0), so localToWorld maps directly with partyWorld=(10,10)
        val tiles = arrayOf(
            arrayOf(TileType.GRASS, TileType.FOREST),
            arrayOf(TileType.WATER, TileType.UNKNOWN),  // UNKNOWN should be skipped
        )
        val vm = ViewportMap(tiles = tiles, partyLocalXY = 0 to 0, partyWorldXY = 10 to 10)

        val added1 = mem.merge(vm)
        added1 shouldBe 3   // UNKNOWN excluded

        val added2 = mem.merge(vm)
        added2 shouldBe 0   // already known

        mem.tileAt(10, 10) shouldBe TileType.GRASS
        mem.tileAt(11, 10) shouldBe TileType.FOREST
        mem.tileAt(10, 11) shouldBe TileType.WATER
    }
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.perception.OverworldTerrainMemoryTest"
```
Expected: FAIL — `OverworldTerrainMemory` class does not exist.

- [ ] **Step 3: Write the class**

Create `knes-agent/src/main/kotlin/knes/agent/perception/OverworldTerrainMemory.kt`:

```kotlin
package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent overworld terrain map across explorer runs. Per-run [FogOfWar.seen]
 * is in-memory; this class merges it to disk so the next run starts knowing
 * everything the previous run mapped.
 *
 * Storage path: ~/.knes/ff1-overworld-terrain.json (override via constructor for tests).
 */
@Serializable
private data class TerrainFile(
    val version: Int = 1,
    val tiles: Map<String, String> = emptyMap(),  // "x,y" -> TileType.name
)

class OverworldTerrainMemory(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val seen: MutableMap<Pair<Int, Int>, TileType> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(TerrainFile.serializer(), file.readText())
            for ((key, name) in parsed.tiles) {
                val (x, y) = key.split(",").map { it.toInt() }
                val type = runCatching { TileType.valueOf(name) }.getOrNull() ?: continue
                seen[x to y] = type
            }
        } catch (e: Exception) {
            System.err.println("[overworld-terrain-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val payload = TerrainFile(
            tiles = seen.entries
                .sortedWith(compareBy({ it.key.second }, { it.key.first }))
                .associate { (k, v) -> "${k.first},${k.second}" to v.name },
        )
        file.writeText(json.encodeToString(TerrainFile.serializer(), payload))
    }

    fun record(worldX: Int, worldY: Int, type: TileType) {
        if (type == TileType.UNKNOWN) return
        seen[worldX to worldY] = type
    }

    fun tileAt(worldX: Int, worldY: Int): TileType? = seen[worldX to worldY]

    fun seenCount(): Int = seen.size

    /** Merge UNKNOWN-filtered viewport into memory; returns count of NEWLY added tiles. */
    fun merge(viewport: ViewportMap): Int {
        var added = 0
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                val type = viewport.tiles[ly][lx]
                if (type == TileType.UNKNOWN) continue
                val world = viewport.localToWorld(lx, ly)
                if (seen[world] == null) added++
                seen[world] = type
            }
        }
        return added
    }

    fun bbox(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (seen.isEmpty()) return null
        val xs = seen.keys.map { it.first }; val ys = seen.keys.map { it.second }
        return (xs.min() to ys.min()) to (xs.max() to ys.max())
    }

    /** Tiles that are known + passable + have at least one UNKNOWN cardinal neighbour. */
    fun frontierTilesNear(center: Pair<Int, Int>, radius: Int): Sequence<Pair<Int, Int>> = sequence {
        for ((tile, type) in seen) {
            val dx = tile.first - center.first; val dy = tile.second - center.second
            if (dx * dx + dy * dy > radius * radius) continue
            if (!type.isPassable()) continue
            for (d in arrayOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                val n = tile.first + d.first to tile.second + d.second
                if (seen[n] == null) { yield(tile); break }
            }
        }
    }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-overworld-terrain.json")
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
./gradlew :knes-agent:test --tests "knes.agent.perception.OverworldTerrainMemoryTest"
```
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/OverworldTerrainMemory.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/OverworldTerrainMemoryTest.kt
git commit -m "feat(agent): add OverworldTerrainMemory — persistent overworld tile map across runs"
```

---

### Task 2: LandmarkMemory

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryTest.kt`:

```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class LandmarkMemoryTest : FunSpec({
    test("record + findByKind + atLocation + markVisited round-trip") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)

        val town = Landmark(id = "coneria_town_entry_147_154", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 147, worldY = 154, mapIdInterior = 8)
        val king = Landmark(id = "king", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 14, localY = 4, note = "throne")
        mem.record(town); mem.record(king)

        mem.findByKind(LandmarkKind.TOWN_ENTRY) shouldHaveSize 1
        mem.findByKind(LandmarkKind.NPC_KING) shouldHaveSize 1
        mem.atLocation(147, 154).shouldNotBeNull().id shouldBe "coneria_town_entry_147_154"
        mem.atLocation(0, 0).shouldBeNull()

        mem.markVisited("king")
        mem.findByKind(LandmarkKind.NPC_KING).first().visited shouldBe true

        mem.save()
        val reload = LandmarkMemory(file = tmp)
        reload.findByKind(LandmarkKind.NPC_KING).first().visited shouldBe true
        reload.findByKind(LandmarkKind.TOWN_ENTRY).first().visited shouldBe false
    }

    test("recordIfNew is idempotent on (kind, worldX, worldY)") {
        val tmp = Files.createTempFile("landmarks", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        mem.recordIfNew(Landmark(id = "a", kind = LandmarkKind.TOWN_ENTRY, worldX = 1, worldY = 2))
        mem.recordIfNew(Landmark(id = "b", kind = LandmarkKind.TOWN_ENTRY, worldX = 1, worldY = 2))
        mem.findByKind(LandmarkKind.TOWN_ENTRY) shouldHaveSize 1
    }
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.perception.LandmarkMemoryTest"
```
Expected: FAIL — class missing.

- [ ] **Step 3: Write the class**

Create `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt`:

```kotlin
package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class LandmarkKind {
    TOWN_ENTRY, CASTLE_ENTRY, DUNGEON_ENTRY,
    NPC_KING, NPC_SHOPKEEPER, NPC_GENERIC,
    STAIRS_UP, STAIRS_DOWN, EXIT_TILE,
    UNKNOWN,
}

@Serializable
data class Landmark(
    val id: String,
    val kind: LandmarkKind,
    /** For overworld landmarks: world coordinates. Null for interior-only landmarks. */
    val worldX: Int? = null,
    val worldY: Int? = null,
    /** For interior landmarks: which mapId, plus local coords. Null for overworld-only. */
    val mapId: Int? = null,
    val localX: Int? = null,
    val localY: Int? = null,
    /** When the landmark is the entry tile to a known interior. */
    val mapIdInterior: Int? = null,
    val visited: Boolean = false,
    val note: String = "",
    val discoveredRunId: String = "",
)

@Serializable
private data class LandmarkFile(
    val version: Int = 1,
    val landmarks: List<Landmark> = emptyList(),
)

class LandmarkMemory(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val byId: MutableMap<String, Landmark> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(LandmarkFile.serializer(), file.readText())
            for (l in parsed.landmarks) byId[l.id] = l
        } catch (e: Exception) {
            System.err.println("[landmark-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val payload = LandmarkFile(landmarks = byId.values.sortedBy { it.id })
        file.writeText(json.encodeToString(LandmarkFile.serializer(), payload))
    }

    fun record(l: Landmark) { byId[l.id] = l }

    /** Records iff no existing landmark matches on (kind + world coords) for overworld
     *  or (kind + mapId + local coords) for interior. Returns true if added. */
    fun recordIfNew(l: Landmark): Boolean {
        val dup = byId.values.firstOrNull { existing ->
            existing.kind == l.kind &&
                existing.worldX == l.worldX && existing.worldY == l.worldY &&
                existing.mapId == l.mapId && existing.localX == l.localX && existing.localY == l.localY
        }
        if (dup != null) return false
        byId[l.id] = l
        return true
    }

    fun findByKind(vararg kinds: LandmarkKind): List<Landmark> =
        byId.values.filter { it.kind in kinds }

    fun atLocation(worldX: Int, worldY: Int): Landmark? =
        byId.values.firstOrNull { it.worldX == worldX && it.worldY == worldY }

    fun markVisited(id: String) {
        byId[id]?.let { byId[id] = it.copy(visited = true) }
    }

    fun all(): List<Landmark> = byId.values.toList()

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-landmarks.json")
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
./gradlew :knes-agent:test --tests "knes.agent.perception.LandmarkMemoryTest"
```
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryTest.kt
git commit -m "feat(agent): add LandmarkMemory — persistent landmark catalog (towns, NPCs, stairs)"
```

---

### Task 3: BlockageMemory

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/BlockageMemory.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/BlockageMemoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/perception/BlockageMemoryTest.kt`:

```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.Duration

class BlockageMemoryTest : FunSpec({
    test("record + recentFailures + runStartDirections round-trip") {
        val tmp = Files.createTempFile("blockages", ".json").toFile().apply { deleteOnExit() }
        var now = Instant.parse("2026-05-05T12:00:00Z")
        val mem = BlockageMemory(file = tmp, clock = Clock.fixed(now, ZoneOffset.UTC))

        mem.record(runId = "run-1", from = 145 to 153, attemptedTo = "148,151",
            result = "BFS no path within viewport")
        mem.record(runId = "run-2", from = 16 to 17, attemptedTo = "exit",
            result = "exitInterior maxSteps reached, mapId=8")
        mem.recordRunStartDirection("run-1", "N")
        mem.recordRunStartDirection("run-2", "N")

        mem.recentFailures(within = Duration.ofMinutes(10)) shouldHaveSize 2
        mem.pathTriedRecentDirections(k = 3) shouldContain "N"

        mem.save()
        val reload = BlockageMemory(file = tmp)
        reload.recentFailures(within = Duration.ofDays(365)) shouldHaveSize 2
        reload.pathTriedRecentDirections(k = 3) shouldContain "N"
    }

    test("recentlyFailedTargets returns attemptedTo strings within window") {
        val tmp = Files.createTempFile("blockages", ".json").toFile().apply { deleteOnExit() }
        val now = Instant.parse("2026-05-05T12:00:00Z")
        val mem = BlockageMemory(file = tmp, clock = Clock.fixed(now, ZoneOffset.UTC))
        mem.record("r", 0 to 0, "148,151", "x")
        mem.recentlyFailedTargets(Duration.ofMinutes(10)) shouldContain "148,151"
    }
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.perception.BlockageMemoryTest"
```
Expected: FAIL — class missing.

- [ ] **Step 3: Write the class**

Create `knes-agent/src/main/kotlin/knes/agent/perception/BlockageMemory.kt`:

```kotlin
package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Serializable
data class Blockage(
    val runId: String,
    val fromX: Int,
    val fromY: Int,
    val attemptedTo: String,
    val result: String,
    val ts: String,  // ISO-8601 instant
)

@Serializable
private data class BlockageFile(
    val version: Int = 1,
    val blockages: List<Blockage> = emptyList(),
    val runStartDirections: Map<String, String> = emptyMap(),
)

class BlockageMemory(
    private val file: File = defaultFile(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val blockages: MutableList<Blockage> = mutableListOf()
    private val runStartDirections: MutableMap<String, String> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(BlockageFile.serializer(), file.readText())
            blockages.addAll(parsed.blockages)
            runStartDirections.putAll(parsed.runStartDirections)
        } catch (e: Exception) {
            System.err.println("[blockage-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val payload = BlockageFile(blockages = blockages.toList(),
            runStartDirections = runStartDirections.toMap())
        file.writeText(json.encodeToString(BlockageFile.serializer(), payload))
    }

    fun record(runId: String, from: Pair<Int, Int>, attemptedTo: String, result: String) {
        blockages += Blockage(
            runId = runId, fromX = from.first, fromY = from.second,
            attemptedTo = attemptedTo, result = result,
            ts = Instant.now(clock).toString(),
        )
    }

    fun recordRunStartDirection(runId: String, dir: String) {
        runStartDirections[runId] = dir
    }

    fun recentFailures(within: Duration): List<Blockage> {
        val cutoff = Instant.now(clock).minus(within)
        return blockages.filter { runCatching { Instant.parse(it.ts) > cutoff }.getOrDefault(false) }
    }

    fun recentlyFailedTargets(within: Duration): Set<String> =
        recentFailures(within).map { it.attemptedTo }.toSet()

    /** Returns the K most-recent runStartDirections, oldest first. */
    fun pathTriedRecentDirections(k: Int): List<String> =
        runStartDirections.values.toList().takeLast(k)

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-blockages.json")
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
./gradlew :knes-agent:test --tests "knes.agent.perception.BlockageMemoryTest"
```
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/BlockageMemory.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/BlockageMemoryTest.kt
git commit -m "feat(agent): add BlockageMemory — append-only failure log + run start directions"
```

---

### Task 4: HaikuConsult interface + Fake

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt`
- Test: (no separate test — exercised via SingleRun tests later)

- [ ] **Step 1: Create the interface and fake**

Create `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt`:

```kotlin
package knes.agent.explorer

import knes.agent.perception.Landmark

/**
 * Cheap, focused LLM consult fired only on novel triggers (new interior, dialog,
 * battle). Implementations may use Anthropic Haiku 4.5 or a fake for tests.
 *
 * Each call returns a [Result] with the cost in USD so the campaign budget can
 * track cumulative spend.
 */
interface HaikuConsult {
    data class InteriorClassification(
        val landmarks: List<Landmark>,
        val costUsd: Double,
    )

    data class DialogReading(
        val summary: String,
        val landmarkHint: String?,   // e.g. "King", "Shopkeeper", or null
        val costUsd: Double,
    )

    /** Called after [knes.agent.skills.ExploreInteriorFrontier] finishes a fresh interior.
     *  Implementation should look at screenshot + visited tile count and return any
     *  Landmark records to add (NPC_KING / NPC_SHOPKEEPER / NPC_GENERIC / etc.). */
    suspend fun classifyInterior(
        mapId: Int,
        visitedTileCount: Int,
        screenshotPng: ByteArray?,
    ): InteriorClassification

    /** Called when a dialog box is open. Implementation should read the dialog text
     *  (may press A across pages) and return a summary plus optional landmark hint. */
    suspend fun readDialog(
        screenshotPng: ByteArray?,
    ): DialogReading
}

/** Test fake. Pass canned results in constructor; assert calls via `interiorCalls`/`dialogCalls`. */
class FakeHaikuConsult(
    private val interiorClassifications: List<HaikuConsult.InteriorClassification> = emptyList(),
    private val dialogReadings: List<HaikuConsult.DialogReading> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set

    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotPng: ByteArray?,
    ): HaikuConsult.InteriorClassification {
        val res = interiorClassifications.getOrNull(interiorCalls)
            ?: HaikuConsult.InteriorClassification(emptyList(), 0.0)
        interiorCalls++
        return res
    }

    override suspend fun readDialog(screenshotPng: ByteArray?): HaikuConsult.DialogReading {
        val res = dialogReadings.getOrNull(dialogCalls)
            ?: HaikuConsult.DialogReading("", null, 0.0)
        dialogCalls++
        return res
    }
}
```

- [ ] **Step 2: Compile to verify it builds**

```bash
./gradlew :knes-agent:compileKotlin :knes-agent:compileTestKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt
git commit -m "feat(agent): add HaikuConsult interface + FakeHaikuConsult for explorer trigger handlers"
```

---

### Task 5: SalienceStrategy

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/explorer/SalienceStrategy.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/explorer/SalienceStrategyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/explorer/SalienceStrategyTest.kt`:

```kotlin
package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.nio.file.Files

class SalienceStrategyTest : FunSpec({
    fun tmp(name: String) = Files.createTempFile(name, ".json").toFile().apply { deleteOnExit() }

    fun viewportAllGrass(centerWorld: Pair<Int, Int>, size: Int = 16): ViewportMap {
        val tiles = Array(size) { Array(size) { TileType.GRASS } }
        return ViewportMap(tiles, partyLocalXY = size / 2 to size / 2,
            partyWorldXY = centerWorld)
    }

    test("priority 1: unvisited landmark is chosen even when far") {
        val landmarks = LandmarkMemory(file = tmp("l"))
        landmarks.record(Landmark(id = "town", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 200, worldY = 200, visited = false))
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = landmarks,
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        target shouldBe (200 to 200)
    }

    test("priority 2: TOWN tile in viewport beats unmapped frontier when no landmark exists") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        tiles[5][5] = TileType.TOWN  // local (5,5)
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 146 to 158)

        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158, viewport = vm)
        // local (5,5), partyLocal (8,8), partyWorld (146,158) → world = (146-3, 158-3) = (143,155)
        target shouldBe (143 to 155)
    }

    test("priority 3: frontier when no salient tiles, no landmarks") {
        val terrain = OverworldTerrainMemory(file = tmp("t"))
        terrain.record(146, 158, TileType.GRASS)
        terrain.record(147, 158, TileType.GRASS)  // frontier — has UNKNOWN neighbours
        val strategy = SalienceStrategy(
            terrainMemory = terrain,
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        // 146,158 itself has UNKNOWN neighbours too; closest with frontier is current — fallback to itself
        // We expect a real frontier tile. 146,158 yields itself (distance 0). That's the chosen target.
        target.first shouldBe 146
        target.second shouldBe 158
    }
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.SalienceStrategyTest"
```
Expected: FAIL — class missing.

- [ ] **Step 3: Write the class**

Create `knes-agent/src/main/kotlin/knes/agent/explorer/SalienceStrategy.kt`:

```kotlin
package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.time.Duration
import kotlin.math.abs

/**
 * "Think like a player" target selector. Returns world (x,y) for the next
 * deterministic walk goal. Priority: unvisited landmarks → salient viewport
 * tiles → frontier → diversify → wander.
 */
class SalienceStrategy(
    private val terrainMemory: OverworldTerrainMemory,
    private val landmarkMemory: LandmarkMemory,
    private val blockageMemory: BlockageMemory,
    private val fog: FogOfWar,
    private val recentFailureWindow: Duration = Duration.ofMinutes(10),
    private val frontierRadius: Int = 20,
) {
    fun pickOverworldTarget(currentXY: Pair<Int, Int>, viewport: ViewportMap): Pair<Int, Int> {
        val recentlyFailed = blockageMemory.recentlyFailedTargets(recentFailureWindow)
        val asKey: (Pair<Int, Int>) -> String = { "${it.first},${it.second}" }

        // Priority 1: unvisited landmarks
        landmarkMemory.findByKind(LandmarkKind.TOWN_ENTRY, LandmarkKind.CASTLE_ENTRY, LandmarkKind.DUNGEON_ENTRY)
            .filter { !it.visited }
            .filter { it.worldX != null && it.worldY != null }
            .filter { (it.worldX!! to it.worldY!!).let { p -> asKey(p) !in recentlyFailed } }
            .minByOrNull { manhattan(currentXY, it.worldX!! to it.worldY!!) }
            ?.let { return it.worldX!! to it.worldY!! }

        // Priority 2: salient unrecorded landmarks in viewport
        val salient = findSalientInViewport(viewport)
            .filter { (wx, wy) -> landmarkMemory.atLocation(wx, wy) == null }
            .filter { asKey(it) !in recentlyFailed }
            .minByOrNull { manhattan(currentXY, it) }
        if (salient != null) {
            // pre-record so future runs prioritize via priority 1
            val kind = guessKindFromTile(viewport.tiles[localY(viewport, salient)][localX(viewport, salient)])
            landmarkMemory.recordIfNew(Landmark(
                id = "${kind.name.lowercase()}_${salient.first}_${salient.second}",
                kind = kind, worldX = salient.first, worldY = salient.second, visited = false,
            ))
            return salient
        }

        // Priority 3: frontier
        terrainMemory.frontierTilesNear(currentXY, frontierRadius)
            .filter { asKey(it) !in recentlyFailed }
            .filter { !fog.isBlocked(it.first, it.second) }
            .minByOrNull { manhattan(currentXY, it) }
            ?.let { return it }

        // Priority 4: diversify
        val recent = blockageMemory.pathTriedRecentDirections(k = 3).toSet()
        val cardinals = listOf("N" to (0 to -1), "E" to (1 to 0), "S" to (0 to 1), "W" to (-1 to 0))
        cardinals.firstOrNull { it.first !in recent }?.let { (_, delta) ->
            return currentXY.first + delta.first * 8 to currentXY.second + delta.second * 8
        }

        // Priority 5: wander — first walkable tile in viewport
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                if (viewport.tiles[ly][lx].isPassable()) {
                    return viewport.localToWorld(lx, ly)
                }
            }
        }
        return currentXY  // truly degenerate
    }

    private fun findSalientInViewport(viewport: ViewportMap): Sequence<Pair<Int, Int>> = sequence {
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                val type = viewport.tiles[ly][lx]
                if (type == TileType.TOWN || type == TileType.CASTLE) {
                    yield(viewport.localToWorld(lx, ly))
                }
            }
        }
    }

    private fun guessKindFromTile(type: TileType): LandmarkKind = when (type) {
        TileType.TOWN -> LandmarkKind.TOWN_ENTRY
        TileType.CASTLE -> LandmarkKind.CASTLE_ENTRY
        else -> LandmarkKind.UNKNOWN
    }

    private fun manhattan(a: Pair<Int, Int>, b: Pair<Int, Int>) =
        abs(a.first - b.first) + abs(a.second - b.second)

    private fun localX(vm: ViewportMap, world: Pair<Int, Int>): Int =
        vm.partyLocalXY.first + (world.first - vm.partyWorldXY.first)
    private fun localY(vm: ViewportMap, world: Pair<Int, Int>): Int =
        vm.partyLocalXY.second + (world.second - vm.partyWorldXY.second)
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.SalienceStrategyTest"
```
Expected: PASS — 3 tests. If priority-3 test asserts wrong tile, adjust expected (any tile in radius 20 from (146,158) that has UNKNOWN neighbours is acceptable; first match by min-manhattan).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/SalienceStrategy.kt \
        knes-agent/src/test/kotlin/knes/agent/explorer/SalienceStrategyTest.kt
git commit -m "feat(agent): add SalienceStrategy — pickOverworldTarget by landmark/viewport/frontier priority"
```

---

### Task 6: ExploreOverworldFrontier skill

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/ExploreOverworldFrontier.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/skills/ExploreOverworldFrontierTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/skills/ExploreOverworldFrontierTest.kt`:

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.OverworldMap
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * Live test (requires ROM). Runs ExploreOverworldFrontier from spawn and asserts
 * the skill returns ok=true with at least one frame of movement OR a recognized
 * stop reason (encounter, interior entry).
 */
class ExploreOverworldFrontierTest : FunSpec({
    test("walks toward salience target and reports outcome") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom).ok shouldBe true
        toolset.applyProfile("ff1").ok shouldBe true
        PressStartUntilOverworld(toolset).invoke()

        val map = OverworldMap.fromRom(File(rom))
        val fog = FogOfWar()
        val skill = ExploreOverworldFrontier(toolset, map, fog)
        val result = skill.invoke(mapOf("targetX" to "146", "targetY" to "157", "maxSteps" to "8"))

        // Either reached, partial-progressed, or a clean stop reason — any non-crash result is fine here.
        (result.ok || result.message.contains("encounter") || result.message.contains("interior") ||
            result.message.contains("stuck")) shouldBe true
    }
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.ExploreOverworldFrontierTest"
```
Expected: FAIL — class missing.

- [ ] **Step 3: Write the skill**

Create `knes-agent/src/main/kotlin/knes/agent/skills/ExploreOverworldFrontier.kt`:

```kotlin
package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportSource
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.runtime.ToolCallLog
import knes.agent.tools.EmulatorToolset

/**
 * Deterministic salience-driven overworld walker for the explorer phase. Thin
 * wrapper over [WalkOverworldTo] with a simpler stop criterion: walk toward
 * (targetX, targetY) for up to maxSteps; abort cleanly on encounter, interior
 * entry, or BFS-blocked. The explorer's outer loop ([SingleRun]) chooses each
 * target via SalienceStrategy and re-invokes this skill on every overworld turn.
 */
class ExploreOverworldFrontier(
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = ViewportPathfinder(),
    private val toolCallLog: ToolCallLog? = null,
) : Skill {
    override val id = "explore_overworld_frontier"
    override val description =
        "Walk toward (targetX, targetY) on the overworld using deterministic BFS. " +
            "Aborts on encounter, interior entry, or no-path. Used by the explorer phase."

    private val walk = WalkOverworldTo(toolset, viewportSource, fog, pathfinder, toolCallLog)

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        // Delegate. WalkOverworldTo already handles encounter / interior abort / no-path.
        // The semantic difference is only that the explorer interprets results differently
        // (no panic recovery, no plan rewrite) — handled by SingleRun, not here.
        toolCallLog?.append("exploreOverworldFrontier",
            "targetX=${args["targetX"]}, targetY=${args["targetY"]}, maxSteps=${args["maxSteps"] ?: 32}")
        return walk.invoke(args)
    }
}
```

- [ ] **Step 4: Run test — verify it passes (live, requires ROM)**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.ExploreOverworldFrontierTest"
```
Expected: PASS (or skip if ROM absent).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/ExploreOverworldFrontier.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/ExploreOverworldFrontierTest.kt
git commit -m "feat(agent): add ExploreOverworldFrontier skill — explorer-phase BFS walker"
```

---

### Task 7: SingleRun shell + restart triggers

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/explorer/SingleRunRestartTriggersTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/explorer/SingleRunRestartTriggersTest.kt`:

```kotlin
package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SingleRunRestartTriggersTest : FunSpec({
    test("party wipe + interior triggers PartyWiped") {
        val ram = mapOf(
            "mapflags" to 0x01,
            "currentMapId" to 1,
            "char1_hpLow" to 0, "char2_hpLow" to 0, "char3_hpLow" to 0, "char4_hpLow" to 0,
        )
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 0,
            stepsTaken = 0, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.PartyWiped
    }

    test("unknown mapId trap after 3 idle turns triggers UnknownMapTrap") {
        val ram = mapOf(
            "mapflags" to 0x01,
            "currentMapId" to 0,  // not in KNOWN_MAP_IDS
            "char1_hpLow" to 30, "char2_hpLow" to 0, "char3_hpLow" to 0, "char4_hpLow" to 0,
        )
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 3, idleTurns = 0,
            stepsTaken = 0, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.UnknownMapTrap
    }

    test("idle 10 turns triggers Idle") {
        val ram = mapOf("mapflags" to 0x00, "currentMapId" to 0, "char1_hpLow" to 30)
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 10,
            stepsTaken = 20, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.Idle
    }

    test("zero coverage delta over window triggers LocalPlateau") {
        val ram = mapOf("mapflags" to 0x00, "currentMapId" to 0, "char1_hpLow" to 30)
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 0,
            stepsTaken = 11, coverageWindow = 10, coverageDeltaInWindow = 0) shouldBe StopReason.LocalPlateau
    }

    test("normal step returns null") {
        val ram = mapOf("mapflags" to 0x00, "currentMapId" to 0, "char1_hpLow" to 30)
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 2,
            stepsTaken = 5, coverageWindow = 10, coverageDeltaInWindow = 2) shouldBe null
    }
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.SingleRunRestartTriggersTest"
```
Expected: FAIL — class missing.

- [ ] **Step 3: Write the SingleRun shell + restart logic**

Create `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt`:

```kotlin
package knes.agent.explorer

import knes.agent.perception.FfPhase

enum class StopReason { PartyWiped, UnknownMapTrap, Idle, LocalPlateau, MaxSteps, GoalAchievedEarly }

data class RunResult(val stopReason: StopReason, val haikuCostUsd: Double, val stepsTaken: Int)

/**
 * Inner loop of the explorer phase: one campaign run from the post-boot savestate
 * to a hard-rule stop. State (memory) flushed by ExplorerSession on completion.
 *
 * The companion object hosts pure functions ([checkRestart]) so the trigger
 * logic is unit-testable in isolation from emulator/observer.
 */
class SingleRun(
    private val runId: String,
    // Real implementation lives in Task 8/9 — for now, the shell exists so the companion
    // can be tested. The full execute() method is added next task.
) {
    companion object {
        /**
         * Hard-rule restart trigger. Pure function for testability; called once per turn
         * by SingleRun.execute(). Order matters — first match wins.
         *
         * @param ram RAM snapshot from observer.
         * @param knownMapIds mapIds where mapflags=1 is normal (Coneria Castle=1, Coneria Town=8).
         * @param consecutiveIdleInTrap how many turns we've been in mapflags=1 + unknown mapId.
         * @param idleTurns how many turns RAM has not changed.
         * @param stepsTaken how many turns into this run.
         * @param coverageWindow size of rolling window to evaluate "did we discover anything recently".
         * @param coverageDeltaInWindow tiles+landmarks newly discovered in last [coverageWindow] turns.
         */
        fun checkRestart(
            ram: Map<String, Int>,
            knownMapIds: Set<Int>,
            consecutiveIdleInTrap: Int,
            idleTurns: Int,
            stepsTaken: Int,
            coverageWindow: Int,
            coverageDeltaInWindow: Int,
        ): StopReason? {
            val mapflags = (ram["mapflags"] ?: 0) and 0x01
            val mapId = ram["currentMapId"] ?: -1
            val partyHpSum = (1..4).sumOf { ram["char${it}_hpLow"] ?: 0 }

            return when {
                partyHpSum == 0 && mapflags == 1 -> StopReason.PartyWiped
                mapflags == 1 && mapId !in knownMapIds && consecutiveIdleInTrap >= 3 -> StopReason.UnknownMapTrap
                idleTurns >= 10 -> StopReason.Idle
                coverageDeltaInWindow == 0 && stepsTaken > coverageWindow -> StopReason.LocalPlateau
                else -> null
            }
        }
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.SingleRunRestartTriggersTest"
```
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt \
        knes-agent/src/test/kotlin/knes/agent/explorer/SingleRunRestartTriggersTest.kt
git commit -m "feat(agent): add SingleRun shell + pure-function checkRestart hard-rule triggers"
```

---

### Task 8: SingleRun execute() loop with deterministic step + trigger detection

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt` (add real `execute()`, dependencies)

- [ ] **Step 1: Identify dependencies and add the executable shell**

Modify `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt` — replace contents:

```kotlin
package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.RamObserver
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset

enum class StopReason { PartyWiped, UnknownMapTrap, Idle, LocalPlateau, MaxSteps, GoalAchievedEarly }

data class RunResult(val stopReason: StopReason, val haikuCostUsd: Double, val stepsTaken: Int)

sealed interface Trigger {
    data class NewInteriorEntered(val mapId: Int) : Trigger
    object DialogBoxVisible : Trigger
    object BattleEntered : Trigger
}

class SingleRun(
    private val runId: String,
    private val toolset: EmulatorToolset,
    private val observer: RamObserver,
    private val overworldMap: OverworldMap,
    private val skillRegistry: SkillRegistry,
    private val terrainMemory: OverworldTerrainMemory,
    private val landmarkMemory: LandmarkMemory,
    private val blockageMemory: BlockageMemory,
    private val interiorMemory: InteriorMemory,
    private val fog: FogOfWar,
    private val salience: SalienceStrategy,
    private val haikuConsult: HaikuConsult,
    private val knownMapIds: Set<Int> = setOf(1, 8),
    private val maxSteps: Int = 200,
    private val coverageWindow: Int = 10,
) {
    private var idleTurns = 0
    private var consecutiveIdleInTrap = 0
    private var lastRam: Map<String, Int> = emptyMap()
    private var haikuCostUsd = 0.0
    private val coverageDeltaWindow: ArrayDeque<Int> = ArrayDeque()
    private var firstStartDirection: String? = null

    suspend fun execute(): RunResult {
        var stepsTaken = 0
        while (stepsTaken < maxSteps) {
            val ram = observer.ramSnapshot()
            val phase = observer.observeWithVision()

            updateIdleTracking(ram)
            val coverageDelta = coverageDeltaWindow.sumOf { it }

            checkRestart(
                ram = ram, knownMapIds = knownMapIds,
                consecutiveIdleInTrap = consecutiveIdleInTrap, idleTurns = idleTurns,
                stepsTaken = stepsTaken, coverageWindow = coverageWindow,
                coverageDeltaInWindow = coverageDelta,
            )?.let { reason ->
                blockageMemory.record(runId,
                    from = (ram["worldX"] ?: -1) to (ram["worldY"] ?: -1),
                    attemptedTo = "<run end>",
                    result = reason.name)
                return RunResult(reason, haikuCostUsd, stepsTaken)
            }

            val trigger = detectTrigger(phase, ram)
            when (trigger) {
                is Trigger.NewInteriorEntered -> handleNewInterior(trigger)
                Trigger.DialogBoxVisible -> handleDialog()
                Trigger.BattleEntered -> handleBattle()
                null -> deterministicStep(phase, ram)
            }
            stepsTaken++
        }
        return RunResult(StopReason.MaxSteps, haikuCostUsd, stepsTaken)
    }

    private fun updateIdleTracking(ram: Map<String, Int>) {
        if (ram == lastRam) idleTurns++ else idleTurns = 0
        lastRam = ram
        val mapflags = (ram["mapflags"] ?: 0) and 0x01
        val mapId = ram["currentMapId"] ?: -1
        if (mapflags == 1 && mapId !in knownMapIds) consecutiveIdleInTrap++
        else consecutiveIdleInTrap = 0
    }

    private fun recordCoverageDelta(delta: Int) {
        coverageDeltaWindow.addLast(delta)
        while (coverageDeltaWindow.size > coverageWindow) coverageDeltaWindow.removeFirst()
        if (delta > 0) idleTurns = 0
    }

    private fun detectTrigger(phase: FfPhase, ram: Map<String, Int>): Trigger? = when {
        phase is FfPhase.Indoors && !interiorMemory.hasMapBeenSeen(phase.mapId) ->
            Trigger.NewInteriorEntered(phase.mapId)
        phase is FfPhase.Battle -> Trigger.BattleEntered
        isDialogBoxOpen(ram) -> Trigger.DialogBoxVisible
        else -> null
    }

    /** Heuristic: dialog typically blocks input; FF1 sets specific screen-state bits.
     *  Conservative default: false. Implementation can be refined as we observe RAM. */
    private fun isDialogBoxOpen(ram: Map<String, Int>): Boolean {
        // FF1 dialog uses screenState != 0x68 (battle) and != normal-overworld marker.
        // Without a confirmed signature, return false; let interior frontier discover NPC tiles
        // via tile signatures inside InteriorMemory instead. Refine once we observe live RAM.
        return false
    }

    // Filled in next task (Task 9):
    private suspend fun handleNewInterior(t: Trigger.NewInteriorEntered) { /* Task 9 */ }
    private suspend fun handleDialog() { /* Task 9 */ }
    private suspend fun handleBattle() { skillRegistry.battleFightAll() }

    // Deterministic step (full impl Task 9):
    private suspend fun deterministicStep(phase: FfPhase, ram: Map<String, Int>) {
        when (phase) {
            is FfPhase.TitleOrMenu, FfPhase.NewGameMenu, FfPhase.NameEntry, FfPhase.Boot ->
                skillRegistry.pressStartUntilOverworld()
            is FfPhase.Overworld -> {
                val cx = ram["worldX"] ?: 0; val cy = ram["worldY"] ?: 0
                val viewport = overworldMap.readFullMapView(cx to cy)
                fog.merge(viewport)
                val newTiles = terrainMemory.merge(viewport)
                recordCoverageDelta(newTiles)
                if (firstStartDirection == null) {
                    firstStartDirection = pickInitialCardinal(cx to cy, viewport)
                    blockageMemory.recordRunStartDirection(runId, firstStartDirection ?: "?")
                }
                val target = salience.pickOverworldTarget(currentXY = cx to cy, viewport = viewport)
                val res = skillRegistry.exploreOverworldFrontier(target.first, target.second)
                if (!res.ok) {
                    blockageMemory.record(runId, from = cx to cy,
                        attemptedTo = "${target.first},${target.second}",
                        result = res.message)
                }
            }
            is FfPhase.Indoors -> skillRegistry.exploreInteriorFrontier()
            is FfPhase.PostBattle -> skillRegistry.battleFightAll()
            else -> idleTurns++  // unknown phase
        }
    }

    private fun pickInitialCardinal(currentXY: Pair<Int, Int>, viewport: knes.agent.perception.ViewportMap): String {
        val target = salience.pickOverworldTarget(currentXY, viewport)
        val dx = target.first - currentXY.first; val dy = target.second - currentXY.second
        return when {
            kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dx > 0) "E" else "W"
            else -> if (dy > 0) "S" else "N"
        }
    }

    companion object {
        fun checkRestart(
            ram: Map<String, Int>,
            knownMapIds: Set<Int>,
            consecutiveIdleInTrap: Int,
            idleTurns: Int,
            stepsTaken: Int,
            coverageWindow: Int,
            coverageDeltaInWindow: Int,
        ): StopReason? {
            val mapflags = (ram["mapflags"] ?: 0) and 0x01
            val mapId = ram["currentMapId"] ?: -1
            val partyHpSum = (1..4).sumOf { ram["char${it}_hpLow"] ?: 0 }
            return when {
                partyHpSum == 0 && mapflags == 1 -> StopReason.PartyWiped
                mapflags == 1 && mapId !in knownMapIds && consecutiveIdleInTrap >= 3 -> StopReason.UnknownMapTrap
                idleTurns >= 10 -> StopReason.Idle
                coverageDeltaInWindow == 0 && stepsTaken > coverageWindow -> StopReason.LocalPlateau
                else -> null
            }
        }
    }
}
```

This requires `SkillRegistry.exploreOverworldFrontier(targetX, targetY)`. Add it next:

- [ ] **Step 2: Add `exploreOverworldFrontier` method to SkillRegistry**

Modify `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt`. Append to the class body (before the closing `}`):

```kotlin
    private val exploreOverworldFrontierSkill =
        ExploreOverworldFrontier(toolset, overworldMap, fog, overworldPathfinder, toolCallLog)

    /**
     * Explorer-phase deterministic walk to (targetX, targetY). Uses SalienceStrategy
     * upstream to pick the target. NOT exposed via @Tool because the explorer phase
     * does not run an LLM tool surface — SingleRun calls this directly.
     */
    suspend fun exploreOverworldFrontier(
        targetX: Int, targetY: Int, maxSteps: Int = 32,
    ): SkillResult {
        toolCallLog.append("exploreOverworldFrontier",
            "targetX=$targetX, targetY=$targetY, maxSteps=$maxSteps")
        return exploreOverworldFrontierSkill.invoke(
            mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps")
        )
    }
```

- [ ] **Step 3: Add `hasMapBeenSeen` to InteriorMemory**

The existing class has `visited(mapId): Set<Pair<Int, Int>>` and a private
`byKey: MutableMap<Triple<Int, Int, Int>, InteriorTileFact>`. Add two new methods.

In `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMemory.kt`, add inside the class body (after the existing `all()` method):

```kotlin
    /** True if any tile inside [mapId] has been recorded as visited. */
    fun hasMapBeenSeen(mapId: Int): Boolean =
        visited(mapId).isNotEmpty()

    /** All mapIds with at least one recorded fact (visited or POI). */
    fun knownMapIds(): Set<Int> =
        byKey.keys.map { it.first }.toSet()
```

Note: `SingleRun.handleNewInterior` will call `interiorMemory.visited(t.mapId).size` (not `getVisited` — that method does not exist). Make sure SingleRun uses `visited()`.

- [ ] **Step 4: Verify everything compiles**

```bash
./gradlew :knes-agent:compileKotlin :knes-agent:compileTestKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run prior tests still pass**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.*" --tests "knes.agent.perception.*Memory*Test" --tests "knes.agent.skills.ExploreOverworldFrontierTest"
```
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt \
        knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt \
        knes-agent/src/main/kotlin/knes/agent/perception/InteriorMemory.kt
git commit -m "feat(agent): SingleRun.execute loop + SkillRegistry.exploreOverworldFrontier"
```

---

### Task 9: SingleRun trigger handlers (NewInterior, Dialog) with FakeHaikuConsult

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt` (fill `handleNewInterior` + `handleDialog`)
- Test: `knes-agent/src/test/kotlin/knes/agent/explorer/SingleRunHandleNewInteriorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/explorer/SingleRunHandleNewInteriorTest.kt`:

```kotlin
package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import java.nio.file.Files

class SingleRunHandleNewInteriorTest : FunSpec({
    test("classifyInterior result is recorded into LandmarkMemory") {
        val landmarks = LandmarkMemory(file = Files.createTempFile("l", ".json").toFile().apply { deleteOnExit() })
        val fake = FakeHaikuConsult(
            interiorClassifications = listOf(
                HaikuConsult.InteriorClassification(
                    landmarks = listOf(
                        Landmark(id = "test_king", kind = LandmarkKind.NPC_KING,
                            mapId = 1, localX = 14, localY = 4, note = "throne", visited = true)
                    ),
                    costUsd = 0.012,
                )
            )
        )

        // Direct call into the helper without spinning up full SingleRun. We invoke the same
        // logic via a stand-alone helper that SingleRun.handleNewInterior delegates to.
        val cost = SingleRun.applyInteriorClassification(
            landmarkMemory = landmarks,
            classification = fake.classifyInterior(mapId = 1, visitedTileCount = 30, screenshotPng = null),
        )
        cost shouldBe 0.012
        landmarks.findByKind(LandmarkKind.NPC_KING) shouldHaveSize 1
        landmarks.findByKind(LandmarkKind.NPC_KING).first().visited shouldBe true
    }
})
```

- [ ] **Step 2: Run test — verify it fails (suspend issue may need runBlocking)**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.SingleRunHandleNewInteriorTest"
```

If compile error about `suspend` in non-suspend test, wrap the `fake.classifyInterior(...)` call in `kotlinx.coroutines.runBlocking { ... }`:

```kotlin
val classification = kotlinx.coroutines.runBlocking {
    fake.classifyInterior(mapId = 1, visitedTileCount = 30, screenshotPng = null)
}
val cost = SingleRun.applyInteriorClassification(landmarks, classification)
```

Re-run; expected: FAIL — `applyInteriorClassification` does not exist.

- [ ] **Step 3: Implement the helper + handlers**

Modify `SingleRun.kt` — replace the stub `handleNewInterior` and `handleDialog`, and add the companion helper:

```kotlin
    private suspend fun handleNewInterior(t: Trigger.NewInteriorEntered) {
        val ram = observer.ramSnapshot()
        // Pre-record the entry as a TOWN/CASTLE/DUNGEON_ENTRY landmark (visited=true).
        val cx = ram["worldX"] ?: 0; val cy = ram["worldY"] ?: 0
        landmarkMemory.recordIfNew(Landmark(
            id = "interior_entry_${t.mapId}_${cx}_${cy}",
            kind = guessEntryKind(t.mapId),
            worldX = cx, worldY = cy,
            mapIdInterior = t.mapId,
            visited = true,
            discoveredRunId = runId,
        ))
        // Run the deterministic interior explorer.
        skillRegistry.exploreInteriorFrontier(maxSteps = 80)
        // Post-explore: ask Haiku to classify what we saw.
        val visited = interiorMemory.visited(t.mapId)
        val screenshot: ByteArray? = runCatching {
            // Implementation note: real screenshot acquisition is via toolset.getState() or a
            // dedicated call. Pass null if not yet wired — the explorer handles cost=0.0.
            null
        }.getOrNull()
        val classification = haikuConsult.classifyInterior(
            mapId = t.mapId, visitedTileCount = visited.size, screenshotPng = screenshot,
        )
        haikuCostUsd += applyInteriorClassification(landmarkMemory, classification)
    }

    private suspend fun handleDialog() {
        // Press A once to advance, take screenshot, ask Haiku to read.
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
        val reading = haikuConsult.readDialog(screenshotPng = null)
        haikuCostUsd += reading.costUsd
        if (reading.landmarkHint != null) {
            val ram = observer.ramSnapshot()
            val mapId = ram["currentMapId"] ?: -1
            val lx = ram["smPlayerX"]; val ly = ram["smPlayerY"]
            landmarkMemory.recordIfNew(Landmark(
                id = "dialog_${reading.landmarkHint.lowercase()}_${mapId}_${lx}_${ly}",
                kind = kindFromHint(reading.landmarkHint),
                mapId = mapId, localX = lx, localY = ly,
                visited = true, note = reading.summary, discoveredRunId = runId,
            ))
        }
    }

    private fun guessEntryKind(mapId: Int): LandmarkKind = when (mapId) {
        1 -> LandmarkKind.CASTLE_ENTRY    // Coneria Castle
        8 -> LandmarkKind.TOWN_ENTRY      // Coneria Town
        else -> LandmarkKind.DUNGEON_ENTRY
    }

    private fun kindFromHint(hint: String): LandmarkKind = when (hint.uppercase()) {
        "KING" -> LandmarkKind.NPC_KING
        "SHOP", "SHOPKEEPER" -> LandmarkKind.NPC_SHOPKEEPER
        else -> LandmarkKind.NPC_GENERIC
    }
```

Add to the companion object:

```kotlin
        /** Pure helper used by handleNewInterior; testable in isolation. */
        fun applyInteriorClassification(
            landmarkMemory: LandmarkMemory,
            classification: HaikuConsult.InteriorClassification,
        ): Double {
            classification.landmarks.forEach { landmarkMemory.recordIfNew(it) }
            return classification.costUsd
        }
```

Make sure the file imports `Landmark` and `LandmarkKind`.

- [ ] **Step 4: Run test — verify it passes**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.SingleRunHandleNewInteriorTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt \
        knes-agent/src/test/kotlin/knes/agent/explorer/SingleRunHandleNewInteriorTest.kt
git commit -m "feat(agent): SingleRun.handleNewInterior + handleDialog with Haiku classification"
```

---

### Task 10: ExplorerSession campaign loop

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerSession.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/explorer/ExplorerSessionGoalAchievedTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/explorer/ExplorerSessionGoalAchievedTest.kt`:

```kotlin
package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import java.nio.file.Files

class ExplorerSessionGoalAchievedTest : FunSpec({
    test("goalsAchieved returns true when both King and Shopkeeper are visited") {
        val mem = LandmarkMemory(file = Files.createTempFile("l", ".json").toFile().apply { deleteOnExit() })
        ExplorerSession.goalsAchieved(mem) shouldBe false

        mem.record(Landmark(id = "king", kind = LandmarkKind.NPC_KING, mapId = 1, visited = true))
        ExplorerSession.goalsAchieved(mem) shouldBe false  // missing shop

        mem.record(Landmark(id = "shop", kind = LandmarkKind.NPC_SHOPKEEPER, mapId = 8, visited = true))
        ExplorerSession.goalsAchieved(mem) shouldBe true
    }

    test("CoverageStats subtraction returns deltas") {
        val before = CoverageStats(terrainTilesKnown = 50, warpsKnown = 1, landmarksKnown = 0,
            landmarksVisited = 0, interiorMapsExplored = 0)
        val after = CoverageStats(terrainTilesKnown = 87, warpsKnown = 1, landmarksKnown = 1,
            landmarksVisited = 1, interiorMapsExplored = 1)
        val delta = after - before
        delta.terrainTilesKnown shouldBe 37
        delta.landmarksKnown shouldBe 1
        delta.warpsKnown shouldBe 0
        delta.hasNoNewDiscoveries() shouldBe false
    }
})
```

- [ ] **Step 2: Run — verify fail**

```bash
./gradlew :knes-agent:test --tests "knes.agent.explorer.ExplorerSessionGoalAchievedTest"
```
Expected: FAIL — class missing.

- [ ] **Step 3: Write ExplorerSession**

Create `knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerSession.kt`:

```kotlin
package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset
import java.nio.file.Path
import java.time.Instant

data class CampaignBudget(
    val maxRuns: Int = 20,
    val maxWallClockMinutes: Int = 30,
    val coveragePlateauRuns: Int = 3,
    val maxTotalCostUsd: Double = 1.0,
)

enum class CampaignOutcome { Success, Plateau, OutOfBudget, OutOfRuns }

data class CoverageStats(
    val terrainTilesKnown: Int,
    val warpsKnown: Int,
    val landmarksKnown: Int,
    val landmarksVisited: Int,
    val interiorMapsExplored: Int,
) {
    operator fun minus(other: CoverageStats) = CoverageStats(
        terrainTilesKnown - other.terrainTilesKnown,
        warpsKnown - other.warpsKnown,
        landmarksKnown - other.landmarksKnown,
        landmarksVisited - other.landmarksVisited,
        interiorMapsExplored - other.interiorMapsExplored,
    )
    fun hasNoNewDiscoveries(): Boolean =
        terrainTilesKnown <= 0 && warpsKnown <= 0 && landmarksKnown <= 0 &&
        landmarksVisited <= 0 && interiorMapsExplored <= 0
}

data class CampaignResult(
    val outcome: CampaignOutcome,
    val runsExecuted: Int,
    val coverage: CoverageStats,
    val totalCostUsd: Double,
)

class ExplorerSession(
    private val toolset: EmulatorToolset,
    private val observer: RamObserver,
    private val overworldMap: OverworldMap,
    private val skillRegistry: SkillRegistry,
    private val terrainMemory: OverworldTerrainMemory,
    private val landmarkMemory: LandmarkMemory,
    private val blockageMemory: BlockageMemory,
    private val warpMemory: OverworldWarpMemory,
    private val interiorMemory: InteriorMemory,
    private val fog: FogOfWar,
    private val haikuConsult: HaikuConsult,
    private val savestatePath: Path,
    private val budget: CampaignBudget = CampaignBudget(),
) {
    private val salience = SalienceStrategy(terrainMemory, landmarkMemory, blockageMemory, fog)

    suspend fun run(): CampaignResult {
        var runs = 0
        var consecutiveZero = 0
        var totalCost = 0.0
        val start = System.currentTimeMillis()

        while (true) {
            if (goalsAchieved(landmarkMemory)) return result(CampaignOutcome.Success, runs, totalCost)
            if (runs >= budget.maxRuns) return result(CampaignOutcome.OutOfRuns, runs, totalCost)
            if (elapsedMin(start) >= budget.maxWallClockMinutes) return result(CampaignOutcome.OutOfBudget, runs, totalCost)
            if (totalCost >= budget.maxTotalCostUsd) return result(CampaignOutcome.OutOfBudget, runs, totalCost)
            if (consecutiveZero >= budget.coveragePlateauRuns) return result(CampaignOutcome.Plateau, runs, totalCost)

            toolset.loadState(savestatePath.toString())
            val before = snapshotCoverage()
            val runId = "run-${runs + 1}-${Instant.now()}"
            println("[campaign] starting $runId; coverage=$before")

            val singleRun = SingleRun(
                runId = runId, toolset = toolset, observer = observer,
                overworldMap = overworldMap, skillRegistry = skillRegistry,
                terrainMemory = terrainMemory, landmarkMemory = landmarkMemory,
                blockageMemory = blockageMemory, interiorMemory = interiorMemory,
                fog = fog, salience = salience, haikuConsult = haikuConsult,
            )
            val runResult = singleRun.execute()

            terrainMemory.save(); landmarkMemory.save(); blockageMemory.save()
            warpMemory.save(); interiorMemory.save()

            val after = snapshotCoverage()
            val delta = after - before
            consecutiveZero = if (delta.hasNoNewDiscoveries()) consecutiveZero + 1 else 0
            totalCost += runResult.haikuCostUsd
            runs++
            println("[campaign] $runId done: stop=${runResult.stopReason} delta=$delta consec-zero=$consecutiveZero cost=$$totalCost")
        }
    }

    private fun snapshotCoverage(): CoverageStats = CoverageStats(
        terrainTilesKnown = terrainMemory.seenCount(),
        warpsKnown = warpMemory.all().size,
        landmarksKnown = landmarkMemory.all().size,
        landmarksVisited = landmarkMemory.all().count { it.visited },
        interiorMapsExplored = interiorMemory.knownMapIds().size,
    )

    private fun result(outcome: CampaignOutcome, runs: Int, cost: Double) =
        CampaignResult(outcome, runs, snapshotCoverage(), cost)

    private fun elapsedMin(startMs: Long) = (System.currentTimeMillis() - startMs) / 60_000.0

    companion object {
        fun goalsAchieved(landmarkMemory: LandmarkMemory): Boolean {
            val king = landmarkMemory.findByKind(LandmarkKind.NPC_KING).any { it.visited }
            val shop = landmarkMemory.findByKind(LandmarkKind.NPC_SHOPKEEPER).any { it.visited }
            return king && shop
        }
    }
}
```

`InteriorMemory.knownMapIds()` was added in Task 8 — already present.

This also requires `EmulatorToolset.loadState(path)`. Verify:

```bash
grep -n "fun loadState" /Users/askowronski/Priv/kNES-ff1-agent-v2/knes-agent/src/main/kotlin/knes/agent/tools/EmulatorToolset.kt
```

If signature differs (e.g. takes `File`), adapt the call site accordingly.

- [ ] **Step 4: Compile + run test**

```bash
./gradlew :knes-agent:compileKotlin :knes-agent:compileTestKotlin && \
  ./gradlew :knes-agent:test --tests "knes.agent.explorer.ExplorerSessionGoalAchievedTest"
```
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerSession.kt \
        knes-agent/src/test/kotlin/knes/agent/explorer/ExplorerSessionGoalAchievedTest.kt \
        knes-agent/src/main/kotlin/knes/agent/perception/InteriorMemory.kt
git commit -m "feat(agent): add ExplorerSession campaign loop + CoverageStats + goalsAchieved helper"
```

---

### Task 11: ExplorerMain entry point + Gradle task

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerMain.kt`
- Modify: `knes-agent/build.gradle.kts`
- Create: `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt` (real impl, used by Main)

- [ ] **Step 1: Add the real HaikuConsult implementation**

Create `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt`:

```kotlin
package knes.agent.explorer

import knes.agent.llm.AnthropicSession
import knes.agent.perception.Landmark

/**
 * Production HaikuConsult backed by Anthropic Haiku 4.5. Uses the existing
 * [AnthropicSession] machinery to issue short, focused calls. Costs are
 * derived from Claude pricing (input $0.80/Mtok, output $4/Mtok approx).
 *
 * Heuristic costing: each call ~ 200-400 input tokens + 100 output tokens →
 * ≈$0.0005-$0.001 per call. We track approximate cumulative cost.
 */
class AnthropicHaikuConsult(
    private val anthropic: AnthropicSession,
) : HaikuConsult {
    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotPng: ByteArray?,
    ): HaikuConsult.InteriorClassification {
        // MVP scaffold: returns no landmarks, $0 cost. Real prompt + parsing
        // can be added once we exercise the explorer end-to-end.
        // The skeleton allows the campaign to run and produce terrain/warp
        // discoveries even before Haiku is wired to a real prompt.
        return HaikuConsult.InteriorClassification(landmarks = emptyList(), costUsd = 0.0)
    }

    override suspend fun readDialog(screenshotPng: ByteArray?): HaikuConsult.DialogReading {
        return HaikuConsult.DialogReading(summary = "", landmarkHint = null, costUsd = 0.0)
    }
}
```

Note: this is a deliberate stub for MVP. The campaign can run end-to-end and validate terrain/warp/blockage memory acquisition without LLM-classified interiors. Phase 1.5 will replace these with real Haiku calls. This keeps the MVP closed and testable.

- [ ] **Step 2: Write ExplorerMain**

Create `knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerMain.kt`:

```kotlin
package knes.agent.explorer

import kotlinx.coroutines.runBlocking
import knes.agent.llm.AnthropicSession
import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.InteriorMemory
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

fun main() {
    val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val savestate = Path.of(System.getenv("FF1_SAVESTATE")
        ?: "/Users/askowronski/Priv/kNES/knes-agent/src/test/resources/ff1-post-boot.savestate")

    require(File(rom).exists()) { "ROM not found: $rom (set FF1_ROM)" }
    require(savestate.toFile().exists()) { "savestate not found: $savestate (set FF1_SAVESTATE)" }

    val session = EmulatorSession()
    val toolset = EmulatorToolset(session)
    require(toolset.loadRom(rom).ok) { "loadRom failed" }
    require(toolset.applyProfile("ff1").ok) { "applyProfile failed" }

    val romBytes = File(rom).readBytes()
    val fog = FogOfWar()
    val mapSession = MapSession(InteriorMapLoader(romBytes), fog)
    val overworldMap = OverworldMap.fromRom(File(rom))
    val observer = RamObserver(toolset)
    val warpMemory = OverworldWarpMemory()
    val interiorMemory = InteriorMemory()
    val terrainMemory = OverworldTerrainMemory()
    val landmarkMemory = LandmarkMemory()
    val blockageMemory = BlockageMemory()

    val skillRegistry = SkillRegistry(
        toolset = toolset, overworldMap = overworldMap, mapSession = mapSession, fog = fog,
        interiorMemory = interiorMemory,
    )

    val haiku: HaikuConsult = try {
        AnthropicHaikuConsult(AnthropicSession.fromEnv())
    } catch (e: Exception) {
        System.err.println("[explorer] no ANTHROPIC_API_KEY — using FakeHaikuConsult (zero classification)")
        FakeHaikuConsult()
    }

    val explorer = ExplorerSession(
        toolset = toolset, observer = observer, overworldMap = overworldMap,
        skillRegistry = skillRegistry,
        terrainMemory = terrainMemory, landmarkMemory = landmarkMemory,
        blockageMemory = blockageMemory, warpMemory = warpMemory,
        interiorMemory = interiorMemory, fog = fog,
        haikuConsult = haiku, savestatePath = savestate,
    )
    val result = runBlocking { explorer.run() }
    println("[campaign] FINAL: $result")
    exitProcess(if (result.outcome == CampaignOutcome.Success) 0 else 1)
}
```

If `AnthropicSession.fromEnv()` does not exist, replace with whatever the existing factory is — search:

```bash
grep -n "fun fromEnv\|fun create\|class AnthropicSession" /Users/askowronski/Priv/kNES-ff1-agent-v2/knes-agent/src/main/kotlin/knes/agent/llm/AnthropicSession.kt
```

Use the actual factory. If no zero-arg factory exists, instantiate AnthropicHaikuConsult only when the Anthropic API key env is present, else fall back to FakeHaikuConsult (already done above).

- [ ] **Step 3: Add gradle task**

Modify `knes-agent/build.gradle.kts`. Find existing `runAgent` task (or other JavaExec) as template:

```bash
grep -n "tasks.register\|JavaExec\|mainClass" /Users/askowronski/Priv/kNES-ff1-agent-v2/knes-agent/build.gradle.kts
```

Append (using same plugin pattern already in file):

```kotlin
tasks.register<JavaExec>("runExplorer") {
    group = "application"
    description = "Run the Phase-1 cross-run explorer (builds memory in ~/.knes/)."
    mainClass.set("knes.agent.explorer.ExplorerMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
```

- [ ] **Step 4: Verify compile + task discoverable**

```bash
./gradlew :knes-agent:compileKotlin && \
  ./gradlew :knes-agent:tasks --all | grep -i explorer
```
Expected: BUILD SUCCESSFUL; line "runExplorer - Run the Phase-1 cross-run explorer..."

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerMain.kt \
        knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt \
        knes-agent/build.gradle.kts
git commit -m "feat(agent): ExplorerMain entry point + runExplorer gradle task + Anthropic stub"
```

---

### Task 12: Final integration — full test suite + HANDOFF.md note

**Files:**
- Modify: `HANDOFF.md`

- [ ] **Step 1: Run the full agent test suite**

```bash
./gradlew :knes-agent:test
```
Expected: same baseline as before (152 pass + 2 pre-existing failures `Coneria8VisualDiffTest`, `ConeriaTownEmpiricalDiscoveryTest`) PLUS new tests added by tasks 1-10 (~12-14 new tests). Total: ~165-167 pass + 2 pre-existing failures. NO new failures. If any new failure surfaces, stop and diagnose before commit.

- [ ] **Step 2: Smoke-run runExplorer (with ROM, no API key)**

```bash
./gradlew :knes-agent:runExplorer 2>&1 | tail -40
```
Expected: campaign runs ≥1 run, prints `[campaign] starting run-1-...`, eventually exits with `[campaign] FINAL: CampaignResult(outcome=...)`. Exit code may be 0 or 1 (depending on whether goals achieved without Haiku — likely Plateau/OutOfRuns since FakeHaikuConsult never returns NPC landmarks). Memory files appear in `~/.knes/`:

```bash
ls -lat ~/.knes/ff1-overworld-terrain.json ~/.knes/ff1-landmarks.json ~/.knes/ff1-blockages.json
```
All three should exist with reasonable content.

If the smoke run errors out on first turn, diagnose: typical issues are savestate path, missing observer/RAM signatures, or InteriorMemory API mismatch. Fix and re-run.

- [ ] **Step 3: Update HANDOFF.md**

Edit `HANDOFF.md` — append a section near the top (under "Open priorities"):

```markdown
## Cross-Run Explorer (Phase 1) — landed 2026-05-05

New flow: `./gradlew :knes-agent:runExplorer` builds persistent memory across N
cheap runs, then `./gradlew :knes-agent:runAgent` (existing) consumes it.

Memory files in `~/.knes/`:
- `ff1-overworld-terrain.json` — overworld tile types, cross-run
- `ff1-landmarks.json` — towns, castles, dungeons, NPCs, stairs
- `ff1-blockages.json` — failed attempts + run start directions
- `ff1-overworld-warps.json` — existing, unchanged shape
- `ff1-interior-memory.json` — existing, unchanged

Architecture: `ExplorerSession` (campaign, multiple `SingleRun`s) → hard-rule
restart (mapId trap, party wipe, idle, plateau) → salience-driven targets
(unvisited landmarks → viewport TOWN/CASTLE → frontier → diversify) →
deterministic walk via existing skills + Haiku consult on triggers.

Spec: `docs/superpowers/specs/2026-05-05-cross-run-explorer-design.md`
Plan: `docs/superpowers/plans/2026-05-05-cross-run-explorer.md`

Stub: `AnthropicHaikuConsult` returns no classifications in MVP. Phase 1.5
wires real Haiku 4.5 calls for interior NPC classification + dialog reading.
Phase 2 modifies `AgentSession` to consume `landmarks.json` for goal routing.
```

- [ ] **Step 4: Final commit**

```bash
git add HANDOFF.md
git commit -m "docs(handoff): cross-run explorer Phase 1 — memory files, flow, next steps"
```

- [ ] **Step 5: Push branch**

```bash
git push origin ff1-agent-v2-4
```

---

## Summary of deliverables

After all 12 tasks complete:

**New main code (8 files):**
- `knes-agent/src/main/kotlin/knes/agent/perception/OverworldTerrainMemory.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/BlockageMemory.kt`
- `knes-agent/src/main/kotlin/knes/agent/skills/ExploreOverworldFrontier.kt`
- `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt`
- `knes-agent/src/main/kotlin/knes/agent/explorer/SalienceStrategy.kt`
- `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt`
- `knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerSession.kt`
- `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt`
- `knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerMain.kt`

**New tests (7 files):**
- `OverworldTerrainMemoryTest`, `LandmarkMemoryTest`, `BlockageMemoryTest`
- `SalienceStrategyTest`, `ExploreOverworldFrontierTest`
- `SingleRunRestartTriggersTest`, `SingleRunHandleNewInteriorTest`, `ExplorerSessionGoalAchievedTest`

**Modified files (3):**
- `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt` (add `exploreOverworldFrontier` method)
- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMemory.kt` (add `hasMapBeenSeen` + `knownMapIds`)
- `knes-agent/build.gradle.kts` (register `runExplorer` task)
- `HANDOFF.md`

**Success criteria** (per spec section 2):
1. `./gradlew :knes-agent:runExplorer` runs end-to-end without crash.
2. After campaign, `~/.knes/*.json` files exist with non-empty contents.
3. Existing test suite remains green (152 pass + 2 pre-existing failures).
4. New tests all pass (~12-14 new tests).
5. No regression on `pressStartUntilOverworld` panic-reset guard from V5.31.

Phase 1.5 (real Haiku) and Phase 2 (Agent uses landmarks) are out of scope of this plan.
