# FF1 Koog Agent V2.4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the FF1 agent to escape any castle/town/dungeon interior by decoding the current interior map from FF1 ROM, classifying tiles, and pathfinding to the nearest exit (door / stairs / warp).

**Architecture:** Pure-function `InteriorMapLoader` parses the bank-4-to-7 RLE pointer-table format from ROM, producing an `InteriorMap` (byte grid + dimensions). `MapSession` caches the current map, swapping when `currentMapId` in RAM changes (sub-map transition). `InteriorPathfinder` is a Pathfinder implementation that BFS-searches for any tile classified as DOOR / STAIRS / WARP. `exitInterior` @Tool drives the press-buttons loop with idle-detection and stairs-A-tap.

**Tech Stack:** Kotlin 2.3.20 / Gradle 9.4.1 / Kotest 6.1.4 / Koog 0.6.1.

**Spec:** `docs/superpowers/specs/2026-05-02-ff1-koog-agent-v2-4-design.md`

**Branch:** `ff1-agent-v2-4` (already created on top of master `b213957`).

**Out of scope (not part of this plan):**
- Multi-map graph pathfinding (V2.5).
- Treasure chest / NPC interaction (V2.6+).
- Cross-run fog persistence (deferred from V2.3).

---

## File Structure

**New files:**
- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMap.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMapLoader.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/MapSession.kt`
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt`
- `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/CurrentMapIdResearchTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLoaderTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLiveTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/InteriorTileClassifierTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/MapSessionTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/pathfinding/InteriorPathfinderTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/runtime/ExitInteriorE2ETest.kt`

**Modified files:**
- `knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt` — add DOOR / STAIRS / WARP values.
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/SearchSpace.kt` — add LOCAL_MAP.
- `knes-agent/src/main/kotlin/knes/agent/perception/FfPhase.kt` — add `mapId` to Indoors.
- `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt` — read currentMapId; populate Indoors.mapId.
- `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt` — new tools `findPathToExit` / `exitInterior`.
- `knes-agent/src/main/kotlin/knes/agent/skills/ExitBuilding.kt` — alias delegating to ExitInterior.
- `knes-agent/src/main/kotlin/knes/agent/Main.kt` — construct InteriorMapLoader + MapSession.

---

## Task 1: Extend TileType with DOOR / STAIRS / WARP

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt`

- [ ] **Step 1: Read current file**

Read `knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt` to confirm the current 9-value enum.

- [ ] **Step 2: Add three new enum entries**

Replace the file contents with:
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
    DOOR('D'),
    STAIRS('>'),
    WARP('*'),
    UNKNOWN('?');

    /** Whether the party can walk onto this tile. UNKNOWN is conservatively impassable.
     *  DOOR / STAIRS / WARP are walkable destinations even though they trigger map transitions. */
    fun isPassable(): Boolean = when (this) {
        GRASS, FOREST, ROAD, BRIDGE, TOWN, CASTLE, DOOR, STAIRS, WARP -> true
        MOUNTAIN, WATER, UNKNOWN -> false
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run all existing tests to confirm no regression**

Run: `./gradlew :knes-agent:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/TileType.kt
git commit -m "feat(agent): V2.4 — TileType adds DOOR, STAIRS, WARP for interior maps"
```

---

## Task 2: Add LOCAL_MAP to SearchSpace

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/pathfinding/SearchSpace.kt`

- [ ] **Step 1: Replace file**

```kotlin
package knes.agent.pathfinding

/** Identifies which data the pathfinder searched. */
enum class SearchSpace { VIEWPORT, LOCAL_MAP, FOG, FULL_MAP }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/pathfinding/SearchSpace.kt
git commit -m "feat(agent): V2.4 — SearchSpace adds LOCAL_MAP for interior pathfinder"
```

---

## Task 3: Extend FfPhase.Indoors with mapId

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/FfPhase.kt`

- [ ] **Step 1: Replace file**

```kotlin
package knes.agent.perception

sealed interface FfPhase {
    object Boot : FfPhase { override fun toString() = "Boot" }
    object TitleOrMenu : FfPhase { override fun toString() = "TitleOrMenu" }
    object NewGameMenu : FfPhase { override fun toString() = "NewGameMenu" }
    object NameEntry : FfPhase { override fun toString() = "NameEntry" }
    data class Overworld(val x: Int, val y: Int) : FfPhase
    /** Inside a building / dungeon / town. `mapId` identifies the interior in ROM
     *  (-1 if RAM byte not yet identified). `localX` / `localY` are the party's
     *  position within that interior map. */
    data class Indoors(val mapId: Int, val localX: Int, val localY: Int) : FfPhase
    data class Battle(val enemyId: Int, val enemyHp: Int, val enemyDead: Boolean) : FfPhase
    object PostBattle : FfPhase { override fun toString() = "PostBattle" }
    object PartyDefeated : FfPhase { override fun toString() = "PartyDefeated" }
}
```

- [ ] **Step 2: Compile and observe call-site failures**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: FAIL — call sites construct `Indoors(localX, localY)` and need `mapId` first.

- [ ] **Step 3: Fix call sites in `RamObserver.kt`**

Read `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt`. Locate the line `return FfPhase.Indoors(localX = ..., localY = ...)`. Replace with:
```kotlin
return FfPhase.Indoors(
    mapId = ram["currentMapId"] ?: -1,
    localX = localX,
    localY = localY,
)
```

(`currentMapId` may not be in the RAM watch list yet — Task 4 adds it. For now `-1` is the documented "unknown" sentinel.)

- [ ] **Step 4: Search for any other Indoors construction**

Run: `grep -rn "FfPhase.Indoors\|FfPhase\\.Indoors\|Indoors(" knes-agent/src --include="*.kt"`
Expected: only `RamObserver.kt` and possibly tests. Update each constructor to include `mapId = -1` for now.

- [ ] **Step 5: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all tests**

Run: `./gradlew :knes-agent:test`
Expected: BUILD SUCCESSFUL (call-site updates may include test files).

- [ ] **Step 7: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/
git commit -m "feat(agent): V2.4 — FfPhase.Indoors carries mapId field (-1 sentinel for unknown)"
```

---

## Task 4: Locate `currentMapId` RAM byte (research)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/CurrentMapIdResearchTest.kt`

This task is a **diagnostic**. It boots the ROM, reaches three known interior states (Coneria castle, Coneria town, leaving Coneria onto overworld), dumps full RAM at each, and prints byte indices that change between states. The human (or you) inspects to find the byte that differs in a way consistent with "map identifier".

- [ ] **Step 1: Create the diagnostic test**

`knes-agent/src/test/kotlin/knes/agent/perception/CurrentMapIdResearchTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * Diagnostic — finds the RAM byte that holds `currentMapId`.
 *
 * Strategy: dump full RAM ($0000..$07FF) at three states:
 *   A) Coneria castle ground floor (party just spawned there)
 *   B) Walk south out of castle until on overworld
 *   C) Walk into Coneria town from overworld
 * The byte that holds different values at A, B, C is a strong candidate.
 *
 * Outputs a CSV-style report listing every byte index where (A, B) differ AND
 * (B, C) differ; prints the candidate set with values.
 */
class CurrentMapIdResearchTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("locate currentMapId byte by RAM diff").config(enabled = romPresent) {
        val outDir = File("build/research/current-map-id").also { it.mkdirs() }
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")

        // Reach overworld via canonical V2 boot drive.
        toolset.step(buttons = emptyList(), frames = 240)
        toolset.tap(button = "START", count = 2, pressFrames = 5, gapFrames = 30)
        repeat(60) {
            val ram = toolset.getState().ram
            if ((ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0) return@repeat
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
        }
        toolset.step(buttons = emptyList(), frames = 60)

        fun ramSnapshot(): IntArray {
            val arr = IntArray(0x800)
            for (i in 0 until 0x800) arr[i] = session.readMemory(i)
            return arr
        }

        // State A: party probably starts inside Coneria castle (V2 default boot).
        val stateA = ramSnapshot()
        File(outDir, "A-castle.txt").writeText(stateA.dumpAsHex())

        // State B: walk south until on overworld.
        repeat(30) {
            toolset.step(buttons = listOf("DOWN"), frames = 16)
            val ram = toolset.getState().ram
            if ((ram["locationType"] ?: 0) == 0 && (ram["localX"] ?: 0) == 0 &&
                (ram["localY"] ?: 0) == 0) return@repeat
        }
        val stateB = ramSnapshot()
        File(outDir, "B-overworld.txt").writeText(stateB.dumpAsHex())

        // State C: walk back north into Coneria town.
        repeat(20) {
            toolset.step(buttons = listOf("UP"), frames = 16)
            val ram = toolset.getState().ram
            if ((ram["localX"] ?: 0) != 0 || (ram["localY"] ?: 0) != 0) return@repeat
        }
        val stateC = ramSnapshot()
        File(outDir, "C-town.txt").writeText(stateC.dumpAsHex())

        // Find candidates: bytes that differ A→B AND B→C.
        val candidates = (0 until 0x800).filter { i ->
            stateA[i] != stateB[i] && stateB[i] != stateC[i]
        }
        val report = StringBuilder()
        report.appendLine("# Candidate currentMapId bytes (differ A→B AND B→C)")
        for (i in candidates) {
            report.appendLine(String.format(
                "  $%04X: A=0x%02X  B=0x%02X  C=0x%02X",
                i, stateA[i], stateB[i], stateC[i]
            ))
        }
        File(outDir, "candidates.txt").writeText(report.toString())
        println("Candidates written to ${outDir.absolutePath}/candidates.txt")
        println("Top candidates:")
        candidates.take(20).forEach {
            println(String.format("  $%04X: A=0x%02X B=0x%02X C=0x%02X",
                it, stateA[it], stateB[it], stateC[it]))
        }
    }
})

private fun IntArray.dumpAsHex(): String = buildString {
    for (i in indices) {
        if (i % 16 == 0) append(String.format("%04X: ", i))
        append(String.format("%02X ", this@dumpAsHex[i]))
        if (i % 16 == 15) append('\n')
    }
}
```

- [ ] **Step 2: Run the diagnostic**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.CurrentMapIdResearchTest`
Expected: PASS. Outputs in `knes-agent/build/research/current-map-id/`.

- [ ] **Step 3 [HUMAN/ANALYTICAL]: Pick the byte**

Read `knes-agent/build/research/current-map-id/candidates.txt`. The byte we want changes between distinct interior maps but stays 0 (or constant) on overworld. Likely candidates: `$0048` (per FF1 RAM map docs) or nearby. Spot-check against `https://datacrystal.tcrf.net/wiki/Final_Fantasy/RAM_map`.

- [ ] **Step 4: Add `currentMapId` to FF1 game profile**

Read `knes-debug/src/main/kotlin/knes/debug/profiles/ff1/Ff1Profile.kt` (or wherever the FF1 game profile lives). Find the existing watched-RAM map. Add an entry mapping `currentMapId` to the discovered RAM offset. Example for `$0048`:
```kotlin
"currentMapId" to 0x0048,
```

- [ ] **Step 5: Verify watched RAM exposes the new key**

Re-run the research test (Step 2). The `getState().ram` map now contains `currentMapId`. Verify by reading the test's printed values.

- [ ] **Step 6: Commit research test + profile update**

```bash
git add knes-agent/src/test/kotlin/knes/agent/perception/CurrentMapIdResearchTest.kt \
        knes-debug/src/main/kotlin/knes/debug/profiles/ff1/
git commit -m "research(agent): V2.4 — locate FF1 currentMapId RAM byte; add to ff1 profile"
```

---

## Task 5: InteriorMap data class + InteriorMapLoader (RLE decoder) + tests

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMap.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMapLoader.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLoaderTest.kt`

- [ ] **Step 1: Write failing tests**

`knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLoaderTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InteriorMapLoaderTest : FunSpec({
    test("decodes a simple RLE map (single 1-byte tiles)") {
        // ROM layout: 32 KB stub. Bank 4 starts at file offset 0x10010.
        val rom = ByteArray(0x10010 + 0x4000)
        // mapId 0 pointer at 0x10010: bank0(of bank4-7), CPU $8200.
        // raw = (bankIdx 0 << 14) | (0x200) = 0x0200
        rom[0x10010] = 0x00
        rom[0x10011] = 0x02
        // Map data starts at file offset 0x10010 + 0x200 = 0x10210.
        // Emit 4 tiles then 0xFF terminator.
        rom[0x10210] = 0x05
        rom[0x10211] = 0x06
        rom[0x10212] = 0x07
        rom[0x10213] = 0x08
        rom[0x10214] = 0xFF.toByte()

        val map = InteriorMapLoader(rom).load(mapId = 0)
        map.width shouldBe 64
        map.height shouldBe 64
        map.tileAt(0, 0) shouldBe 0x05
        map.tileAt(1, 0) shouldBe 0x06
        map.tileAt(2, 0) shouldBe 0x07
        map.tileAt(3, 0) shouldBe 0x08
        map.tileAt(4, 0) shouldBe 0x00   // tail-pad
    }

    test("decodes 2-byte RLE entry with repeat count") {
        val rom = ByteArray(0x10010 + 0x4000)
        rom[0x10010] = 0x00; rom[0x10011] = 0x02
        // 0x83 = 0x80 | 0x03 -> tile id 0x03; followed by count 0x05 = 5 tiles.
        rom[0x10210] = 0x83.toByte()
        rom[0x10211] = 0x05
        rom[0x10212] = 0xFF.toByte()

        val map = InteriorMapLoader(rom).load(0)
        for (i in 0 until 5) map.tileAt(i, 0) shouldBe 0x03
        map.tileAt(5, 0) shouldBe 0x00
    }

    test("2-byte entry wraps row boundary (62 grass + run-of-4 across rows)") {
        val rom = ByteArray(0x10010 + 0x4000)
        rom[0x10010] = 0x00; rom[0x10011] = 0x02
        // Emit 62 single-byte tiles of value 0x01 to fill cols 0..61 of row 0.
        var off = 0x10210
        for (i in 0 until 62) { rom[off++] = 0x01 }
        // Then a 2-byte RLE: tile 0x09, repeat 4 — fills cols 62, 63, then row 1 cols 0, 1.
        rom[off++] = 0x89.toByte()
        rom[off++] = 0x04
        rom[off++] = 0xFF.toByte()

        val map = InteriorMapLoader(rom).load(0)
        map.tileAt(61, 0) shouldBe 0x01
        map.tileAt(62, 0) shouldBe 0x09
        map.tileAt(63, 0) shouldBe 0x09
        map.tileAt(0, 1) shouldBe 0x09
        map.tileAt(1, 1) shouldBe 0x09
        map.tileAt(2, 1) shouldBe 0x00  // tail-pad
    }

    test("respects bank index in upper 2 bits of pointer") {
        val rom = ByteArray(0x10010 + 4 * 0x4000)
        // mapId 0: bank index 1 (= bank 5), CPU $8000.
        // raw = (1 << 14) | 0x0000 = 0x4000
        rom[0x10010] = 0x00; rom[0x10011] = 0x40
        // Bank 5 starts at file offset 0x14010. CPU $8000 -> file 0x14010.
        rom[0x14010] = 0x42
        rom[0x14011] = 0xFF.toByte()
        val map = InteriorMapLoader(rom).load(0)
        map.tileAt(0, 0) shouldBe 0x42
    }

    test("rejects out-of-range mapId") {
        val rom = ByteArray(0x10010 + 0x4000)
        try {
            InteriorMapLoader(rom).load(-1)
            error("expected throw")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.InteriorMapLoaderTest`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement InteriorMap**

`knes-agent/src/main/kotlin/knes/agent/perception/InteriorMap.kt`:
```kotlin
package knes.agent.perception

/**
 * Decoded interior map (town / castle floor / dungeon / shop).
 * 64x64 byte grid, indexed by (x, y) where (0, 0) is top-left.
 * Maps shorter than 64 rows have unused rows zero-filled.
 */
class InteriorMap(internal val tiles: ByteArray) : ViewportSource {
    init {
        require(tiles.size == WIDTH * HEIGHT) { "tiles must be ${WIDTH * HEIGHT}, got ${tiles.size}" }
    }

    val width: Int get() = WIDTH
    val height: Int get() = HEIGHT

    fun tileAt(x: Int, y: Int): Int {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return 0
        return tiles[y * WIDTH + x].toInt() and 0xFF
    }

    fun classifyAt(x: Int, y: Int): TileType =
        InteriorTileClassifier.classify(tileAt(x, y))

    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val size = ViewportMap.SIZE
        val partyLocal = size / 2 to size / 2
        val (px, py) = partyWorldXY
        val grid = Array(size) { ly ->
            Array(size) { lx ->
                val gx = px + (lx - partyLocal.first)
                val gy = py + (ly - partyLocal.second)
                if (gx in 0 until WIDTH && gy in 0 until HEIGHT) classifyAt(gx, gy)
                else TileType.UNKNOWN
            }
        }
        return ViewportMap(grid, partyLocal, partyWorldXY)
    }

    companion object {
        const val WIDTH = 64
        const val HEIGHT = 64
    }
}
```

- [ ] **Step 4: Implement InteriorMapLoader**

`knes-agent/src/main/kotlin/knes/agent/perception/InteriorMapLoader.kt`:
```kotlin
package knes.agent.perception

import java.io.File

/**
 * Decodes one FF1 interior map from ROM bytes. Format reference:
 * https://datacrystal.tcrf.net/wiki/Final_Fantasy/ROM_map
 *
 * Pointer table starts at file offset 0x10010 (= NES bank 4, mapped at $8000).
 * Each pointer is 16-bit LE. Upper 2 bits = bank index (0..3 -> bank 4..7).
 * Lower 14 bits = CPU address (offset from $8000).
 *
 * Map data: same RLE as overworld.
 *   0x00..0x7F  -> single tile of that id
 *   0x80..0xFE  -> tile id (byte - 0x80), next byte = run length (0 means 256)
 *   0xFF        -> end of map
 * Rows are 64 wide. 2-byte entries can wrap across rows. Tail rows zero-filled.
 */
class InteriorMapLoader(private val rom: ByteArray) {

    fun load(mapId: Int): InteriorMap {
        require(mapId in 0..127) { "mapId $mapId out of range" }
        val ptrFile = POINTER_TABLE_OFFSET + mapId * 2
        require(ptrFile + 1 < rom.size) { "ROM too small for mapId $mapId" }
        val raw = (rom[ptrFile].toInt() and 0xFF) or
                  ((rom[ptrFile + 1].toInt() and 0xFF) shl 8)
        val bankIndex = (raw ushr 14) and 0x03
        val bankBase = (4 + bankIndex) * 0x4000
        val dataStart = bankBase + 0x10 + (raw and 0x3FFF)
        require(dataStart in 0 until rom.size) {
            "mapId $mapId pointer 0x${raw.toString(16)} resolves to invalid offset 0x${dataStart.toString(16)}"
        }
        return InteriorMap(decodeRle(rom, dataStart))
    }

    companion object {
        const val POINTER_TABLE_OFFSET = 0x10010

        internal fun decodeRle(rom: ByteArray, start: Int): ByteArray {
            val grid = ByteArray(InteriorMap.WIDTH * InteriorMap.HEIGHT)
            var idx = 0
            var off = start
            while (idx < grid.size) {
                if (off >= rom.size) break
                val b = rom[off].toInt() and 0xFF
                off++
                when {
                    b == 0xFF -> return grid  // tail already zero-filled
                    b in 0x00..0x7F -> {
                        grid[idx++] = b.toByte()
                    }
                    else /* 0x80..0xFE */ -> {
                        if (off >= rom.size) break
                        val tile = (b - 0x80).toByte()
                        val rawCount = rom[off].toInt() and 0xFF
                        off++
                        val count = if (rawCount == 0) 256 else rawCount
                        repeat(count) {
                            if (idx >= grid.size) return grid
                            grid[idx++] = tile
                        }
                    }
                }
            }
            return grid
        }
    }
}

fun InteriorMapLoader.Companion.fromRomFile(file: File): InteriorMapLoader =
    InteriorMapLoader(file.readBytes())
```

(Note: the extension function `fromRomFile` is convenience for callers that already have a File.)

- [ ] **Step 5: Stub InteriorTileClassifier so InteriorMap compiles**

Task 7 fully implements this; for now we need a stub that always returns UNKNOWN so InteriorMap.classifyAt compiles. Create `knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt` with:
```kotlin
package knes.agent.perception

/** Stub — fully populated in Task 7. */
object InteriorTileClassifier {
    fun classify(tileId: Int): TileType = TileType.UNKNOWN
}
```

- [ ] **Step 6: Run tests to verify pass**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.InteriorMapLoaderTest`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/InteriorMap.kt \
        knes-agent/src/main/kotlin/knes/agent/perception/InteriorMapLoader.kt \
        knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLoaderTest.kt
git commit -m "feat(agent): V2.4 — InteriorMap + InteriorMapLoader (FF1 RLE bank4-7 decoder)"
```

---

## Task 6: Live decode — verify against real FF1 ROM

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLiveTest.kt`

- [ ] **Step 1: Create live test that decodes maps 0..63**

`knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLiveTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import java.io.File

class InteriorMapLiveTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("decodes interior maps 0..63 without crashing").config(enabled = romPresent) {
        val rom = File(romPath).readBytes()
        val loader = InteriorMapLoader(rom)
        var nonEmpty = 0
        for (id in 0..63) {
            val map = loader.load(id)
            // Sanity: at least one non-zero tile somewhere.
            val nonZero = (0 until InteriorMap.WIDTH * InteriorMap.HEIGHT)
                .any { map.tiles[it].toInt() != 0 }
            if (nonZero) nonEmpty++
        }
        // Most maps should be non-empty.
        check(nonEmpty >= 30) { "only $nonEmpty non-empty maps in 0..63 — decoder likely broken" }
    }

    test("dump map id 0 (likely Coneria castle ground floor)").config(enabled = romPresent) {
        val outDir = File("build/research/interior-maps").also { it.mkdirs() }
        val rom = File(romPath).readBytes()
        val loader = InteriorMapLoader(rom)
        val map = loader.load(0)
        val sb = StringBuilder()
        sb.append("=== InteriorMap id=0 (64x64 hex) ===\n")
        for (y in 0 until InteriorMap.HEIGHT) {
            for (x in 0 until InteriorMap.WIDTH) {
                sb.append("%02x ".format(map.tileAt(x, y)))
            }
            sb.append('\n')
        }
        File(outDir, "map-0.hex.txt").writeText(sb.toString())
    }
})
```

- [ ] **Step 2: Run live test**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.InteriorMapLiveTest`
Expected: PASS. `map-0.hex.txt` produced.

- [ ] **Step 3: Inspect map-0.hex.txt**

Read the dump. Visually identify wall / floor / door / stairs patterns. Walls usually form rectangular boundaries; floor inside; stairs / doors are isolated unique tile ids on borders.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/perception/InteriorMapLiveTest.kt
git commit -m "test(agent): V2.4 — InteriorMapLiveTest decodes all FF1 interior maps + dumps map 0"
```

---

## Task 7: InteriorTileClassifier (empirical, build from map-0 dump)

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/InteriorTileClassifierTest.kt`

Use the `map-0.hex.txt` dump from Task 6 and a screenshot of Coneria castle ground floor (capture by booting the ROM in any emulator, or use the FF1 maps wiki). Identify tile bytes for: floor, wall, door, stairs, water, treasure (if any), counter (in shops).

- [ ] **Step 1: Write failing tests**

`knes-agent/src/test/kotlin/knes/agent/perception/InteriorTileClassifierTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InteriorTileClassifierTest : FunSpec({
    test("known floor tile classifies as GRASS (passable)") {
        // Replace literal with empirically-found floor byte from map-0 dump.
        InteriorTileClassifier.classify(KNOWN_FLOOR_ID) shouldBe TileType.GRASS
    }

    test("known wall tile classifies as MOUNTAIN (impassable)") {
        InteriorTileClassifier.classify(KNOWN_WALL_ID) shouldBe TileType.MOUNTAIN
    }

    test("known door tile classifies as DOOR") {
        InteriorTileClassifier.classify(KNOWN_DOOR_ID) shouldBe TileType.DOOR
    }

    test("known stairs tile classifies as STAIRS") {
        InteriorTileClassifier.classify(KNOWN_STAIRS_ID) shouldBe TileType.STAIRS
    }

    test("unknown id maps to UNKNOWN") {
        InteriorTileClassifier.classify(0xFF) shouldBe TileType.UNKNOWN
    }
}) {
    companion object {
        // Filled in from map-0.hex.txt during Task 7 step 3.
        const val KNOWN_FLOOR_ID = 0x00
        const val KNOWN_WALL_ID = 0x00
        const val KNOWN_DOOR_ID = 0x00
        const val KNOWN_STAIRS_ID = 0x00
    }
}
```

- [ ] **Step 2 [HUMAN/ANALYTICAL]: Identify tile semantic ids from map-0 dump**

Open `knes-agent/build/research/interior-maps/map-0.hex.txt`. Compare to in-game screenshot of Coneria castle ground floor (FF1 maps wiki, e.g. `https://guides.gamercorner.net/ff/maps/`). For each visible terrain category, locate the byte value at that position in the hex grid:

- **Floor** (open passable area): a byte that fills the interior of rooms.
- **Wall** (rectangular boundaries): a byte that forms the outline of every room.
- **Door** (south exit): an isolated byte at the south edge of a room.
- **Stairs**: 1-2 bytes that appear as small clusters connecting room layers.

Replace the constants in the test file (Step 1) with the discovered ids.

- [ ] **Step 3: Implement InteriorTileClassifier**

Replace `knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt` with the populated lookup. Example structure (real values from Step 2):
```kotlin
package knes.agent.perception

/**
 * Classifies FF1 NES interior tile bytes (0x00..0x7F) to TileType.
 *
 * Built empirically from map-0 dump (Coneria castle ground floor) cross-referenced
 * with in-game screenshots. Values may need extension when tested against more
 * interiors (Marsh Cave, Earth Cave, towers).
 */
object InteriorTileClassifier {
    fun classify(tileId: Int): TileType = when (tileId and 0xFF) {
        // Floor / walkway
        in 0x00..0x03 -> TileType.GRASS

        // Wall / impassable structure
        in 0x10..0x1F -> TileType.MOUNTAIN

        // Door (south-edge exits to overworld)
        0x36, 0x37 -> TileType.DOOR

        // Stairs (sub-map transitions)
        0x40, 0x41 -> TileType.STAIRS

        // Warp tile
        0x42 -> TileType.WARP

        // Water / lava (impassable in interior)
        0x20, 0x21 -> TileType.WATER

        // Anything else: conservative UNKNOWN -> impassable.
        else -> TileType.UNKNOWN
    }
}
```

Replace placeholder ranges with empirical values from Step 2.

- [ ] **Step 4: Run tests**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.InteriorTileClassifierTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/InteriorTileClassifierTest.kt
git commit -m "feat(agent): V2.4 — InteriorTileClassifier with empirical FF1 tile mapping"
```

---

## Task 8: MapSession — caches current map + provides ViewportSource

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/MapSession.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/MapSessionTest.kt`

- [ ] **Step 1: Write failing tests**

`knes-agent/src/test/kotlin/knes/agent/perception/MapSessionTest.kt`:
```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MapSessionTest : FunSpec({
    // Build a synthetic ROM with two distinct maps.
    fun buildRom(): ByteArray {
        val rom = ByteArray(0x10010 + 0x4000 * 2)
        // map 0 -> (bankIdx 0, $8200): emit tile 0x05 once + 0xFF
        rom[0x10010] = 0x00; rom[0x10011] = 0x02
        rom[0x10210] = 0x05; rom[0x10211] = 0xFF.toByte()
        // map 1 -> (bankIdx 0, $8400): emit tile 0x09 once + 0xFF
        rom[0x10012] = 0x00; rom[0x10013] = 0x04
        rom[0x10410] = 0x09; rom[0x10411] = 0xFF.toByte()
        return rom
    }

    test("cache hit on same mapId returns same instance") {
        val ms = MapSession(InteriorMapLoader(buildRom()), FogOfWar())
        ms.ensureCurrent(0)
        val first = ms.currentMap
        ms.ensureCurrent(0)
        ms.currentMap shouldBe first
    }

    test("cache invalidation + fog clear on mapId change") {
        val fog = FogOfWar()
        val ms = MapSession(InteriorMapLoader(buildRom()), fog)
        ms.ensureCurrent(0)
        val first = ms.currentMap
        // Pre-populate fog with a fake blocked tile.
        fog.markBlocked(1, 1)
        fog.isBlocked(1, 1) shouldBe true
        ms.ensureCurrent(1)
        ms.currentMap shouldNotBe first
        fog.isBlocked(1, 1) shouldBe false  // cleared on transition
    }

    test("readViewport delegates to current map") {
        val ms = MapSession(InteriorMapLoader(buildRom()), FogOfWar())
        ms.ensureCurrent(0)
        val vp = ms.readViewport(8 to 8)
        vp.partyWorldXY shouldBe (8 to 8)
        // Center tile classifies via stub classifier (UNKNOWN by default).
    }

    test("readViewport before ensureCurrent throws") {
        val ms = MapSession(InteriorMapLoader(buildRom()), FogOfWar())
        try {
            ms.readViewport(0 to 0)
            error("expected throw")
        } catch (_: IllegalStateException) { /* ok */ }
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.MapSessionTest`
Expected: FAIL.

- [ ] **Step 3: Implement MapSession**

`knes-agent/src/main/kotlin/knes/agent/perception/MapSession.kt`:
```kotlin
package knes.agent.perception

/**
 * Caches the currently-loaded interior map. Reloads + clears fog on map transition.
 * Implements ViewportSource so it can plug into the same advisor / pathfinder
 * code paths as OverworldMap.
 */
class MapSession(
    private val loader: InteriorMapLoader,
    private val fog: FogOfWar,
) : ViewportSource {
    private var cachedId: Int = -1
    var currentMap: InteriorMap? = null
        private set

    fun ensureCurrent(mapId: Int) {
        if (mapId == cachedId && currentMap != null) return
        currentMap = loader.load(mapId)
        cachedId = mapId
        // New sub-map = different coord system, fog from previous map is meaningless.
        fog.clear()
    }

    val currentMapId: Int get() = cachedId

    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap {
        val map = currentMap ?: error("MapSession.readViewport called before ensureCurrent")
        return map.readViewport(partyWorldXY)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :knes-agent:test --tests knes.agent.perception.MapSessionTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/MapSession.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/MapSessionTest.kt
git commit -m "feat(agent): V2.4 — MapSession caches current interior map; clears fog on transition"
```

---

## Task 9: InteriorPathfinder (BFS to nearest exit) + tests

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/pathfinding/InteriorPathfinderTest.kt`

- [ ] **Step 1: Write failing tests**

`knes-agent/src/test/kotlin/knes/agent/pathfinding/InteriorPathfinderTest.kt`:
```kotlin
package knes.agent.pathfinding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap

private fun viewport(
    fill: TileType = TileType.GRASS,
    party: Pair<Int, Int> = 8 to 8,
    edits: (Array<Array<TileType>>) -> Unit = {},
): ViewportMap {
    val tiles = Array(16) { Array(16) { fill } }
    edits(tiles)
    return ViewportMap(tiles, partyLocalXY = party, partyWorldXY = party)
}

class InteriorPathfinderTest : FunSpec({
    val pf = InteriorPathfinder()

    test("direct path to single DOOR in viewport") {
        val vp = viewport { tiles -> tiles[12][8] = TileType.DOOR }
        val res = pf.findPath(8 to 8, /* unused */ 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.searchSpace shouldBe SearchSpace.LOCAL_MAP
        res.steps.size shouldBe 4
        res.steps.shouldNotBeEmpty()
    }

    test("prefers nearest exit when multiple exist") {
        val vp = viewport { tiles ->
            tiles[10][8] = TileType.STAIRS  // 2 north — nearer
            tiles[14][8] = TileType.DOOR    // 6 south
        }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.steps.size shouldBe 2
    }

    test("paths around walls to reach a DOOR") {
        val vp = viewport { tiles ->
            // Wall blocks direct south at (8, 9).
            tiles[9][8] = TileType.MOUNTAIN
            // DOOR at (8, 12) — must detour around wall.
            tiles[12][8] = TileType.DOOR
        }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        // Detour path is at least 5 steps (e.g. E, S, S, S, S, W).
        check(res.steps.size >= 5)
    }

    test("no exit visible in viewport returns not_found") {
        val vp = viewport()  // all GRASS, no DOOR/STAIRS/WARP
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe false
        res.steps shouldBe emptyList()
    }

    test("WARP tile counts as exit") {
        val vp = viewport { tiles -> tiles[6][8] = TileType.WARP }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.steps.size shouldBe 2
    }

    test("fog-blocked exit tile is skipped") {
        val vp = viewport { tiles ->
            tiles[10][8] = TileType.DOOR    // nearer
            tiles[14][8] = TileType.DOOR    // further
        }
        // Fog blocks the world coord that maps to local (8, 10): dx=0, dy=2 from
        // partyLocal (8, 8) and partyWorldXY (8, 8) -> world (8, 10).
        val fog = FogOfWar().apply { markBlocked(8, 10) }
        val res = pf.findPath(8 to 8, 0 to 0, vp, fog)
        res.found shouldBe true
        res.steps.size shouldBe 6  // forced to take the further DOOR
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-agent:test --tests knes.agent.pathfinding.InteriorPathfinderTest`
Expected: FAIL.

- [ ] **Step 3: Implement InteriorPathfinder**

`knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt`:
```kotlin
package knes.agent.pathfinding

import knes.agent.perception.FogOfWar
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.util.ArrayDeque

/**
 * BFS over the viewport from party position to the nearest tile classified as
 * DOOR / STAIRS / WARP. Goal is "any exit"; the `to` parameter of Pathfinder is ignored.
 */
class InteriorPathfinder(private val maxSteps: Int = 64) : Pathfinder {

    override fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        viewport: ViewportMap,
        fog: FogOfWar,
    ): PathResult {
        val start = viewport.worldToLocal(from.first, from.second)
            ?: return PathResult.blocked(from, "from outside viewport", SearchSpace.LOCAL_MAP)

        val w = viewport.width
        val h = viewport.height
        val visited = Array(h) { BooleanArray(w) }
        val viaDir = Array(h) { Array<Direction?>(w) { null } }
        val q = ArrayDeque<Pair<Int, Int>>()
        q.add(start)
        visited[start.second][start.first] = true

        while (q.isNotEmpty()) {
            val (cx, cy) = q.poll()
            val type = viewport.tiles[cy][cx]
            if ((cx != start.first || cy != start.second) && isExit(type)) {
                val steps = reconstruct(cx, cy, start, viaDir)
                val (rwx, rwy) = viewport.localToWorld(cx, cy)
                if (steps.size > maxSteps) {
                    val truncated = steps.take(maxSteps)
                    return PathResult(true, truncated, reachedAfter(truncated, from),
                        SearchSpace.LOCAL_MAP, partial = true,
                        reason = "exit found but path exceeds $maxSteps steps")
                }
                return PathResult(true, steps, rwx to rwy, SearchSpace.LOCAL_MAP, partial = false)
            }
            for (dir in Direction.values()) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                if (visited[ny][nx]) continue
                val tile = viewport.tiles[ny][nx]
                // Allow stepping onto exit tiles even if they are technically walkable;
                // skip impassable terrain.
                if (!tile.isPassable() && !isExit(tile)) continue
                val (wx, wy) = viewport.localToWorld(nx, ny)
                if (fog.isBlocked(wx, wy)) continue
                visited[ny][nx] = true
                viaDir[ny][nx] = dir
                q.add(nx to ny)
            }
        }

        return PathResult.blocked(from, "no exit (DOOR/STAIRS/WARP) visible in viewport",
            SearchSpace.LOCAL_MAP)
    }

    private fun isExit(t: TileType): Boolean =
        t == TileType.DOOR || t == TileType.STAIRS || t == TileType.WARP

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

- [ ] **Step 4: Run tests**

Run: `./gradlew :knes-agent:test --tests knes.agent.pathfinding.InteriorPathfinderTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt \
        knes-agent/src/test/kotlin/knes/agent/pathfinding/InteriorPathfinderTest.kt
git commit -m "feat(agent): V2.4 — InteriorPathfinder BFS to nearest exit (DOOR/STAIRS/WARP)"
```

---

## Task 10: ExitInterior skill + SkillRegistry tools + ExitBuilding alias

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/ExitBuilding.kt`

- [ ] **Step 1: Implement ExitInterior**

`knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt`:
```kotlin
package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.perception.TileType
import knes.agent.pathfinding.InteriorPathfinder
import knes.agent.pathfinding.Pathfinder
import knes.agent.tools.EmulatorToolset

/**
 * Walks toward the nearest exit (DOOR / STAIRS / WARP) of the current interior map
 * using a deterministic BFS. Adapts when the party transitions to a sub-map
 * (currentMapId changes) by reloading the map and re-pathfinding.
 *
 * Termination:
 *  - Success: phase becomes Overworld (locationType==0 AND localX==0 AND localY==0).
 *  - Encounter: returns ok=true with reason "encounter".
 *  - No path: returns ok=false with current (mapId, localX, localY).
 */
class ExitInterior(
    private val toolset: EmulatorToolset,
    private val mapSession: MapSession,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = InteriorPathfinder(),
) : Skill {
    override val id = "exit_interior"
    override val description =
        "Walk to the nearest exit of the current FF1 interior map (DOOR/STAIRS/WARP). " +
            "Stops on map transition, encounter, or arrival on overworld."

    private val FRAMES_PER_TILE = 16

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 64
        var totalFrames = 0
        var stepsTaken = 0

        while (stepsTaken < maxSteps) {
            val ram = toolset.getState().ram
            if ((ram["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ram)
            }
            val onOverworld = (ram["locationType"] ?: 0) == 0 &&
                (ram["localX"] ?: 0) == 0 && (ram["localY"] ?: 0) == 0
            if (onOverworld) {
                return SkillResult(true,
                    "reached overworld at (worldX=${ram["worldX"] ?: 0}, worldY=${ram["worldY"] ?: 0})",
                    totalFrames, ram)
            }
            val mapId = ram["currentMapId"] ?: -1
            if (mapId < 0) {
                return SkillResult(false, "currentMapId unknown — RAM byte not configured", totalFrames, ram)
            }
            mapSession.ensureCurrent(mapId)
            val lx = ram["localX"] ?: 0
            val ly = ram["localY"] ?: 0
            val viewport = mapSession.readViewport(lx to ly)
            fog.merge(viewport)
            val path = pathfinder.findPath(lx to ly, /* unused */ 0 to 0, viewport, fog)
            if (!path.found || path.steps.isEmpty()) {
                return SkillResult(false,
                    "no exit visible at mapId=$mapId, ($lx,$ly): ${path.reason ?: ""}",
                    totalFrames, ram)
            }
            val nextDir = path.steps.first()
            val r = toolset.step(buttons = listOf(nextDir.button), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++

            // Idle detection: if (mapId, lx, ly) unchanged, mark target tile blocked.
            val ram1 = toolset.getState().ram
            val mid1 = ram1["currentMapId"] ?: -1
            val lx1 = ram1["localX"] ?: 0
            val ly1 = ram1["localY"] ?: 0
            if (mid1 == mapId && lx1 == lx && ly1 == ly) {
                fog.markBlocked(lx + nextDir.dx, ly + nextDir.dy)
            }

            // If next tile in path is STAIRS or WARP and we just arrived on it, tap A
            // to activate the transition.
            if (mid1 == mapId && (lx1 != lx || ly1 != ly)) {
                val tileNow = mapSession.currentMap?.classifyAt(lx1, ly1)
                if (tileNow == TileType.STAIRS || tileNow == TileType.WARP) {
                    toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
                }
            }
        }

        val ram = toolset.getState().ram
        return SkillResult(false, "did not exit interior in $maxSteps steps", totalFrames, ram)
    }
}
```

- [ ] **Step 2: Replace ExitBuilding to delegate**

`knes-agent/src/main/kotlin/knes/agent/skills/ExitBuilding.kt`:
```kotlin
package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.tools.EmulatorToolset

/**
 * V2.4: thin alias to ExitInterior. Kept so existing callers / advisor prompts
 * referencing "exitBuilding" continue to work.
 */
class ExitBuilding(
    private val toolset: EmulatorToolset,
    private val mapSession: MapSession,
    private val fog: FogOfWar,
) : Skill {
    override val id = "exit_building"
    override val description = "Alias for exitInterior — walks to the nearest exit of the current interior."

    private val delegate = ExitInterior(toolset, mapSession, fog)

    override suspend fun invoke(args: Map<String, String>): SkillResult = delegate.invoke(args)
}
```

- [ ] **Step 3: Update SkillRegistry to add findPathToExit + exitInterior tools and pass MapSession**

Read `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt`. Modify constructor to accept `MapSession`, construct `ExitInterior` and `InteriorPathfinder`, and add the new tools. Replace contents:
```kotlin
package knes.agent.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.pathfinding.InteriorPathfinder
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
    private val overworldMap: OverworldMap,
    private val mapSession: MapSession,
    private val fog: FogOfWar,
    private val overworldPathfinder: Pathfinder = ViewportPathfinder(),
    private val interiorPathfinder: Pathfinder = InteriorPathfinder(),
) : ToolSet {

    private val pressStartSkill = PressStartUntilOverworld(toolset)
    private val walkSkill = WalkOverworldTo(toolset, overworldMap, fog, overworldPathfinder)
    private val exitInteriorSkill = ExitInterior(toolset, mapSession, fog, interiorPathfinder)

    @Tool
    @LLMDescription(
        "Advance from the FF1 title screen through NEW GAME / class select / name entry into " +
            "the overworld. Mashes START then A. Termination: char1_hpLow != 0 OR worldX != 0."
    )
    suspend fun pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult =
        pressStartSkill.invoke(mapOf("maxAttempts" to "$maxAttempts"))

    @Tool
    @LLMDescription(
        "Walk to the nearest exit of the current FF1 interior map (DOOR/STAIRS/WARP) using a " +
            "deterministic BFS. Stops when the party transitions to a sub-map, encounter starts, " +
            "or arrives on the overworld. Use when phase is Indoors."
    )
    suspend fun exitInterior(maxSteps: Int = 64): SkillResult =
        exitInteriorSkill.invoke(mapOf("maxSteps" to "$maxSteps"))

    @Tool
    @LLMDescription(
        "Find walkable path from current local position to the nearest interior exit " +
            "(DOOR/STAIRS/WARP) within the visible 16x16 viewport. Deterministic; no LLM tokens."
    )
    fun findPathToExit(): String {
        val ram = toolset.getState().ram
        val mapId = ram["currentMapId"] ?: -1
        if (mapId < 0) return "BLOCKED. currentMapId unknown."
        mapSession.ensureCurrent(mapId)
        val from = (ram["localX"] ?: 0) to (ram["localY"] ?: 0)
        val viewport = mapSession.readViewport(from)
        fog.merge(viewport)
        val res = interiorPathfinder.findPath(from, 0 to 0, viewport, fog)
        return when {
            res.found -> "PATH ${res.steps.size} steps to exit at (${res.reachedTile.first}," +
                "${res.reachedTile.second}): ${res.steps.joinToString(",") { it.name }}"
            else -> "BLOCKED. ${res.reason ?: "no exit visible"}."
        }
    }

    @Tool
    @LLMDescription(
        "Walk on the FF1 overworld toward (targetX, targetY) using deterministic BFS pathfinding."
    )
    suspend fun walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 32): SkillResult =
        walkSkill.invoke(mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps"))

    @Tool
    @LLMDescription(
        "Find walkable path from current party position to target world coordinates within " +
            "the visible 16x16 overworld viewport."
    )
    fun findPath(targetX: Int, targetY: Int): String {
        val ram = toolset.getState().ram
        val from = (ram["worldX"] ?: 0) to (ram["worldY"] ?: 0)
        val viewport = overworldMap.readViewport(from)
        fog.merge(viewport)
        val res = overworldPathfinder.findPath(from, targetX to targetY, viewport, fog)
        return when {
            res.found && !res.partial ->
                "PATH ${res.steps.size} steps: ${res.steps.joinToString(",") { it.name }}"
            res.found && res.partial ->
                "PARTIAL ${res.steps.size} steps to (${res.reachedTile.first},${res.reachedTile.second}); " +
                    "first steps: ${res.steps.take(8).joinToString(",") { it.name }}"
            else -> "BLOCKED. ${res.reason ?: "no path"}. Suggest askAdvisor."
        }
    }

    @Tool
    @LLMDescription("Run the registered FF1 battle_fight_all action.")
    suspend fun battleFightAll(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")

    @Tool
    @LLMDescription("Run the registered FF1 walk_until_encounter action.")
    suspend fun walkUntilEncounter(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "walk_until_encounter")

    @Tool
    @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    fun getState(): StateSnapshot = toolset.getState()
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: FAIL — Main.kt and ExecutorAgent.kt construct `SkillRegistry(toolset, overworldMap, fog)` without `mapSession`. Task 11 fixes them.

For now create a stub MapSession in those call sites so this task's commit compiles standalone. In `Main.kt`:
```kotlin
val mapSession = MapSession(InteriorMapLoader(File(rom).readBytes()), fog)
```
Insert after `val fog = FogOfWar()`.

In `ExecutorAgent.kt`: change the `SkillRegistry` constructor call to pass `mapSession` (which the executor receives from caller — see Task 11 for full wiring; for now add a constructor parameter `private val mapSession: MapSession`).

- [ ] **Step 5: Compile + run all tests**

Run: `./gradlew :knes-agent:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/ \
        knes-agent/src/main/kotlin/knes/agent/Main.kt \
        knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt
git commit -m "feat(agent): V2.4 — exitInterior @Tool + findPathToExit; ExitBuilding alias to ExitInterior"
```

---

## Task 11: Wire MapSession through AgentSession + advisor prompt

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/Main.kt`

The MapSession needs to be visible to RamObserver (so it can read interior viewport for advisor prompts), executor (already done in T10), and advisor (so its prompt also gets ASCII map of interior).

- [ ] **Step 1: Update AdvisorAgent to inject ASCII map for Indoors**

Read `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`. Locate `augmentForOverworld`. Extend to handle `FfPhase.Indoors`:
```kotlin
private fun augmentForOverworld(phase: FfPhase, observation: String): String {
    val src: ViewportSource? = when (phase) {
        is FfPhase.Overworld -> overworldSource
        is FfPhase.Indoors -> interiorSource
        else -> null
    }
    val partyXY = when (phase) {
        is FfPhase.Overworld -> phase.x to phase.y
        is FfPhase.Indoors -> phase.localX to phase.localY
        else -> null
    }
    if (src == null || partyXY == null) return observation
    val f = fog ?: return observation
    val viewport = src.readViewport(partyXY)
    f.merge(viewport)
    val mapBlock = AsciiMapRenderer.render(viewport, f)
    return buildString {
        append(observation)
        if (!observation.endsWith('\n')) append('\n')
        append('\n')
        append(mapBlock)
    }
}
```

Add constructor parameters: `interiorSource: ViewportSource? = null`. Rename existing `viewportSource` parameter to `overworldSource` for clarity. Update all callers in Main.kt.

For the Indoors case, MapSession.ensureCurrent must be called BEFORE readViewport. Either do it inside `augmentForOverworld` (call `(src as? MapSession)?.ensureCurrent(phase.mapId)`) or pre-call in the AgentSession loop. Add inside augmentForOverworld:
```kotlin
if (phase is FfPhase.Indoors && src is MapSession && phase.mapId >= 0) {
    src.ensureCurrent(phase.mapId)
}
```

- [ ] **Step 2: Update AgentSession to handle Indoors gracefully**

Read `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`. The existing loop already calls advisor on phase change; with V2.4 the Indoors phase carries mapId. No structural change needed.

- [ ] **Step 3: Update Main.kt to construct MapSession and thread it**

Replace the wiring block in Main.kt:
```kotlin
val router = ModelRouter()
val overworldMap = OverworldMap.fromRom(File(rom))
val fog = FogOfWar()
val mapSession = MapSession(InteriorMapLoader(File(rom).readBytes()), fog)
val observer = RamObserver(toolset, overworldMap)
val advisor = AdvisorAgent(
    anthropic, router, toolset,
    overworldSource = overworldMap,
    interiorSource = mapSession,
    fog = fog,
)
val executor = ExecutorAgent(anthropic, router, toolset, advisor, overworldMap, mapSession, fog)
```

- [ ] **Step 4: Update ExecutorAgent constructor to accept mapSession**

Read `knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt`. Add `mapSession: MapSession` as a constructor parameter; pass it to `SkillRegistry` construction.

- [ ] **Step 5: Compile + run all tests**

Run: `./gradlew :knes-agent:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/
git commit -m "feat(agent): V2.4 — MapSession threaded through AgentSession; advisor renders interior ASCII"
```

---

## Task 12: Update advisor system prompt for V2.4 indoor knowledge

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`

- [ ] **Step 1: Extend system prompt**

In `AdvisorAgent.systemPrompt`, find the `FF1 KNOWLEDGE` block. Add a paragraph about V2.4 interior pathfinding:
```
              - V2.4: when phase is Indoors you also receive an ASCII MAP showing
                the current interior. Glyphs include D=door, >=stairs, *=warp.
                Doors lead OUT of the interior to the overworld; stairs/warps move
                between sub-maps within the same interior. The executor has
                exitInterior and findPathToExit tools — both deterministic. Suggest
                exitInterior when in Indoors phase; the BFS will find the nearest
                door/stairs and drive the party there. Successive sub-map transitions
                eventually land back on the overworld.
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt
git commit -m "feat(agent): V2.4 — advisor system prompt mentions exitInterior + interior glyphs"
```

---

## Task 13: Live E2E exit test (ROM + API key gated)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/ExitInteriorE2ETest.kt`

- [ ] **Step 1: Create the live test**

`knes-agent/src/test/kotlin/knes/agent/runtime/ExitInteriorE2ETest.kt`:
```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.Main
import java.io.File

/**
 * Live E2E. Boots agent, runs ≤25 skill invocations with API key, asserts that
 * trace.jsonl shows Indoors -> Overworld transition (i.e. agent escaped the
 * starting interior).
 *
 * Skipped on CI (no key, no ROM).
 */
class ExitInteriorE2ETest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()
    val keyPresent = !System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()

    test("agent escapes Coneria interior to overworld within 25 skills")
        .config(enabled = romPresent && keyPresent) {
        val runDir = File.createTempFile("knes-v24-e2e-", "").apply { delete(); mkdirs() }.absolutePath
        Main.main(arrayOf(
            "--rom=$romPath",
            "--profile=ff1",
            "--max-skill-invocations=25",
            "--wall-clock-cap-seconds=540",
            "--run-dir=$runDir",
        ))

        val trace = File(runDir).walk()
            .firstOrNull { it.name == "trace.jsonl" }
            ?: error("trace.jsonl not produced")

        // Look for at least one event with phase starting "Indoors" AND at least one
        // later event with phase starting "Overworld".
        var sawIndoors = false
        var sawOverworldAfterIndoors = false
        trace.forEachLine { line ->
            if ("\"phase\":\"Indoors" in line) sawIndoors = true
            if (sawIndoors && "\"phase\":\"Overworld" in line) sawOverworldAfterIndoors = true
        }
        sawIndoors shouldBe true
        sawOverworldAfterIndoors shouldBe true
    }
})
```

- [ ] **Step 2: Run live test (requires API key + ROM)**

Run: `ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew :knes-agent:test --tests knes.agent.runtime.ExitInteriorE2ETest`
Expected: PASS — trace shows `Indoors -> Overworld` transition.

If FAIL: inspect trace, identify whether (a) tile classifier missed a key tile id (extend Task 7 mapping), (b) `currentMapId` byte is wrong (re-run Task 4 research), or (c) stairs activation path is wrong (debug the A-tap branch in ExitInterior).

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/runtime/ExitInteriorE2ETest.kt
git commit -m "test(agent): V2.4 — live E2E asserts Indoors -> Overworld transition"
```

---

## Task 14: Evidence run + PR

- [ ] **Step 1: Run agent live with run-dir**

```bash
mkdir -p docs/superpowers/runs/2026-05-02-v2-4-castle-exit
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY KNES_RUN_DIR=$(pwd)/docs/superpowers/runs/2026-05-02-v2-4-castle-exit \
  ./gradlew :knes-agent:run \
    --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes --profile=ff1 \
            --max-skill-invocations=40 --wall-clock-cap-seconds=720"
```

Expected: trace.jsonl shows successful exit + further overworld navigation.

- [ ] **Step 2: Write SUMMARY.md**

Create `docs/superpowers/runs/2026-05-02-v2-4-castle-exit/SUMMARY.md` with:
- Final phase
- Whether agent reached `Overworld(...)` from `Indoors`
- How many sub-map transitions
- Whether `Outcome.AtGarlandBattle` was reached (stretch goal)
- Comparison table to V2.3.1 (turns to escape, manhattan progress, encounters fought)

- [ ] **Step 3: Commit evidence**

```bash
git add docs/superpowers/runs/2026-05-02-v2-4-castle-exit/
git commit -m "evidence(agent): V2.4 castle exit run — Indoors -> Overworld transition"
```

- [ ] **Step 4: Push branch and open PR**

```bash
git push -u origin ff1-agent-v2-4
gh pr create --repo ArturSkowronski/kNES --base master --head ff1-agent-v2-4 \
  --title "V2.4 — interior map decoder + exit pathfinder" \
  --body "$(cat <<'EOF'
## Summary

V2.4 builds on PR #98 (V2.3.1) by giving the agent the ability to escape any FF1
castle / town / dungeon interior. New components:

- `InteriorMapLoader` — parses FF1 RLE pointer table at file offset 0x10010, banks 4-7.
- `InteriorMap` — 64x64 byte grid, ViewportSource impl.
- `InteriorTileClassifier` — empirical byte -> {GRASS, MOUNTAIN, DOOR, STAIRS, WARP, ...}.
- `MapSession` — caches current map; reloads on currentMapId change; clears fog on transition.
- `InteriorPathfinder` — BFS to nearest exit (any DOOR / STAIRS / WARP).
- `ExitInterior` skill (replaces ExitBuilding semantics) + `findPathToExit` @Tool.
- `FfPhase.Indoors` carries mapId.
- Advisor prompt augmented with ASCII map of interior.

Spec: docs/superpowers/specs/2026-05-02-ff1-koog-agent-v2-4-design.md
Plan: docs/superpowers/plans/2026-05-02-ff1-koog-agent-v2-4.md
Evidence: docs/superpowers/runs/2026-05-02-v2-4-castle-exit/

## Test plan

- [x] Unit: InteriorMapLoader (5), InteriorPathfinder (6), MapSession (4), InteriorTileClassifier (5)
- [x] Live: InteriorMapLiveTest (decodes 0..63 maps from real ROM), ExitInteriorE2ETest (Indoors -> Overworld)
- [x] Evidence run: agent escapes Coneria interior, transitions sub-maps, lands on overworld
EOF
)"
```

---

## Self-Review

**1. Spec coverage:**

| Spec section | Plan task |
|---|---|
| TileType extension (DOOR/STAIRS/WARP) | T1 |
| SearchSpace.LOCAL_MAP | T2 |
| FfPhase.Indoors mapId | T3 |
| RamObserver currentMapId read | T4 |
| InteriorMap data class | T5 |
| InteriorMapLoader RLE decoder | T5 |
| Live decoder verification | T6 |
| InteriorTileClassifier empirical | T7 |
| MapSession (cache + ViewportSource) | T8 |
| InteriorPathfinder BFS-to-exit | T9 |
| exitInterior tool + ExitBuilding alias | T10 |
| Advisor + AgentSession wiring | T11, T12 |
| Stairs/warp A-tap activation | T10 (in ExitInterior loop) |
| Live E2E test | T13 |
| Evidence run + PR | T14 |
| Risks (currentMapId byte, tile consistency) | T4, T7 (research-test gates) |

Coverage acceptable.

**2. Placeholder scan:**

- T7 Step 3 has placeholder `0x36, 0x37 -> TileType.DOOR` etc. with note "Replace placeholder ranges with empirical values from Step 2." This is intentional — the human-step (T7 Step 2) fills them. Same pattern as V2.3 T6. Keep.
- T7 Step 1 test constants `KNOWN_FLOOR_ID = 0x00` etc. are explicit-fill placeholders; T7 Step 2 says "Replace the constants in the test file (Step 1) with the discovered ids." Keep.
- No "TODO" / "TBD" / "implement later" elsewhere.

**3. Type consistency:**

- `MapSession` constructor `(InteriorMapLoader, FogOfWar)` consistent T8 / T10 / T11.
- `ExitInterior` constructor `(EmulatorToolset, MapSession, FogOfWar, Pathfinder?)` consistent T10 / T11.
- `SkillRegistry` constructor (ordered: toolset, overworldMap, mapSession, fog, ...) consistent T10 / T11.
- `FfPhase.Indoors(mapId, localX, localY)` field order consistent T3 / T11 / live tests.
- `InteriorPathfinder.findPath` `to` parameter unused; consistent with documented behavior across T9 / T10.

No drift.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-02-ff1-koog-agent-v2-4.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, isolation per context window.
2. **Inline Execution** — execute in this session via executing-plans, batched checkpoints.

Which approach?
