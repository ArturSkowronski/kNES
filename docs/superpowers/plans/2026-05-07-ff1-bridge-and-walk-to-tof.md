# FF1 Bridge → Walk to ToF — Implementation Plan (Spec 3a)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Spec 3a (`docs/superpowers/specs/2026-05-07-ff1-bridge-and-walk-to-tof-design.md`) — when the strategic advisor returns `BRIDGE`, the session deterministically walks the party from Coneria peninsula to a tile adjacent to the Temple of Fiends entrance, persisting the discovered entrance as a `TEMPLE_ENTRY` landmark for later sessions.

**Architecture:** Three new artifacts plus one new `HaikuConsult` method. `BridgeTick` is a per-iteration tick (mirrors Spec 1's `GrindLoop` integration shape) invoked from `AgentSession.run()` whenever `bridgePhaseActive && phase is Overworld`. Encounter handling is delegated to the existing main-loop PostBattle auto-dismiss path — no duplication. Persistence is via existing `landmarkMemory` only; no savestate-keyed state file (per project policy, see `feedback_no_savestate.md`).

**Tech Stack:** Kotlin 2.x, existing `HaikuConsult` interface (Gemini implementation `GeminiVisionConsult`, Anthropic stub `AnthropicHaikuConsult`), existing `WalkOverworldTo` skill, existing `LandmarkMemory`, existing `ViewportSource` / `ViewportMap`. Kotest 5.x FunSpec for tests.

---

## File Structure

**New files:**
- `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverChaosShrine.kt` — vision skill that asks Gemini to locate the chaos shrine sprite in the current overworld viewport, persists `TEMPLE_ENTRY` landmark on success.
- `knes-agent/src/main/kotlin/knes/agent/runtime/BridgeTick.kt` — per-iteration tick logic (sealed `TickOutcome` + state machine).
- `knes-agent/src/test/kotlin/knes/agent/skills/DiscoverChaosShrineTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/runtime/BridgeTickTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryFindTempleEntryTest.kt`

**Modified files:**
- `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkKind.kt` — add `TEMPLE_ENTRY` enum constant.
- `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt` — add `findTempleEntry()` helper.
- `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt` — add `OverworldClassification` sealed type + `classifyOverworldLandmark` method + extend `FakeHaikuConsult`.
- `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt` — stub `classifyOverworldLandmark` returning `NotFound`.
- `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt` — Gemini implementation of `classifyOverworldLandmark` (reuses `buildBody`/`postOrNull`/`parseEnvelope`).
- `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` — add `bridgePhaseActive`/`bridgeTick` fields, two ctor params (`bridgeVision`, `bridgeViewportSource`), flip logic in `runStrategicTick` BRIDGE branch, BridgeTick gate in main loop.

---

## Task 1: `LandmarkKind.TEMPLE_ENTRY`

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt:7-12`

- [ ] **Step 1: Add enum constant**

Open the file and edit the `LandmarkKind` enum:

```kotlin
enum class LandmarkKind {
    TOWN_ENTRY, CASTLE_ENTRY, DUNGEON_ENTRY, TEMPLE_ENTRY,
    NPC_KING, NPC_SHOPKEEPER, NPC_INNKEEPER, NPC_GENERIC,
    STAIRS_UP, STAIRS_DOWN, EXIT_TILE,
    UNKNOWN,
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt
git commit -m "feat(perception): add LandmarkKind.TEMPLE_ENTRY for ToF entrance"
```

---

## Task 2: `LandmarkMemory.findTempleEntry()` helper

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt:131`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryFindTempleEntryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryFindTempleEntryTest.kt`:

```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class LandmarkMemoryFindTempleEntryTest : FunSpec({
    test("returns null when no TEMPLE_ENTRY landmark exists") {
        val tmp = Files.createTempFile("lm-tof-empty-", ".json").toFile().apply { deleteOnExit() }
        val memory = LandmarkMemory(file = tmp)
        memory.findTempleEntry().shouldBeNull()
    }

    test("returns the TEMPLE_ENTRY landmark when present") {
        val tmp = Files.createTempFile("lm-tof-present-", ".json").toFile().apply { deleteOnExit() }
        val memory = LandmarkMemory(file = tmp)
        memory.record(Landmark(
            id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY,
            worldX = 211, worldY = 137,
            note = "chaos-shrine entrance",
        ))
        val found = memory.findTempleEntry()
        found?.kind shouldBe LandmarkKind.TEMPLE_ENTRY
        found?.worldX shouldBe 211
        found?.worldY shouldBe 137
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-agent:test --tests "knes.agent.perception.LandmarkMemoryFindTempleEntryTest"`
Expected: FAIL with `Unresolved reference: findTempleEntry`

- [ ] **Step 3: Add helper method**

Edit `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt`. Find the existing `findInnkeeper()` line (~131) and add immediately after:

```kotlin
    fun findInnkeeper(): Landmark? = byId.values.firstOrNull { it.kind == LandmarkKind.NPC_INNKEEPER }

    fun findTempleEntry(): Landmark? = byId.values.firstOrNull { it.kind == LandmarkKind.TEMPLE_ENTRY }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-agent:test --tests "knes.agent.perception.LandmarkMemoryFindTempleEntryTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryFindTempleEntryTest.kt
git commit -m "feat(perception): add LandmarkMemory.findTempleEntry() helper"
```

---

## Task 3: `HaikuConsult.classifyOverworldLandmark` interface + Fake + Anthropic stub

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt:62-64`

- [ ] **Step 1: Add new sealed type + interface method**

Edit `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt`. Add a new sealed result type alongside `ShopClassification`. Find the `ShopClassification` `data class` block and add immediately after:

```kotlin
    sealed interface OverworldClassification {
        /** screenX/Y are tile coordinates within the visible 16x16 viewport, where
         *  (0,0) is the top-left tile and (15,15) is the bottom-right. The caller
         *  converts to world coords via [knes.agent.perception.ViewportMap.localToWorld]. */
        data class Found(val screenX: Int, val screenY: Int, val costUsd: Double) : OverworldClassification
        data class NotFound(val costUsd: Double) : OverworldClassification
    }
```

Then add a new method on the `HaikuConsult` interface (after the existing `classifyShopMenu` method in the same file):

```kotlin
    /** Called when the agent needs to locate a known FF1 overworld landmark
     *  (e.g. the Chaos Shrine / Temple of Fiends) in the current viewport.
     *  `kind` is a free-form descriptor like "chaos_shrine" that the prompt
     *  template translates into pixel-art guidance. Returns `NotFound` on any
     *  failure (no exception leaks). */
    suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): OverworldClassification
```

- [ ] **Step 2: Extend `FakeHaikuConsult`**

In the same file, edit the existing `FakeHaikuConsult` class. Add a new constructor parameter and override:

```kotlin
class FakeHaikuConsult(
    private val interiorClassifications: List<HaikuConsult.InteriorClassification> = emptyList(),
    private val dialogReadings: List<HaikuConsult.DialogReading> = emptyList(),
    private val shopClassifications: List<HaikuConsult.ShopClassification> = emptyList(),
    private val overworldClassifications: List<HaikuConsult.OverworldClassification> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set
    var shopCalls: Int = 0; private set
    var overworldCalls: Int = 0; private set
```

And after the existing `classifyShopMenu` override in `FakeHaikuConsult`:

```kotlin
    override suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): HaikuConsult.OverworldClassification {
        val res = overworldClassifications.getOrNull(overworldCalls)
            ?: HaikuConsult.OverworldClassification.NotFound(0.0)
        overworldCalls++
        return res
    }
```

- [ ] **Step 3: Anthropic stub**

Edit `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt`. Find the existing `classifyShopMenu` stub method (~line 62-64) and add immediately after:

```kotlin
    /** Stub: overworld landmark classification is delegated to Gemini in this codebase. */
    override suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): HaikuConsult.OverworldClassification =
        HaikuConsult.OverworldClassification.NotFound(0.0)
```

- [ ] **Step 4: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL

(Tests for the FakeHaikuConsult behaviour are covered indirectly by `BridgeTickTest` in Task 6 — no separate test for the stub.)

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt
git commit -m "feat(vision): add HaikuConsult.classifyOverworldLandmark + Anthropic stub"
```

---

## Task 4: `GeminiVisionConsult.classifyOverworldLandmark` implementation + parser

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt`
- Test: extend `knes-agent/src/test/kotlin/knes/agent/explorer/GeminiVisionConsultTest.kt` if it exists, else add focused test in `BridgeTickTest`.

- [ ] **Step 1: Find existing GeminiVisionConsult shop pattern as reference**

Run: `grep -n "classifyShopMenu\|SYSTEM_SHOP\|SHOP_USER_TEXT\|parseShopResponse" knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt`

Note the line numbers of `SYSTEM_SHOP`, `SHOP_USER_TEXT`, and `parseShopResponse` — the new code mirrors them.

- [ ] **Step 2: Add interface override and parser to GeminiVisionConsult**

Open `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt`. Find the existing `classifyShopMenu` override (~line 60-64) and add immediately after:

```kotlin
    override suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): HaikuConsult.OverworldClassification {
        val body = buildBody(SYSTEM_OVERWORLD_LANDMARK, overworldUserText(kind), screenshotBase64,
            maxOutputTokens = 400)
        val raw = postOrNull(body) ?: return HaikuConsult.OverworldClassification.NotFound(0.0)
        return parseOverworldResponse(raw)
    }
```

- [ ] **Step 3: Add prompt strings + parser inside the companion object**

Find the companion object block in the same file (search for `companion object` near the parseShop method). Inside it, add:

```kotlin
        private const val SYSTEM_OVERWORLD_LANDMARK = """
You are a vision tool for the FF1 NES overworld. The user provides a screenshot
showing a 16x16 tile viewport centered on the party (party at tile (8,8) marked
by 4 sprite avatars). Your job is to locate a specific landmark sprite.

Respond with strict JSON ONLY (no commentary, no markdown fences). Schema:
  {"found": true, "screenX": <int 0..15>, "screenY": <int 0..15>}
or
  {"found": false}

Use tile coordinates (each visible tile is 16 NES pixels = one grid cell).
Top-left tile is (0,0); bottom-right tile is (15,15). If the landmark is
not visible in the viewport, return {"found": false}.
"""

        private fun overworldUserText(kind: String): String = when (kind) {
            "chaos_shrine" -> """
Locate the Chaos Shrine (Temple of Fiends) in the viewport.

Visual cues: a small dark/grey stone temple structure, distinct from town walls.
It has a single visible front entrance. It is NOT a castle (no flag/towers),
NOT a town (no surrounding wall ring), NOT a forest tile. The shrine sits on
grass terrain in the early-game continent north of Coneria.

Return the tile coordinates of the entrance (the bottom-center tile of the
shrine sprite — the tile the party will step onto to enter).
""".trimIndent()
            else -> "Locate the landmark of kind '$kind' in the viewport."
        }

        fun parseOverworldResponse(rawJson: String): HaikuConsult.OverworldClassification {
            val (innerText, costUsd) = parseEnvelope(rawJson)
            if (innerText == null) return HaikuConsult.OverworldClassification.NotFound(costUsd)
            return try {
                val match = JSON_OBJECT.find(innerText)
                    ?: return HaikuConsult.OverworldClassification.NotFound(costUsd)
                val obj = json.parseToJsonElement(match.value).jsonObject
                val found = obj["found"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) ?: false
                if (!found) return HaikuConsult.OverworldClassification.NotFound(costUsd)
                val sx = obj["screenX"]?.jsonPrimitive?.content?.toIntOrNull()
                val sy = obj["screenY"]?.jsonPrimitive?.content?.toIntOrNull()
                if (sx == null || sy == null || sx !in 0..15 || sy !in 0..15) {
                    HaikuConsult.OverworldClassification.NotFound(costUsd)
                } else {
                    HaikuConsult.OverworldClassification.Found(sx, sy, costUsd)
                }
            } catch (e: Exception) {
                System.err.println("[gemini-vision] parseOverworld failed: ${e.message}")
                HaikuConsult.OverworldClassification.NotFound(costUsd)
            }
        }
```

(If `JSON_OBJECT` and `json` are private in the companion, reuse the same names — they're already in scope.)

- [ ] **Step 4: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Add focused parser test**

Create `knes-agent/src/test/kotlin/knes/agent/explorer/GeminiOverworldParserTest.kt`:

```kotlin
package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class GeminiOverworldParserTest : FunSpec({
    val happyPathEnvelope = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\"found\": true, \"screenX\": 11, \"screenY\": 4}"}]}}],
          "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":0}
        }
    """.trimIndent()

    val notFoundEnvelope = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\"found\": false}"}]}}],
          "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"thoughtsTokenCount":0}
        }
    """.trimIndent()

    val malformedEnvelope = "not json at all"

    test("parses Found(11,4) from happy-path envelope") {
        val r = GeminiVisionConsult.parseOverworldResponse(happyPathEnvelope)
        r.shouldBeInstanceOf<HaikuConsult.OverworldClassification.Found>()
        r.screenX shouldBe 11
        r.screenY shouldBe 4
    }

    test("parses NotFound from explicit not-found envelope") {
        val r = GeminiVisionConsult.parseOverworldResponse(notFoundEnvelope)
        r.shouldBeInstanceOf<HaikuConsult.OverworldClassification.NotFound>()
    }

    test("returns NotFound on malformed envelope (no exception)") {
        val r = GeminiVisionConsult.parseOverworldResponse(malformedEnvelope)
        r.shouldBeInstanceOf<HaikuConsult.OverworldClassification.NotFound>()
    }
})
```

If `parseOverworldResponse` is not visible (private in companion), make it `internal` or expose it via the companion (mirror the existing `parseShopResponse` visibility — companion methods in `GeminiVisionConsult` are already accessible to tests in the same module).

- [ ] **Step 6: Run test**

Run: `./gradlew :knes-agent:test --tests "knes.agent.explorer.GeminiOverworldParserTest"`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt knes-agent/src/test/kotlin/knes/agent/explorer/GeminiOverworldParserTest.kt
git commit -m "feat(vision): Gemini classifyOverworldLandmark — chaos shrine prompt + parser"
```

---

## Task 5: `DiscoverChaosShrine` skill

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverChaosShrine.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/skills/DiscoverChaosShrineTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/skills/DiscoverChaosShrineTest.kt`:

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.api.EmulatorSession
import java.nio.file.Files

class DiscoverChaosShrineTest : FunSpec({
    test("Discovered: persists TEMPLE_ENTRY landmark with world coords") {
        val ramAt200_140 = mapOf("worldX" to 200, "worldY" to 140)
        val toolset = StubToolset(ramAt200_140, screenshotPng = byteArrayOf(0x42))
        val viewport = StubViewportSource(ViewportMap(
            tiles = Array(16) { Array(16) { TileType.UNKNOWN } },
            partyLocalXY = 8 to 8,
            partyWorldXY = 200 to 140,
        ))
        // Gemini reports shrine at screen tile (11, 4) — i.e. world (200+11-8, 140+4-8) = (203, 136)
        val vision = FakeHaikuConsult(overworldClassifications = listOf(
            HaikuConsult.OverworldClassification.Found(screenX = 11, screenY = 4, costUsd = 0.005),
        ))
        val tmp = Files.createTempFile("dcs-found-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)

        val skill = DiscoverChaosShrine(toolset, viewport, landmarks, vision)
        val r = skill.invoke(emptyMap())

        r.ok shouldBe true
        r.message.contains("Discovered") shouldBe true
        r.message.contains("(203,136)") shouldBe true
        landmarks.findTempleEntry()?.worldX shouldBe 203
        landmarks.findTempleEntry()?.worldY shouldBe 136
    }

    test("NotVisible: no landmark persisted, ok=true with NotVisible message") {
        val toolset = StubToolset(mapOf("worldX" to 150, "worldY" to 150), screenshotPng = byteArrayOf(0x01))
        val viewport = StubViewportSource(ViewportMap.ofUnknown(150 to 150))
        val vision = FakeHaikuConsult(overworldClassifications = listOf(
            HaikuConsult.OverworldClassification.NotFound(costUsd = 0.005),
        ))
        val tmp = Files.createTempFile("dcs-nv-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)

        val skill = DiscoverChaosShrine(toolset, viewport, landmarks, vision)
        val r = skill.invoke(emptyMap())

        r.ok shouldBe true
        r.message.contains("NotVisible") shouldBe true
        landmarks.findTempleEntry() shouldBe null
    }
})

private class StubToolset(
    private val ram: Map<String, Int>,
    private val screenshotPng: ByteArray,
) : EmulatorToolset(EmulatorSession()) {
    override fun getState(): StateSnapshot =
        StateSnapshot(frame = 0, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    override fun getScreen(): ByteArray = screenshotPng
}

private class StubViewportSource(private val vm: ViewportMap) : ViewportSource {
    override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap = vm
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-agent:test --tests "knes.agent.skills.DiscoverChaosShrineTest"`
Expected: FAIL with `Unresolved reference: DiscoverChaosShrine`

- [ ] **Step 3: Implement the skill**

Create `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverChaosShrine.kt`:

```kotlin
package knes.agent.skills

import knes.agent.explorer.HaikuConsult
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset
import java.util.Base64

/**
 * Vision skill: looks at the current overworld viewport and asks the configured
 * vision backend to locate the Chaos Shrine (Temple of Fiends) entrance tile.
 * On Found, persists a TEMPLE_ENTRY landmark at the derived world coordinates
 * and returns SkillResult(ok=true, "Discovered (wx,wy)"). On NotFound or any
 * failure path, returns SkillResult(ok=true, "NotVisible") or "ClassifyFailed:<msg>"
 * — never throws. The caller (BridgeTick) treats these uniformly.
 */
class DiscoverChaosShrine(
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource,
    private val landmarks: LandmarkMemory,
    private val vision: HaikuConsult,
) : Skill {
    override val id = "discover_chaos_shrine"
    override val description =
        "Locate the Chaos Shrine in the current overworld viewport via vision; " +
            "persists a TEMPLE_ENTRY landmark at the derived world coords."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val ram = toolset.getState().ram
        val wx = ram["worldX"] ?: return SkillResult(false, "worldX missing")
        val wy = ram["worldY"] ?: return SkillResult(false, "worldY missing")
        val viewport = viewportSource.readViewport(wx to wy)
        val screenshotB64 = try {
            Base64.getEncoder().encodeToString(toolset.getScreen())
        } catch (e: Exception) {
            return SkillResult(true, "ClassifyFailed: screenshot unavailable: ${e.message}")
        }
        val classification = try {
            vision.classifyOverworldLandmark(screenshotB64, kind = "chaos_shrine")
        } catch (e: Exception) {
            return SkillResult(true, "ClassifyFailed: ${e.message}")
        }
        return when (classification) {
            is HaikuConsult.OverworldClassification.NotFound ->
                SkillResult(true, "NotVisible (cost=\$${"%.4f".format(classification.costUsd)})")
            is HaikuConsult.OverworldClassification.Found -> {
                val (tx, ty) = viewport.localToWorld(classification.screenX, classification.screenY)
                landmarks.recordIfNew(Landmark(
                    id = "temple-of-fiends-entry",
                    kind = LandmarkKind.TEMPLE_ENTRY,
                    worldX = tx, worldY = ty,
                    note = "chaos-shrine entrance discovered via vision",
                ))
                landmarks.save()
                SkillResult(true, "Discovered ($tx,$ty) (cost=\$${"%.4f".format(classification.costUsd)})")
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-agent:test --tests "knes.agent.skills.DiscoverChaosShrineTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/DiscoverChaosShrine.kt knes-agent/src/test/kotlin/knes/agent/skills/DiscoverChaosShrineTest.kt
git commit -m "feat(skill): DiscoverChaosShrine — vision-classify ToF entrance, persist landmark"
```

---

## Task 6: `BridgeTick` state machine

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/BridgeTick.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/runtime/BridgeTickTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/runtime/BridgeTickTest.kt`:

```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import knes.agent.skills.Skill
import knes.agent.skills.SkillResult
import java.nio.file.Files
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind

class BridgeTickTest : FunSpec({

    fun emptyLandmarks() = LandmarkMemory(file =
        Files.createTempFile("bt-", ".json").toFile().apply { deleteOnExit() })

    test("when tofEntranceTile null and discover returns Discovered: cache tile, return Continue") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = "test"))
        val tick = BridgeTick(
            discover = { SkillResult(true, "Discovered (211,137)") },
            walk = { _, _ -> SkillResult(false, "should not be called") },
            landmarks = landmarks,
        )
        val r = tick.run(ram = mapOf("worldX" to 150, "worldY" to 150))
        r shouldBe BridgeTick.TickOutcome.Continue
        tick.tofEntranceTile shouldBe (211 to 137)
    }

    test("3 NotVisible discovers => BailToLlm and sticky") {
        val landmarks = emptyLandmarks()
        val tick = BridgeTick(
            discover = { SkillResult(true, "NotVisible") },
            walk = { _, _ -> SkillResult(false, "should not be called") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 150, "worldY" to 150)
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm   // sticky
    }

    test("ClassifyFailed counts toward discover cap (combined with NotVisible)") {
        val landmarks = emptyLandmarks()
        val responses = listOf("ClassifyFailed: oops", "NotVisible", "ClassifyFailed: again")
        var idx = 0
        val tick = BridgeTick(
            discover = { SkillResult(true, responses[idx++]) },
            walk = { _, _ -> SkillResult(false, "n/a") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 100, "worldY" to 100)
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm
    }

    test("adjacent (Manhattan <= 1) returns Reached") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val tick = BridgeTick(
            discover = { SkillResult(false, "should not be called") },
            walk = { _, _ -> SkillResult(false, "should not be called") },
            landmarks = landmarks,
        )
        // worldX=210, worldY=137: Manhattan = 1 → adjacent
        tick.run(mapOf("worldX" to 210, "worldY" to 137)) shouldBe BridgeTick.TickOutcome.Reached
    }

    test("walk returns 'encounter triggered' (WalkOverworldTo's encounter abort) does NOT increment stall") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val tick = BridgeTick(
            discover = { SkillResult(false, "n/a") },
            walk = { _, _ -> SkillResult(true, "encounter triggered after 3 steps") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 180, "worldY" to 137)
        repeat(10) { tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue }
        tick.bailed shouldBe false   // 10 encounters in a row never bail
    }

    test("walk failure 5x consecutive => BailToLlm") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val tick = BridgeTick(
            discover = { SkillResult(false, "n/a") },
            walk = { _, _ -> SkillResult(false, "did not reach (211,137) in 64 steps") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 180, "worldY" to 137)
        repeat(4) { tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue }
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm
    }

    test("successful 'reached' walk message resets walkStallCount") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val responses = ArrayDeque(listOf<SkillResult>(
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),
            SkillResult(true, "reached (200,137) in 8 steps"),  // resets stall
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),  // 4 in a row again — still not 5
        ))
        val tick = BridgeTick(
            discover = { SkillResult(false, "n/a") },
            walk = { _, _ -> responses.removeFirst() },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 180, "worldY" to 137)
        repeat(7) { tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue }
        tick.bailed shouldBe false
    }

    test("hydrates tofEntranceTile from landmarks at construction") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = "pre-existing"))
        val tick = BridgeTick(
            discover = { SkillResult(false, "should not be called") },
            walk = { _, _ -> SkillResult(true, "moved") },
            landmarks = landmarks,
        )
        tick.tofEntranceTile shouldBe (211 to 137)
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-agent:test --tests "knes.agent.runtime.BridgeTickTest"`
Expected: FAIL with `Unresolved reference: BridgeTick`

- [ ] **Step 3: Implement BridgeTick**

Create `knes-agent/src/main/kotlin/knes/agent/runtime/BridgeTick.kt`:

```kotlin
package knes.agent.runtime

import knes.agent.perception.LandmarkMemory
import knes.agent.skills.SkillResult
import kotlin.math.absoluteValue

/**
 * Per-iteration tick for the post-BRIDGE phase. Drives the party from the
 * current overworld position toward the Temple of Fiends entrance using
 * `WalkOverworldTo`, falling back to a vision discover step when the entrance
 * coordinate is not yet known.
 *
 * Encounter handling is delegated to AgentSession.run()'s main-loop PostBattle
 * auto-dismiss path. WalkOverworldTo returns `ok=true, "encounter triggered..."`
 * when a random encounter aborts the walk; this tick treats that as a non-stall
 * Continue and re-tries on the next Overworld tick after combat resolves.
 *
 * Bail caps: 3 discover attempts, 5 consecutive walk no-progress steps. Bails
 * are sticky for the session lifetime to prevent thrash if BRIDGE re-fires.
 */
class BridgeTick(
    private val discover: suspend () -> SkillResult,
    private val walk: suspend (targetX: Int, targetY: Int) -> SkillResult,
    private val landmarks: LandmarkMemory,
) {
    sealed interface TickOutcome {
        data object Continue : TickOutcome
        data object Reached : TickOutcome
        data object BailToLlm : TickOutcome
    }

    var tofEntranceTile: Pair<Int, Int>? =
        landmarks.findTempleEntry()?.let { (it.worldX ?: 0) to (it.worldY ?: 0) }
        private set

    var bailed: Boolean = false
        private set

    private var discoverAttempts: Int = 0
    private var walkStallCount: Int = 0

    private val DISCOVER_CAP = 3
    private val WALK_STALL_CAP = 5

    suspend fun run(ram: Map<String, Int>): TickOutcome {
        if (bailed) return TickOutcome.BailToLlm

        if (tofEntranceTile == null) {
            val r = discover()
            return when {
                r.message.startsWith("Discovered") -> {
                    // re-read landmark to get the persisted coords
                    val land = landmarks.findTempleEntry()
                    if (land?.worldX != null && land.worldY != null) {
                        tofEntranceTile = land.worldX to land.worldY
                    }
                    TickOutcome.Continue
                }
                else -> {
                    discoverAttempts++
                    if (discoverAttempts >= DISCOVER_CAP) {
                        bailed = true
                        TickOutcome.BailToLlm
                    } else TickOutcome.Continue
                }
            }
        }

        val (tx, ty) = tofEntranceTile!!
        val wx = ram["worldX"] ?: return TickOutcome.Continue
        val wy = ram["worldY"] ?: return TickOutcome.Continue
        val manhattan = (wx - tx).absoluteValue + (wy - ty).absoluteValue
        if (manhattan <= 1) return TickOutcome.Reached

        val r = walk(tx, ty)
        return when {
            r.ok && r.message.startsWith("encounter triggered") -> TickOutcome.Continue
            r.ok -> { walkStallCount = 0; TickOutcome.Continue }
            else -> {
                walkStallCount++
                if (walkStallCount >= WALK_STALL_CAP) {
                    bailed = true
                    TickOutcome.BailToLlm
                } else TickOutcome.Continue
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-agent:test --tests "knes.agent.runtime.BridgeTickTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/BridgeTick.kt knes-agent/src/test/kotlin/knes/agent/runtime/BridgeTickTest.kt
git commit -m "feat(runtime): BridgeTick — deterministic post-BRIDGE walk to ToF entrance"
```

---

## Task 7: Wire BridgeTick into AgentSession

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/runtime/AgentSessionBridgePhaseTest.kt`

- [ ] **Step 1: Write the failing test**

Create `knes-agent/src/test/kotlin/knes/agent/runtime/AgentSessionBridgePhaseTest.kt`:

```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.nio.file.Files

class AgentSessionBridgePhaseTest : FunSpec({
    test("BRIDGE decision flips bridgePhaseActive when bridge deps wired") {
        // Tested at the BridgeTick wiring layer — full session test would require
        // a running emulator. We verify the wiring by inspecting that BridgeTick
        // is constructed iff both bridgeVision and bridgeViewportSource are set.
        val sessionWith = bareSession(withBridgeDeps = true)
        sessionWith.bridgeTickIsConstructed shouldBe true

        val sessionWithout = bareSession(withBridgeDeps = false)
        sessionWithout.bridgeTickIsConstructed shouldBe false
    }
})

/** Minimal helper around AgentSession for the wiring assertion above.
 *  Uses package-private accessor `bridgeTickIsConstructed` added in Task 7. */
private fun bareSession(withBridgeDeps: Boolean): AgentSession {
    val session = EmulatorSession()
    val toolset = EmulatorToolset(session)
    val romBytes = ByteArray(0x40010)  // minimal stub; not exercised by this test
    val overworldMap = try { OverworldMap.fromRom(romBytes) } catch (_: Exception) { null }
        ?: error("OverworldMap.fromRom failed; provide a valid stub or skip this test")
    val fog = FogOfWar()
    val mapSession = MapSession(InteriorMapLoader(romBytes), fog)
    val observer = RamObserver(toolset, overworldMap)

    val anthropic = AnthropicSession("sk-ant-stub-key")
    val router = ModelRouter()
    val advisor = object : AdvisorAgent(anthropic, router, toolset) {
        override suspend fun consultStrategy(prompt: String): String = "GRIND"
        override suspend fun plan(phase: FfPhase, observation: String): String = "stub"
    }
    val executor = object : ExecutorAgent(
        anthropic, router, toolset, advisor, overworldMap, mapSession, fog, ToolCallLog()
    ) {
        override suspend fun run(phase: FfPhase, input: String): String = "stub"
    }

    val tmpLand = Files.createTempFile("ab-", ".json").toFile().apply { deleteOnExit() }
    val landmarks = LandmarkMemory(file = tmpLand)

    val viewport = object : ViewportSource {
        override fun readViewport(partyWorldXY: Pair<Int, Int>): ViewportMap =
            ViewportMap.ofUnknown(partyWorldXY)
    }
    val warpMem = OverworldWarpMemory(file =
        Files.createTempFile("ab-warp-", ".json").toFile().apply { deleteOnExit() })

    return AgentSession(
        toolset = toolset,
        observer = observer,
        executor = executor,
        advisor = advisor,
        toolCallLog = ToolCallLog(),
        budget = Budget(maxSkillInvocations = 1, maxAdvisorCalls = 1, costCapUsd = 0.0,
            wallClockCapSeconds = 1),
        fog = fog,
        warpMemory = warpMem,
        landmarkMemory = landmarks,
        bridgeVision = if (withBridgeDeps) FakeHaikuConsult() else null,
        bridgeViewportSource = if (withBridgeDeps) viewport else null,
        runDir = Files.createTempDirectory("ab-run-"),
    )
}
```

(If the OverworldMap.fromRom stub path proves brittle, drop this test in favor of unit-testing only via BridgeTickTest. The BridgePhase wiring is a 5-line conditional in AgentSession ctor; the integration risk is low.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-agent:test --tests "knes.agent.runtime.AgentSessionBridgePhaseTest"`
Expected: FAIL with `Unresolved reference: bridgeVision` (and similar for other new params)

- [ ] **Step 3: Add ctor params to AgentSession**

Edit `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`. Find the existing `outfitMapSession` ctor param block (~line 70) and add immediately after it:

```kotlin
    /**
     * Spec 3a: optional dependencies for the post-BRIDGE phase that walks to
     * the Temple of Fiends entrance. When both are non-null, BridgeTick is
     * constructed and BRIDGE decisions flip `bridgePhaseActive=true`.
     * When either is null, BRIDGE behaves as before (LLM executor takes over).
     */
    private val bridgeVision: HaikuConsult? = null,
    private val bridgeViewportSource: ViewportSource? = null,
```

- [ ] **Step 4: Add BridgeTick field + flag + accessor**

In the same file, find the existing `bridgeTile` private val (~line 122) and replace the surrounding strategy-mode-state block with:

```kotlin
    /** Strategy mode state — see spec §2 (one-way switch). */
    private var grindModeActive: Boolean = true
    /** Spec 3a: post-BRIDGE phase active flag. Mutually exclusive with grindModeActive. */
    private var bridgePhaseActive: Boolean = false
    private val recentDecisions: RecentDecisionsBuffer = RecentDecisionsBuffer()
```

(Replace existing `private var grindModeActive: Boolean = true` line with the 3-line block above, preserving any existing recentDecisions line.)

Then add the BridgeTick construction. After the existing `BRIDGE_TILE` and `TARGET_MIN_LEVEL` private vals, add:

```kotlin
    /** Spec 3a: BridgeTick — null if vision deps absent. */
    private val bridgeTick: BridgeTick? =
        if (bridgeVision != null && bridgeViewportSource != null && fog != null) {
            BridgeTick(
                discover = {
                    knes.agent.skills.DiscoverChaosShrine(
                        toolset, bridgeViewportSource, landmarkMemory, bridgeVision,
                    ).invoke(emptyMap())
                },
                walk = { tx, ty ->
                    knes.agent.skills.WalkOverworldTo(
                        toolset, bridgeViewportSource, fog,
                    ).invoke(mapOf("targetX" to tx.toString(), "targetY" to ty.toString()))
                },
                landmarks = landmarkMemory,
            )
        } else null

    /** Test accessor — reflects whether the BridgeTick was wired at session ctor. */
    internal val bridgeTickIsConstructed: Boolean get() = bridgeTick != null
```

- [ ] **Step 5: Flip bridgePhaseActive on BRIDGE decision**

In the same file, find the `runStrategicTick` BRIDGE branch (~line 150):

```kotlin
            StrategicDecision.BRIDGE -> { grindModeActive = false; null }
```

Replace with:

```kotlin
            StrategicDecision.BRIDGE -> {
                grindModeActive = false
                if (bridgeTick != null) bridgePhaseActive = true
                null
            }
```

- [ ] **Step 6: Add BridgeTick gate in main loop**

In the same file, find the existing strategic-tick block in `run()` (~line 307 — the `if (grindModeActive && phase is FfPhase.Overworld && strategicPlan == null)` block). Locate the closing `}` of the entire `when (invocation) { ... }` (around line 397, after `null -> { /* BRIDGE: ... */ }`). Immediately after that closing brace and before the `}` that closes the outer `if`, add the BridgeTick gate as a new sibling block:

Find this structure:
```kotlin
                if (grindModeActive && phase is FfPhase.Overworld && strategicPlan == null) {
                    val invocation = runStrategicTick(phase, ram)
                    when (invocation) {
                        SkillInvocation.Grind -> { ... }
                        SkillInvocation.Rest -> { ... }
                        null -> { /* BRIDGE: grindModeActive flipped, fall through */ }
                    }
                }

                // V5.36: strategic plan override...
```

Insert between the closing `}` of `if (grindModeActive ...)` and the comment `// V5.36`:

```kotlin
                if (bridgePhaseActive && phase is FfPhase.Overworld && strategicPlan == null) {
                    val r = bridgeTick!!.run(ram)
                    when (r) {
                        is BridgeTick.TickOutcome.Reached -> {
                            bridgePhaseActive = false
                            val wx = ram["worldX"] ?: -1
                            val wy = ram["worldY"] ?: -1
                            println("[bridge_phase] reached at ($wx,$wy)")
                            trace.record(TraceEvent(turn = 0, role = "system", phase = phase.toString(),
                                note = "bridge_phase_summary: reached at ($wx,$wy)"))
                            continue
                        }
                        is BridgeTick.TickOutcome.BailToLlm -> {
                            bridgePhaseActive = false
                            println("[bridge_phase] bailed_to_llm")
                            trace.record(TraceEvent(turn = 0, role = "system", phase = phase.toString(),
                                note = "bridge_phase_summary: bailed_to_llm"))
                            // fall through to LLM executor
                        }
                        is BridgeTick.TickOutcome.Continue -> continue
                    }
                }

```

- [ ] **Step 7: Run new test**

Run: `./gradlew :knes-agent:test --tests "knes.agent.runtime.AgentSessionBridgePhaseTest"`
Expected: PASS (1 test)

If the OverworldMap.fromRom stub crashes, simplify the test to just compile-check the new ctor params (delete the test body and replace with a single `AgentSession::class.constructors.first()` reflection assertion, or drop the test file entirely — see Step 1's parenthetical note).

- [ ] **Step 8: Run full test suite to check zero regressions**

Run: `./gradlew :knes-agent:test`
Expected: 287+ tests, 0 new failures (3 pre-existing baseline failures unchanged: Coneria8VisualDiffTest, ConeriaTownEmpiricalDiscoveryTest, ExploreOverworldFrontierTest).

- [ ] **Step 9: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt knes-agent/src/test/kotlin/knes/agent/runtime/AgentSessionBridgePhaseTest.kt
git commit -m "feat(session): wire BridgeTick — flip on BRIDGE, gate Overworld ticks, log summary"
```

---

## Task 8: Wire production callers (only if a session ctor caller exists outside tests)

**Files:**
- Search: `grep -rn "AgentSession(" knes-agent/src/main`

- [ ] **Step 1: Check if any production caller constructs AgentSession**

Run: `grep -rn "AgentSession(" knes-agent/src/main 2>/dev/null`

If the only callers are tests (likely — kNES wires the agent through CLI mains in `knes-agent/src/main/kotlin/knes/agent/main/` or similar), skip to Step 3.

- [ ] **Step 2: If a production caller exists**

Edit it to pass `bridgeVision = ...` and `bridgeViewportSource = ...` analogously to how `outfitVision`/`outfitViewportSource` are passed today. Reuse the same `HaikuConsult` instance (Gemini if `KNES_VISION=gemini-pro`, else `null`) and the existing `OverworldMap` as the viewport source.

Example (adjust paths per actual caller):

```kotlin
val agent = AgentSession(
    // ... existing params ...
    outfitVision = vision,
    outfitViewportSource = overworldMap,
    bridgeVision = vision,                 // new
    bridgeViewportSource = overworldMap,   // new
    // ...
)
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit (skip if Step 2 was unnecessary)**

```bash
git add <modified files>
git commit -m "chore(main): wire bridgeVision + bridgeViewportSource into agent main"
```

---

## Task 9: Self-review + final test run + update project status memory

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :knes-agent:test`
Expected: ~290 tests, 0 new failures (3 pre-existing baseline failures unchanged).

- [ ] **Step 2: Visually re-read the spec section 5 (error matrix)**

Open `docs/superpowers/specs/2026-05-07-ff1-bridge-and-walk-to-tof-design.md` and confirm all 9 rows of the error-handling table are exercised by `BridgeTickTest`. If any row is missing test coverage, add a focused test. Specifically verify:
- Vision deps unavailable → `bridgeTickIsConstructed == false` (Task 7 test).
- 3× NotVisible → BailToLlm (BridgeTickTest).
- Mixed Failed/NotVisible 3× → BailToLlm (BridgeTickTest).
- 5× walk NoMove → BailToLlm (BridgeTickTest).
- "encounter triggered" → not stall (BridgeTickTest).
- Adjacent → Reached (BridgeTickTest).
- Sticky bail → BridgeTickTest.

- [ ] **Step 3: Update `~/.claude/.../memory/project_status.md`**

Append a new section under "What this session built" describing Spec 3a artifacts (commits, test count, integration point). Update "Next session entry point" to reflect that Spec 3a is implemented and pending validation.

- [ ] **Step 4: Final commit if memory updated**

```bash
# Memory file lives outside the repo; just save it via the Write tool from the agent context.
```

(No git commit — memory is in `~/.claude/projects/.../memory/`, not the kNES repo.)

---

## Self-review

After writing all tasks:

1. **Spec coverage:**
   - §1 success criteria 1 (functional) — manual validation, not automatable.
   - §1 success criteria 2 (persistence) — Task 5 tests + Task 6 hydration test.
   - §1 success criteria 3 (no regressions) — Task 7 step 8.
   - §1 success criteria 4 (resilience/bail) — Task 6 BridgeTickTest.
   - §3.1 DiscoverChaosShrine — Task 5.
   - §3.2 BridgeTick — Task 6.
   - §3.3 classifyOverworldLandmark — Tasks 3, 4.
   - §3.4 LandmarkKind.TEMPLE_ENTRY — Task 1.
   - §3.5 LandmarkMemory.findTempleEntry — Task 2.
   - §3.6 AgentSession integration — Task 7.
   - All §5 error rows — Task 9 step 2 verification.

2. **Placeholder scan:** None. All steps contain code.

3. **Type consistency:**
   - `OverworldClassification.Found(screenX, screenY, costUsd)` — used identically in Tasks 3, 4, 5, 6.
   - `BridgeTick.TickOutcome.{Continue, Reached, BailToLlm}` — used identically in Tasks 6, 7.
   - `findTempleEntry(): Landmark?` — defined in Task 2, used in Tasks 5, 6.
   - `bridgeVision`, `bridgeViewportSource` — defined in Task 7, used in Tasks 7, 8.

4. **Test count target:** ~15 new unit tests. Task 2 (2) + Task 4 (3) + Task 5 (2) + Task 6 (8) + Task 7 (1) = 16. Matches spec target.
