# FF1 Interior Self-Discovery Landmark Scan — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace brittle `discoverWeaponShop` cold-start probe with a goal-aware compositional `InteriorExplorer` that uses two-pass vision (Haiku candidate scan + Gemini Pro verify) to auto-persist confirmed interior landmarks. MVP unblocks Spec 3a end-to-end validation in Coneria.

**Architecture:** New `InteriorExplorer` class composes existing `WalkInteriorVision` (unchanged) with new `InteriorScanner` (Pass 1 + Pass 2) and new `FrameChangeDetector` (PPU OAM delta + pixel-hash fallback). `OutfitBootPhase` calls `explorer.exploreUntilFound(NPC_SHOPKEEPER, predicate=kind=weapon, capSteps=50)` instead of the old probe loop.

**Tech Stack:** Kotlin (knes-agent module), kotlinx.serialization, JUnit 5 + kotlin.test, Anthropic SDK (Haiku 4.5), Google Gemini API (2.5 Pro), existing `HaikuConsult` interface pattern.

**Spec:** `docs/superpowers/specs/2026-05-08-ff1-interior-self-discovery-design.md` (commit `e6598c5`).

---

## File Structure

**New files:**
- `knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt` — composition: walk + scan + persist + goal check.
- `knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt` — Pass 1 + Pass 2 wrapping HaikuConsult, persistence.
- `knes-agent/src/main/kotlin/knes/agent/perception/FrameChangeDetector.kt` — OAM-primary trigger heuristic + pixel-hash fallback.
- `knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/skills/InteriorScannerTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/perception/FrameChangeDetectorTest.kt`
- `knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerLiveTest.kt` — gated `KNES_LIVE_VISION=true`.

**Modified files:**
- `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt` — add `CHEST`, `SIGN`, `DIALOGUE_TRIGGER` to `LandmarkKind`.
- `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt` — add `scanInteriorCandidates()` + `verifyLandmark()` to interface; extend `FakeHaikuConsult`.
- `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt` — Pass 1 implementation.
- `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt` — Pass 2 implementation.
- `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` — `runOutfitBootPhase` calls explorer; `discoverWeaponShop` retained for fallback compat (but bypassed when explorer succeeds).

---

## Task 0: Branch setup

**Files:** none (git only).

- [ ] **Step 1: Verify HEAD**

Run: `git log -1 --oneline`
Expected: `e6598c5 docs(spec): Spec 4 — FF1 interior self-discovery landmark scan`

- [ ] **Step 2: Cut implementation branch from spec commit**

```bash
git checkout -b ff1-interior-self-discovery e6598c5
```

- [ ] **Step 3: Verify clean working tree**

Run: `git status --short`
Expected: only pre-existing untracked files (`.claude/`, `start.png`, etc.); no modified tracked files.

---

## Task 1: Extend `LandmarkKind` enum

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt:7-12`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryTest.kt` (add JSON round-trip cases; create if file does not exist)

- [ ] **Step 1: Write failing test for new enum values + JSON round-trip**

Add to `LandmarkMemoryTest.kt`:

```kotlin
@Test
fun `new landmark kinds serialize and deserialize`() {
    val kinds = listOf(LandmarkKind.CHEST, LandmarkKind.SIGN, LandmarkKind.DIALOGUE_TRIGGER)
    val landmarks = kinds.mapIndexed { i, k ->
        Landmark(id = "lm_$i", kind = k, mapId = 8, localX = i, localY = i, note = "kind=$k")
    }
    val tmp = createTempFile().toFile().also { it.deleteOnExit() }
    val mem = LandmarkMemory(tmp)
    landmarks.forEach { mem.recordIfNew(it) }
    mem.save()
    val reloaded = LandmarkMemory(tmp)
    assertEquals(3, reloaded.findByKind(LandmarkKind.CHEST).size +
        reloaded.findByKind(LandmarkKind.SIGN).size +
        reloaded.findByKind(LandmarkKind.DIALOGUE_TRIGGER).size)
}
```

- [ ] **Step 2: Run test, expect FAIL (CHEST/SIGN/DIALOGUE_TRIGGER unknown)**

Run: `./gradlew :knes-agent:test --tests "*LandmarkMemoryTest.new landmark kinds*"`
Expected: compile error or test fail referencing missing enum values.

- [ ] **Step 3: Add the enum values**

Edit `LandmarkMemory.kt:7-12`:

```kotlin
enum class LandmarkKind {
    TOWN_ENTRY, CASTLE_ENTRY, DUNGEON_ENTRY, TEMPLE_ENTRY,
    NPC_KING, NPC_SHOPKEEPER, NPC_INNKEEPER, NPC_GENERIC,
    STAIRS_UP, STAIRS_DOWN, EXIT_TILE,
    CHEST, SIGN, DIALOGUE_TRIGGER,
    UNKNOWN,
}
```

- [ ] **Step 4: Run test, expect PASS**

Run: `./gradlew :knes-agent:test --tests "*LandmarkMemoryTest.new landmark kinds*"`
Expected: PASS.

- [ ] **Step 5: Run full module tests, expect 302 baseline still green**

Run: `./gradlew :knes-agent:test`
Expected: only the 3 known baseline failures; new tests pass; no regressions.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt knes-agent/src/test/kotlin/knes/agent/perception/LandmarkMemoryTest.kt
git commit -m "feat(perception): add CHEST, SIGN, DIALOGUE_TRIGGER LandmarkKinds"
```

---

## Task 2: Extend `HaikuConsult` interface with new methods + data types

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt:12-117`

- [ ] **Step 1: Add data types and method signatures to interface**

Insert into `interface HaikuConsult` body (after existing `OverworldClassification`):

```kotlin
data class CandidateLandmark(
    /** "shopkeeper" | "king" | "innkeeper" | "generic_npc" | "stairs_up" |
     *  "stairs_down" | "chest" | "sign" | "exit_tile" */
    val kind: String,
    val screenX: Int,    // 0..15
    val screenY: Int,    // 0..14
    val confidence: Double,
)

data class CandidatesScan(
    val candidates: List<CandidateLandmark>,
    val costUsd: Double,
)

sealed interface VerifyResult {
    /** refinedShopKind is null for non-shopkeeper kinds. */
    data class Confirmed(
        val refinedKind: String,
        val refinedShopKind: String?,
        val reason: String,
        val costUsd: Double,
    ) : VerifyResult
    data class Rejected(val reason: String, val costUsd: Double) : VerifyResult
    data class Errored(val reason: String, val costUsd: Double) : VerifyResult
}

/** Pass 1: enumerate visible interior landmarks. Returns empty list on any
 *  failure (no exception leaks). */
suspend fun scanInteriorCandidates(
    screenshotBase64: String?,
): CandidatesScan

/** Pass 2: verify a single candidate against a focused crop. Returns
 *  [VerifyResult.Errored] on any infrastructure failure (no exception leaks). */
suspend fun verifyLandmark(
    focusedScreenshotBase64: String?,
    candidateKind: String,
    candidateScreenX: Int,
    candidateScreenY: Int,
): VerifyResult
```

- [ ] **Step 2: Extend `FakeHaikuConsult` constructor + state**

Edit `FakeHaikuConsult` class (lines 74-117) — add to constructor and add scripting:

```kotlin
class FakeHaikuConsult(
    private val interiorClassifications: List<HaikuConsult.InteriorClassification> = emptyList(),
    private val dialogReadings: List<HaikuConsult.DialogReading> = emptyList(),
    private val shopClassifications: List<HaikuConsult.ShopClassification> = emptyList(),
    private val overworldClassifications: List<HaikuConsult.OverworldClassification> = emptyList(),
    private val candidatesScans: List<HaikuConsult.CandidatesScan> = emptyList(),
    private val verifyResults: List<HaikuConsult.VerifyResult> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set
    var shopCalls: Int = 0; private set
    var overworldCalls: Int = 0; private set
    var scanCalls: Int = 0; private set
    var verifyCalls: Int = 0; private set
    val verifyArgs: MutableList<Triple<String, Int, Int>> = mutableListOf()

    // ... existing overrides unchanged ...

    override suspend fun scanInteriorCandidates(
        screenshotBase64: String?,
    ): HaikuConsult.CandidatesScan {
        val res = candidatesScans.getOrNull(scanCalls)
            ?: HaikuConsult.CandidatesScan(emptyList(), 0.0)
        scanCalls++
        return res
    }

    override suspend fun verifyLandmark(
        focusedScreenshotBase64: String?,
        candidateKind: String,
        candidateScreenX: Int,
        candidateScreenY: Int,
    ): HaikuConsult.VerifyResult {
        verifyArgs.add(Triple(candidateKind, candidateScreenX, candidateScreenY))
        val res = verifyResults.getOrNull(verifyCalls)
            ?: HaikuConsult.VerifyResult.Errored("fake-not-scripted", 0.0)
        verifyCalls++
        return res
    }
}
```

- [ ] **Step 3: Add stub overrides to `AnthropicHaikuConsult` and `GeminiVisionConsult`**

For now both stubs return empty/errored results so the interface compiles. Real implementations land in Tasks 3 + 4.

In `AnthropicHaikuConsult.kt` add:

```kotlin
override suspend fun scanInteriorCandidates(
    screenshotBase64: String?,
): HaikuConsult.CandidatesScan {
    return HaikuConsult.CandidatesScan(emptyList(), 0.0)
}

override suspend fun verifyLandmark(
    focusedScreenshotBase64: String?,
    candidateKind: String,
    candidateScreenX: Int,
    candidateScreenY: Int,
): HaikuConsult.VerifyResult {
    return HaikuConsult.VerifyResult.Errored("anthropic-stub-not-implemented", 0.0)
}
```

In `GeminiVisionConsult.kt` add the same stubs (will be replaced in Task 4).

- [ ] **Step 4: Compile & run tests**

Run: `./gradlew :knes-agent:compileKotlin :knes-agent:compileTestKotlin :knes-agent:test`
Expected: compiles cleanly; existing tests still pass; 3 baseline failures unchanged.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt \
        knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt \
        knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt
git commit -m "feat(explorer): add scanInteriorCandidates + verifyLandmark to HaikuConsult"
```

---

## Task 3: Implement `AnthropicHaikuConsult.scanInteriorCandidates` (Pass 1)

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/explorer/AnthropicHaikuConsultPass1Test.kt`

**Spec reference:** §3.1 prompt verbatim.

- [ ] **Step 1: Write failing test for prompt + parser**

```kotlin
class AnthropicHaikuConsultPass1Test {
    @Test
    fun `parses well-formed candidates response`() {
        val raw = """
        {"candidates":[
          {"kind":"shopkeeper","screenX":5,"screenY":3,"confidence":0.85},
          {"kind":"stairs_down","screenX":12,"screenY":7,"confidence":0.9}
        ]}
        """.trimIndent()
        val parsed = AnthropicHaikuConsult.parsePass1(raw)
        assertEquals(2, parsed.size)
        assertEquals("shopkeeper", parsed[0].kind)
        assertEquals(5, parsed[0].screenX)
    }

    @Test
    fun `handles markdown-fenced JSON`() {
        val raw = "```json\n{\"candidates\":[{\"kind\":\"chest\",\"screenX\":2,\"screenY\":2,\"confidence\":0.6}]}\n```"
        assertEquals(1, AnthropicHaikuConsult.parsePass1(raw).size)
    }

    @Test
    fun `returns empty list on malformed JSON`() {
        assertEquals(emptyList<HaikuConsult.CandidateLandmark>(),
            AnthropicHaikuConsult.parsePass1("not json"))
    }

    @Test
    fun `returns empty list on null candidates`() {
        assertEquals(emptyList<HaikuConsult.CandidateLandmark>(),
            AnthropicHaikuConsult.parsePass1("""{"candidates":null}"""))
    }

    @Test
    fun `system prompt mentions all 9 landmark kinds`() {
        val expected = listOf("shopkeeper", "king", "innkeeper", "generic_npc",
            "stairs_up", "stairs_down", "chest", "sign", "exit_tile")
        expected.forEach { kind ->
            assertTrue(AnthropicHaikuConsult.SYSTEM_INTERIOR_SCAN.contains("\"$kind\""),
                "prompt must mention $kind")
        }
    }
}
```

- [ ] **Step 2: Run, expect FAIL (parser + prompt constant missing)**

Run: `./gradlew :knes-agent:test --tests "*AnthropicHaikuConsultPass1Test*"`

- [ ] **Step 3: Implement parser, prompt constant, and HTTP wiring**

In `AnthropicHaikuConsult.kt` add (paste the full prompt from spec §3.1 verbatim):

```kotlin
companion object {
    const val SYSTEM_INTERIOR_SCAN: String = """
You are reading a Final Fantasy 1 (NES) interior screenshot. The image is
256x240 px, a 16-tile-wide x 15-tile-tall viewport (each tile 16x16 px).
The party (4 sprites overlapping into one figure) renders at tile (8, 7).

Identify ALL visible non-party landmarks. Possible kinds:
- "shopkeeper"        — NPC behind a counter, often dressed distinctively
- "king"              — NPC on a throne / royal sprite
- "innkeeper"         — NPC near a bed/inn counter
- "generic_npc"       — generic townsperson/villager (dialogue trigger)
- "stairs_up"         — stair sprite leading up
- "stairs_down"       — stair sprite leading down
- "chest"             — treasure chest (open or closed)
- "sign"              — sign or tablet
- "exit_tile"         — door/staircase clearly leading outside

Output JSON only. Schema:
{"candidates":[{"kind":"<kind>","screenX":<int 0..15>,
                "screenY":<int 0..14>,"confidence":<float 0..1>}]}

If no landmarks visible: {"candidates":[]}.

Do NOT guess. confidence ≥ 0.7 only when you can see the sprite clearly.
Return ONLY JSON.
"""

    private val JSON_OBJECT_REGEX: Regex = Regex("""\{[\s\S]*\}""")

    /** Public for test access. */
    fun parsePass1(raw: String): List<HaikuConsult.CandidateLandmark> {
        return try {
            val match = JSON_OBJECT_REGEX.find(raw)?.value ?: return emptyList()
            val obj = Json.parseToJsonElement(match).jsonObject
            val arr = obj["candidates"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                HaikuConsult.CandidateLandmark(
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    screenX = o["screenX"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    screenY = o["screenY"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    confidence = o["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

override suspend fun scanInteriorCandidates(
    screenshotBase64: String?,
): HaikuConsult.CandidatesScan {
    if (screenshotBase64.isNullOrEmpty()) {
        return HaikuConsult.CandidatesScan(emptyList(), 0.0)
    }
    return try {
        // Follow same HTTP pattern as classifyInterior in this file.
        val response = client.send(
            system = SYSTEM_INTERIOR_SCAN,
            userText = "Identify visible landmarks.",
            imageBase64 = screenshotBase64,
            maxTokens = 1500,
        )
        val candidates = parsePass1(response.text)
        HaikuConsult.CandidatesScan(candidates, response.costUsd)
    } catch (e: Throwable) {
        HaikuConsult.CandidatesScan(emptyList(), 0.0)
    }
}
```

If `client.send(...)` does not exist in this exact form, mirror the existing `classifyInterior` HTTP body in the same file. Read existing method first; do not invent client API.

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :knes-agent:test --tests "*AnthropicHaikuConsultPass1Test*"`

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt \
        knes-agent/src/test/kotlin/knes/agent/explorer/AnthropicHaikuConsultPass1Test.kt
git commit -m "feat(explorer): Anthropic Haiku Pass 1 candidate scan implementation"
```

---

## Task 4: Implement `GeminiVisionConsult.verifyLandmark` (Pass 2)

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/explorer/GeminiVisionConsultPass2Test.kt`

**Spec reference:** §3.2 prompt verbatim including disambiguation rules.

- [ ] **Step 1: Write failing test**

```kotlin
class GeminiVisionConsultPass2Test {
    @Test
    fun `parses confirmed shopkeeper response with refined shop kind`() {
        val raw = """{"confirmed":true,"refinedKind":"shopkeeper","refinedShopKind":"weapon","reason":"counter shows weapons"}"""
        val r = GeminiVisionConsult.parsePass2(raw, costUsd = 0.005)
        assertTrue(r is HaikuConsult.VerifyResult.Confirmed)
        r as HaikuConsult.VerifyResult.Confirmed
        assertEquals("shopkeeper", r.refinedKind)
        assertEquals("weapon", r.refinedShopKind)
    }

    @Test
    fun `parses confirmed non-shopkeeper with null refinedShopKind`() {
        val raw = """{"confirmed":true,"refinedKind":"king","refinedShopKind":null,"reason":"on throne"}"""
        val r = GeminiVisionConsult.parsePass2(raw, costUsd = 0.005)
        assertTrue(r is HaikuConsult.VerifyResult.Confirmed)
        assertNull((r as HaikuConsult.VerifyResult.Confirmed).refinedShopKind)
    }

    @Test
    fun `parses rejected with reason`() {
        val raw = """{"confirmed":false,"reason":"no NPC at coords"}"""
        val r = GeminiVisionConsult.parsePass2(raw, costUsd = 0.005)
        assertTrue(r is HaikuConsult.VerifyResult.Rejected)
        assertEquals("no NPC at coords", (r as HaikuConsult.VerifyResult.Rejected).reason)
    }

    @Test
    fun `malformed JSON returns Rejected with malformed reason`() {
        val r = GeminiVisionConsult.parsePass2("not json", 0.0)
        assertTrue(r is HaikuConsult.VerifyResult.Rejected)
        assertTrue((r as HaikuConsult.VerifyResult.Rejected).reason.contains("malformed"))
    }

    @Test
    fun `system prompt forbids classifying shopkeeper as inn shop`() {
        assertTrue(GeminiVisionConsult.SYSTEM_VERIFY_LANDMARK.contains("\"innkeeper\" is its own kind"))
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew :knes-agent:test --tests "*GeminiVisionConsultPass2Test*"`

- [ ] **Step 3: Implement prompt + parser + HTTP**

Add to `GeminiVisionConsult.kt`:

```kotlin
companion object {
    // ... existing constants ...

    const val SYSTEM_VERIFY_LANDMARK: String = """
You are verifying a Final Fantasy 1 (NES) interior landmark candidate.
The image is a focused 32x32 pixel crop centered on tile coordinates the
candidate scanner reported. The candidate's claimed kind is provided in
the user message.

Two-step task:
(1) Confirm or reject that the candidate kind matches what you see.
(2) If confirmed AND the candidate kind is "shopkeeper", additionally
    classify the shop type from visual context (counter contents, NPC
    sprite palette): weapon|armor|whiteMagic|blackMagic|item|unknown.
    For non-shopkeeper kinds, refinedShopKind = null.

Output JSON only. Schema:
{"confirmed": true,
 "refinedKind":"<same as candidate kind>",
 "refinedShopKind":"<weapon|armor|whiteMagic|blackMagic|item|unknown>"
                    OR null for non-shopkeeper,
 "reason":"<short>"}
or
{"confirmed": false, "reason":"<short>"}

Confirmed examples by kind:
- shopkeeper: NPC behind a counter; refinedShopKind required.
  - weapon shop:  counter shows weapons (sword/axe/dagger/hammer/staff).
  - armor shop:   counter shows shields/helms/body armor.
  - whiteMagic:   CURE/HARM/FOG/RUSE scroll sprites.
  - blackMagic:   FIRE/LIT/SLEP/LOCK scroll sprites.
  - item shop:    potion/tent/cabin sprites.
  - unknown:      shopkeeper sprite clear but counter unclear.
- innkeeper: NPC near a bed/inn counter; refinedShopKind = null.
- king: NPC on throne; refinedShopKind = null.
- generic_npc / chest / sign / stairs_up / stairs_down / exit_tile:
  refinedShopKind = null.

Note: "innkeeper" is its own kind. Do NOT classify a shopkeeper as
"inn" — if you see a bed/inn context, the kind is "innkeeper", not
"shopkeeper" with shop type "inn".

Return ONLY JSON.
"""

    fun parsePass2(raw: String, costUsd: Double): HaikuConsult.VerifyResult {
        return try {
            val match = JSON_OBJECT.find(raw)?.value
                ?: return HaikuConsult.VerifyResult.Rejected("malformed: no JSON object", costUsd)
            val obj = Json.parseToJsonElement(match).jsonObject
            val confirmed = obj["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!confirmed) {
                val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "no reason"
                return HaikuConsult.VerifyResult.Rejected(reason, costUsd)
            }
            val refinedKind = obj["refinedKind"]?.jsonPrimitive?.contentOrNull
                ?: return HaikuConsult.VerifyResult.Rejected("malformed: missing refinedKind", costUsd)
            val refinedShopKind = obj["refinedShopKind"]?.jsonPrimitive?.contentOrNull
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            HaikuConsult.VerifyResult.Confirmed(refinedKind, refinedShopKind, reason, costUsd)
        } catch (e: Throwable) {
            HaikuConsult.VerifyResult.Rejected("malformed: ${e.message}", costUsd)
        }
    }
}

override suspend fun verifyLandmark(
    focusedScreenshotBase64: String?,
    candidateKind: String,
    candidateScreenX: Int,
    candidateScreenY: Int,
): HaikuConsult.VerifyResult {
    if (focusedScreenshotBase64.isNullOrEmpty()) {
        return HaikuConsult.VerifyResult.Errored("no-screenshot", 0.0)
    }
    return try {
        val resp = callGemini(
            system = SYSTEM_VERIFY_LANDMARK,
            userText = "Verify candidate kind=$candidateKind at tile ($candidateScreenX, $candidateScreenY).",
            imageBase64 = focusedScreenshotBase64,
            maxOutputTokens = 2000,  // Gemini 2.5 Pro thinking-mode mandatory; matches overworld classify
        )
        parsePass2(resp.text, resp.costUsd)
    } catch (e: Throwable) {
        HaikuConsult.VerifyResult.Errored("api-error: ${e.message}", 0.0)
    }
}
```

If `callGemini`/`JSON_OBJECT` differ in this file, follow the existing `classifyShopMenu` pattern (lines 60-180 per spec research). Do NOT invent API surface.

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :knes-agent:test --tests "*GeminiVisionConsultPass2Test*"`

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt \
        knes-agent/src/test/kotlin/knes/agent/explorer/GeminiVisionConsultPass2Test.kt
git commit -m "feat(explorer): Gemini Pro Pass 2 verifyLandmark implementation"
```

---

## Task 5: `FrameChangeDetector` — OAM primary, pixel-hash fallback

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/FrameChangeDetector.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/perception/FrameChangeDetectorTest.kt`

**Spec reference:** §2 architecture, §5 error handling row 4.

- [ ] **Step 1: Define data types and write failing tests**

```kotlin
// In FrameChangeDetectorTest.kt
class FrameChangeDetectorTest {

    private fun sprite(slot: Int, tileId: Int, x: Int, y: Int) =
        FrameChangeDetector.SpriteSlot(slot, tileId, x, y)

    @Test
    fun `first frame always triggers`() {
        val det = FrameChangeDetector()
        val sprites = setOf(sprite(0, 0xA0, 120, 112))
        assertTrue(det.shouldScan(currOam = sprites, currPixels = ByteArray(0)))
    }

    @Test
    fun `party-only motion does not trigger`() {
        val det = FrameChangeDetector()
        val a = setOf(sprite(0, 0xA0, 120, 112), sprite(1, 0xA1, 128, 112))
        val b = setOf(sprite(0, 0xA0, 124, 112), sprite(1, 0xA1, 132, 112))
        det.shouldScan(a, ByteArray(0))   // first frame, sets baseline
        assertFalse(det.shouldScan(b, ByteArray(0)),
            "moving party slots 0-3 only must not trigger")
    }

    @Test
    fun `new sprite slot triggers`() {
        val det = FrameChangeDetector()
        val a = setOf(sprite(0, 0xA0, 120, 112))
        val b = a + sprite(8, 0xC4, 80, 80)   // new NPC sprite
        det.shouldScan(a, ByteArray(0))
        assertTrue(det.shouldScan(b, ByteArray(0)))
    }

    @Test
    fun `oam-null falls back to pixel hash`() {
        val det = FrameChangeDetector()
        val pixA = ByteArray(256 * 240) { (it % 16).toByte() }
        val pixB = pixA.copyOf().also { it[5000] = 99 }   // change ~3 tiles' worth
        det.shouldScan(currOam = null, currPixels = pixA)   // baseline
        assertTrue(det.shouldScan(null, pixB), "pixel hash must trigger on diff")
    }

    @Test
    fun `oam-null sticks to pixel-hash for the rest of session`() {
        val det = FrameChangeDetector()
        det.shouldScan(currOam = null, currPixels = ByteArray(256 * 240))
        // Even if OAM becomes available later, must remain in fallback.
        assertEquals(FrameChangeDetector.Mode.PIXEL_HASH, det.mode)
        det.shouldScan(currOam = setOf(sprite(0, 0xA0, 0, 0)), currPixels = ByteArray(256 * 240))
        assertEquals(FrameChangeDetector.Mode.PIXEL_HASH, det.mode)
    }
}
```

- [ ] **Step 2: Run, expect FAIL (class missing)**

Run: `./gradlew :knes-agent:test --tests "*FrameChangeDetectorTest*"`

- [ ] **Step 3: Implement**

```kotlin
package knes.agent.perception

class FrameChangeDetector {
    data class SpriteSlot(val slot: Int, val tileId: Int, val pixelX: Int, val pixelY: Int)

    enum class Mode { OAM, PIXEL_HASH }

    private val partySlotIds: Set<Int> = setOf(0, 1, 2, 3)
    var mode: Mode = Mode.OAM
        private set

    private var prevOam: Set<SpriteSlot>? = null
    private var prevPixelHash: Long? = null
    private var initialized: Boolean = false

    /** Returns true if a vision scan should be triggered this iter. */
    fun shouldScan(currOam: Set<SpriteSlot>?, currPixels: ByteArray): Boolean {
        if (!initialized) {
            initialized = true
            if (currOam == null) mode = Mode.PIXEL_HASH
            prevOam = currOam
            prevPixelHash = pixelHash(currPixels)
            return true
        }
        if (currOam == null) {
            // Sticky fallback to pixel hash.
            mode = Mode.PIXEL_HASH
        }
        return when (mode) {
            Mode.OAM -> oamShouldTrigger(currOam!!)
            Mode.PIXEL_HASH -> pixelShouldTrigger(currPixels)
        }
    }

    private fun oamShouldTrigger(curr: Set<SpriteSlot>): Boolean {
        val prev = prevOam ?: emptySet()
        prevOam = curr
        // Compare slot occupancy ignoring party slots 0..3.
        val prevNonParty = prev.filter { it.slot !in partySlotIds }.map { it.slot }.toSet()
        val currNonParty = curr.filter { it.slot !in partySlotIds }.map { it.slot }.toSet()
        return currNonParty != prevNonParty
    }

    private fun pixelShouldTrigger(currPixels: ByteArray): Boolean {
        val curr = pixelHash(currPixels)
        val prev = prevPixelHash ?: 0L
        prevPixelHash = curr
        // Threshold: any difference in coarse hash triggers. Coarse-grained 16x15
        // grid avoids party-jiggle false positives via FNV folding tile averages.
        return curr != prev
    }

    private fun pixelHash(pixels: ByteArray): Long {
        if (pixels.isEmpty()) return 0L
        var h = 0xCBF29CE484222325L
        // 16x15 grid average of 16x16 tiles. NES 256x240 = 16x15 tiles.
        for (ty in 0 until 15) {
            for (tx in 0 until 16) {
                var sum = 0
                for (py in 0 until 16) {
                    for (px in 0 until 16) {
                        val idx = (ty * 16 + py) * 256 + (tx * 16 + px)
                        if (idx < pixels.size) sum += pixels[idx].toInt() and 0xFF
                    }
                }
                val avg = (sum shr 8).toByte()  // /256
                h = h xor avg.toLong()
                h *= 0x100000001B3L
            }
        }
        return h
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :knes-agent:test --tests "*FrameChangeDetectorTest*"`

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/FrameChangeDetector.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/FrameChangeDetectorTest.kt
git commit -m "feat(perception): FrameChangeDetector with OAM primary + pixel-hash fallback"
```

---

## Task 6: `InteriorScanner` — Pass 1 wrapping + filtering

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/skills/InteriorScannerTest.kt`

**Spec reference:** §3, §4 data flow steps "Pass 1" and "verify each candidate".

- [ ] **Step 1: Sketch types + write Pass 1 tests**

```kotlin
class InteriorScannerTest {

    private val noopMemory = LandmarkMemory(File.createTempFile("im", ".json").apply { deleteOnExit() })

    @Test
    fun `scanCandidates filters confidence below 0.5`() {
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(HaikuConsult.CandidatesScan(listOf(
                HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.8),
                HaikuConsult.CandidateLandmark("generic_npc", 7, 4, 0.4),  // filtered
            ), 0.002))
        )
        val scanner = InteriorScanner(haiku, noopMemory, runId = "r1")
        val out = runBlocking { scanner.scanCandidates("img-base64") }
        assertEquals(1, out.candidates.size)
        assertEquals("shopkeeper", out.candidates[0].kind)
    }

    @Test
    fun `scanCandidates with empty result returns empty list`() {
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(HaikuConsult.CandidatesScan(emptyList(), 0.001))
        )
        val scanner = InteriorScanner(haiku, noopMemory, runId = "r1")
        assertEquals(emptyList<HaikuConsult.CandidateLandmark>(),
            runBlocking { scanner.scanCandidates("img").candidates })
    }
}
```

- [ ] **Step 2: Implement scanner skeleton with `scanCandidates`**

```kotlin
package knes.agent.skills

import knes.agent.explorer.HaikuConsult
import knes.agent.perception.LandmarkMemory

class InteriorScanner(
    private val haiku: HaikuConsult,
    private val memory: LandmarkMemory,
    private val runId: String,
    private val confidenceThreshold: Double = 0.5,
) {
    data class ScanResult(
        val candidates: List<HaikuConsult.CandidateLandmark>,
        val costUsd: Double,
    )

    suspend fun scanCandidates(screenshotBase64: String?): ScanResult {
        val raw = haiku.scanInteriorCandidates(screenshotBase64)
        val filtered = raw.candidates.filter { it.confidence >= confidenceThreshold }
        return ScanResult(filtered, raw.costUsd)
    }
}
```

- [ ] **Step 3: Run, expect PASS**

Run: `./gradlew :knes-agent:test --tests "*InteriorScannerTest*"`

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/InteriorScannerTest.kt
git commit -m "feat(skills): InteriorScanner Pass 1 candidate filtering"
```

---

## Task 7: `InteriorScanner` — Pass 2 verify + persistence

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt`
- Modify: `knes-agent/src/test/kotlin/knes/agent/skills/InteriorScannerTest.kt`

**Spec reference:** §3.2, §4 data flow "verify each candidate" + persist.

- [ ] **Step 1: Add tests for verify + persist**

```kotlin
@Test
fun `verifyAndPersist confirmed shopkeeper writes Landmark with kind=weapon note`() {
    val tmp = File.createTempFile("im", ".json").apply { deleteOnExit() }
    val mem = LandmarkMemory(tmp)
    val haiku = FakeHaikuConsult(
        verifyResults = listOf(
            HaikuConsult.VerifyResult.Confirmed("shopkeeper", "weapon", "counter shows swords", 0.005)
        )
    )
    val scanner = InteriorScanner(haiku, mem, runId = "r1")
    val candidate = HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.8)
    val partyRamLocalX = 8
    val partyRamLocalY = 7
    val mapId = 8

    val result = runBlocking {
        scanner.verifyAndPersist(
            focusedScreenshotBase64 = "img",
            candidate = candidate,
            mapId = mapId,
            partyLocalX = partyRamLocalX,
            partyLocalY = partyRamLocalY,
        )
    }

    assertTrue(result is InteriorScanner.PersistResult.Confirmed)
    val persisted = mem.findByKind(LandmarkKind.NPC_SHOPKEEPER)
    assertEquals(1, persisted.size)
    assertTrue(persisted[0].note.contains("kind=weapon"))
    assertEquals(mapId, persisted[0].mapId)
}

@Test
fun `verifyAndPersist rejected does not persist`() {
    val tmp = File.createTempFile("im", ".json").apply { deleteOnExit() }
    val mem = LandmarkMemory(tmp)
    val haiku = FakeHaikuConsult(
        verifyResults = listOf(HaikuConsult.VerifyResult.Rejected("not a shopkeeper", 0.005))
    )
    val scanner = InteriorScanner(haiku, mem, runId = "r1")
    val candidate = HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.8)

    val result = runBlocking {
        scanner.verifyAndPersist("img", candidate, 8, 8, 7)
    }
    assertTrue(result is InteriorScanner.PersistResult.Rejected)
    assertEquals(0, mem.findByKind(LandmarkKind.NPC_SHOPKEEPER).size)
}

@Test
fun `verifyAndPersist with errored result returns Errored without persisting`() {
    val tmp = File.createTempFile("im", ".json").apply { deleteOnExit() }
    val mem = LandmarkMemory(tmp)
    val haiku = FakeHaikuConsult(
        verifyResults = listOf(HaikuConsult.VerifyResult.Errored("api-down", 0.0))
    )
    val scanner = InteriorScanner(haiku, mem, runId = "r1")
    val candidate = HaikuConsult.CandidateLandmark("chest", 2, 2, 0.9)
    val result = runBlocking {
        scanner.verifyAndPersist("img", candidate, 8, 8, 7)
    }
    assertTrue(result is InteriorScanner.PersistResult.Errored)
    assertEquals(0, mem.findByKind(LandmarkKind.CHEST).size)
}

@Test
fun `kind string maps to LandmarkKind enum`() {
    assertEquals(LandmarkKind.NPC_SHOPKEEPER, InteriorScanner.kindStringToEnum("shopkeeper"))
    assertEquals(LandmarkKind.NPC_KING, InteriorScanner.kindStringToEnum("king"))
    assertEquals(LandmarkKind.NPC_INNKEEPER, InteriorScanner.kindStringToEnum("innkeeper"))
    assertEquals(LandmarkKind.NPC_GENERIC, InteriorScanner.kindStringToEnum("generic_npc"))
    assertEquals(LandmarkKind.STAIRS_UP, InteriorScanner.kindStringToEnum("stairs_up"))
    assertEquals(LandmarkKind.STAIRS_DOWN, InteriorScanner.kindStringToEnum("stairs_down"))
    assertEquals(LandmarkKind.CHEST, InteriorScanner.kindStringToEnum("chest"))
    assertEquals(LandmarkKind.SIGN, InteriorScanner.kindStringToEnum("sign"))
    assertEquals(LandmarkKind.EXIT_TILE, InteriorScanner.kindStringToEnum("exit_tile"))
    assertEquals(LandmarkKind.UNKNOWN, InteriorScanner.kindStringToEnum("anything-else"))
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew :knes-agent:test --tests "*InteriorScannerTest*"`

- [ ] **Step 3: Extend `InteriorScanner` with verify + persist + kind mapping**

Append to `InteriorScanner.kt`:

```kotlin
sealed interface PersistResult {
    data class Confirmed(val landmark: Landmark, val costUsd: Double) : PersistResult
    data class Rejected(val reason: String, val costUsd: Double) : PersistResult
    data class Errored(val reason: String, val costUsd: Double) : PersistResult
}

suspend fun verifyAndPersist(
    focusedScreenshotBase64: String?,
    candidate: HaikuConsult.CandidateLandmark,
    mapId: Int,
    partyLocalX: Int,
    partyLocalY: Int,
): PersistResult {
    val verify = haiku.verifyLandmark(
        focusedScreenshotBase64,
        candidate.kind,
        candidate.screenX,
        candidate.screenY,
    )
    return when (verify) {
        is HaikuConsult.VerifyResult.Errored -> PersistResult.Errored(verify.reason, verify.costUsd)
        is HaikuConsult.VerifyResult.Rejected -> PersistResult.Rejected(verify.reason, verify.costUsd)
        is HaikuConsult.VerifyResult.Confirmed -> {
            val (lx, ly) = screenTileToLocal(
                candidate.screenX, candidate.screenY, partyLocalX, partyLocalY,
            ) ?: return PersistResult.Rejected("invalid-coords", verify.costUsd)
            val kind = kindStringToEnum(verify.refinedKind)
            val note = if (verify.refinedShopKind != null) {
                "kind=${verify.refinedShopKind}; verified=pass2; reason=${verify.reason}"
            } else {
                "verified=pass2; reason=${verify.reason}"
            }
            val landmark = Landmark(
                id = "interior_${kind.name.lowercase()}_${mapId}_${lx}_$ly",
                kind = kind,
                mapId = mapId,
                localX = lx,
                localY = ly,
                visited = false,
                note = note,
                discoveredRunId = runId,
            )
            memory.recordIfNew(landmark)
            memory.save()
            PersistResult.Confirmed(landmark, verify.costUsd)
        }
    }
}

private fun screenTileToLocal(
    screenX: Int, screenY: Int, partyLocalX: Int, partyLocalY: Int,
): Pair<Int, Int>? {
    if (screenX !in 0..15 || screenY !in 0..14) return null
    // Party renders at viewport tile (8, 7).
    val dx = screenX - 8
    val dy = screenY - 7
    return Pair(partyLocalX + dx, partyLocalY + dy)
}

companion object {
    fun kindStringToEnum(kindStr: String): LandmarkKind = when (kindStr) {
        "shopkeeper" -> LandmarkKind.NPC_SHOPKEEPER
        "king" -> LandmarkKind.NPC_KING
        "innkeeper" -> LandmarkKind.NPC_INNKEEPER
        "generic_npc" -> LandmarkKind.NPC_GENERIC
        "stairs_up" -> LandmarkKind.STAIRS_UP
        "stairs_down" -> LandmarkKind.STAIRS_DOWN
        "exit_tile" -> LandmarkKind.EXIT_TILE
        "chest" -> LandmarkKind.CHEST
        "sign" -> LandmarkKind.SIGN
        else -> LandmarkKind.UNKNOWN
    }
}
```

Note: `screenTileToLocal` here is a local interior-coordinate transform, not the overworld `ViewportMap.localToWorld`. Interior maps don't use world coords — they use `mapId + localX/Y`. The party's RAM-side `localX/Y` (read by caller from FF1 RAM) anchors the transform.

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :knes-agent:test --tests "*InteriorScannerTest*"`

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/InteriorScannerTest.kt
git commit -m "feat(skills): InteriorScanner verifyAndPersist + kind-string mapping"
```

---

## Task 8: `InteriorExplorer` — outcome types + skeleton

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt`
- Test: `knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerTest.kt`

**Spec reference:** §2 architecture, §4 data flow `exploreUntilFound`, §5 error handling.

- [ ] **Step 1: Define `ExploreOutcome` + write minimal "Found in iter 1" test**

```kotlin
class InteriorExplorerTest {

    @Test
    fun `Found returned when goal landmark exists in memory after first scan`() = runBlocking {
        val tmp = File.createTempFile("im", ".json").apply { deleteOnExit() }
        val memory = LandmarkMemory(tmp)
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(HaikuConsult.CandidatesScan(listOf(
                HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.9)
            ), 0.002)),
            verifyResults = listOf(
                HaikuConsult.VerifyResult.Confirmed("shopkeeper", "weapon", "ok", 0.005)
            ),
        )
        val walk = StubWalkInteriorVision(returnSequence = listOf(
            WalkOutcome.Stepped("east"),
        ))
        val frame = AlwaysScanFrameDetector()
        val emu = StubEmulatorState(mapId = 8, partyLocalX = 8, partyLocalY = 7)
        val explorer = InteriorExplorer(walk, InteriorScanner(haiku, memory, "r1"), frame, emu)

        val outcome = explorer.exploreUntilFound(
            goal = LandmarkKind.NPC_SHOPKEEPER,
            predicate = { it.note.contains("kind=weapon") },
            capSteps = 50,
        )

        assertTrue(outcome is InteriorExplorer.ExploreOutcome.Found)
        outcome as InteriorExplorer.ExploreOutcome.Found
        assertEquals(LandmarkKind.NPC_SHOPKEEPER, outcome.landmark.kind)
    }
}
```

The test uses three local stubs you will need to write: `StubWalkInteriorVision`, `AlwaysScanFrameDetector`, `StubEmulatorState`. Define them in the same file or a `_test_support.kt` file under `knes-agent/src/test/kotlin/knes/agent/runtime/`. Each stub minimally satisfies the explorer's interface dependency. `WalkOutcome` mirrors WalkInteriorVision's existing public outcome (or adapt naming).

- [ ] **Step 2: Define interface adapters needed by explorer**

Add to `InteriorExplorer.kt`:

```kotlin
package knes.agent.runtime

import knes.agent.perception.FrameChangeDetector
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.skills.InteriorScanner

interface InteriorEmulatorState {
    /** Returns null when OAM API is unavailable; explorer falls back. */
    fun captureOam(): Set<FrameChangeDetector.SpriteSlot>?
    fun capturePixels(): ByteArray   // raw 256x240 byte buffer; empty array if unavailable
    fun captureScreenshotBase64(): String?
    fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int): String?
    fun currentMapId(): Int
    fun partyLocalX(): Int
    fun partyLocalY(): Int
}

sealed interface WalkOutcome {
    data class Stepped(val direction: String) : WalkOutcome
    object Stuck : WalkOutcome
    object EncounterStarted : WalkOutcome
}

interface WalkInteriorVisionAdapter {
    suspend fun step(): WalkOutcome
}

class InteriorExplorer(
    private val walk: WalkInteriorVisionAdapter,
    private val scanner: InteriorScanner,
    private val frameDetector: FrameChangeDetector,
    private val emu: InteriorEmulatorState,
    private val memory: LandmarkMemory,
) {
    sealed interface ExploreOutcome {
        data class Found(val landmark: Landmark, val stats: ExploreStats) : ExploreOutcome
        data class NotFoundCapReached(val stats: ExploreStats) : ExploreOutcome
        data class StuckBailout(val reason: String, val stats: ExploreStats) : ExploreOutcome
        data class EncounterTriggered(val stats: ExploreStats) : ExploreOutcome
    }

    data class ExploreStats(
        val walkSteps: Int,
        val scansTriggered: Int,
        val candidatesEvaluated: Int,
        val confirmed: Int,
        val rejected: Int,
        val errored: Int,
        val costUsd: Double,
    )

    suspend fun exploreUntilFound(
        goal: LandmarkKind,
        predicate: (Landmark) -> Boolean,
        capSteps: Int,
    ): ExploreOutcome {
        // Skeleton implementation — Task 9 fills the loop.
        TODO("implemented in Task 9")
    }
}
```

- [ ] **Step 3: Test compiles but fails (`TODO`)**

Run: `./gradlew :knes-agent:test --tests "*InteriorExplorerTest*"`
Expected: test runs and fails with `NotImplementedError`. (Don't fix yet — Task 9 fills the loop.)

- [ ] **Step 4: Commit skeleton**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerTest.kt
git commit -m "feat(runtime): InteriorExplorer types + adapter interfaces (skeleton)"
```

---

## Task 9: `InteriorExplorer` — full step loop

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt`
- Modify: `knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerTest.kt` (add tests for §5 error matrix scenarios)

**Spec reference:** §4 data flow + §5 error matrix.

- [ ] **Step 1: Add 8 tests covering all explorer outcomes**

Add to `InteriorExplorerTest.kt` (the "Found in iter 1" test from Task 8 already covers scenario 1):

```kotlin
@Test
fun `Found in iter 5 after 3 rejected candidates`() { /* sequence 4 scans returning rejected, then confirmed */ }

@Test
fun `NotFoundCapReached when capSteps reached with zero confirmed`() { /* candidates exist but predicate never matches */ }

@Test
fun `StuckBailout after 3 consecutive walk STUCK`() { /* StubWalkInteriorVision returns Stuck thrice */ }

@Test
fun `single iter vision API error continues without bailing`() { /* haiku returns errored once, then succeeds */ }

@Test
fun `3 consecutive malformed Pass 1 returns StuckBailout pass1-degraded`() { /* candidatesScan returns empty 3x in row WHILE frame detector triggers; explorer must distinguish "scan triggered but Pass 1 yielded nothing 3x" from "Pass 1 errored 3x". For this MVP, treat 3 consecutive empty-after-trigger as the sticky-degraded signal. */ }

@Test
fun `goal predicate filters kind weapon over kind armor`() { /* persist armor first, then weapon; expect weapon returned */ }

@Test
fun `EncounterTriggered when walk reports encounter`() { /* walk returns EncounterStarted */ }
```

Each test follows the pattern from Task 8 with appropriate stub responses.

- [ ] **Step 2: Run, expect FAIL on all 8**

Run: `./gradlew :knes-agent:test --tests "*InteriorExplorerTest*"`

- [ ] **Step 3: Implement `exploreUntilFound` loop**

Replace the `TODO` in `InteriorExplorer.kt` with:

```kotlin
suspend fun exploreUntilFound(
    goal: LandmarkKind,
    predicate: (Landmark) -> Boolean,
    capSteps: Int,
): ExploreOutcome {
    var walkSteps = 0
    var scansTriggered = 0
    var candidatesEvaluated = 0
    var confirmedCount = 0
    var rejectedCount = 0
    var erroredCount = 0
    var costUsd = 0.0
    var consecutiveStuck = 0
    var consecutiveScanEmpty = 0

    fun stats() = ExploreStats(walkSteps, scansTriggered, candidatesEvaluated,
        confirmedCount, rejectedCount, erroredCount, costUsd)

    while (walkSteps < capSteps) {
        val pixels = emu.capturePixels()
        val oam = emu.captureOam()

        if (frameDetector.shouldScan(oam, pixels)) {
            scansTriggered++
            val screenshot = emu.captureScreenshotBase64()
            val scan = scanner.scanCandidates(screenshot)
            costUsd += scan.costUsd
            if (scan.candidates.isEmpty()) {
                consecutiveScanEmpty++
                if (consecutiveScanEmpty >= 3) {
                    return ExploreOutcome.StuckBailout("pass1-degraded", stats())
                }
            } else {
                consecutiveScanEmpty = 0
                for (cand in scan.candidates) {
                    candidatesEvaluated++
                    val focused = emu.captureFocusedCropBase64(cand.screenX, cand.screenY)
                    val pr = scanner.verifyAndPersist(
                        focused,
                        cand,
                        mapId = emu.currentMapId(),
                        partyLocalX = emu.partyLocalX(),
                        partyLocalY = emu.partyLocalY(),
                    )
                    when (pr) {
                        is InteriorScanner.PersistResult.Confirmed -> {
                            confirmedCount++
                            costUsd += pr.costUsd
                        }
                        is InteriorScanner.PersistResult.Rejected -> {
                            rejectedCount++
                            costUsd += pr.costUsd
                        }
                        is InteriorScanner.PersistResult.Errored -> {
                            erroredCount++
                            costUsd += pr.costUsd
                        }
                    }
                }
            }
        }

        // Goal check after scan — landmark may have been persisted this iter.
        val foundLandmark = memory.findByKind(goal).firstOrNull(predicate)
        if (foundLandmark != null) {
            return ExploreOutcome.Found(foundLandmark, stats())
        }

        // Walk step.
        val walkOutcome = walk.step()
        walkSteps++
        when (walkOutcome) {
            is WalkOutcome.Stepped -> consecutiveStuck = 0
            is WalkOutcome.Stuck -> {
                consecutiveStuck++
                if (consecutiveStuck >= 3) {
                    return ExploreOutcome.StuckBailout(
                        "walk-stuck-after-${walkSteps}-steps", stats())
                }
            }
            is WalkOutcome.EncounterStarted ->
                return ExploreOutcome.EncounterTriggered(stats())
        }
    }
    return ExploreOutcome.NotFoundCapReached(stats())
}
```

- [ ] **Step 4: Run tests, expect all 9 (including the one from Task 8) PASS**

Run: `./gradlew :knes-agent:test --tests "*InteriorExplorerTest*"`

- [ ] **Step 5: Run full module tests**

Run: `./gradlew :knes-agent:test`
Expected: 302 baseline + new tests green; 3 baseline failures unchanged.

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerTest.kt
git commit -m "feat(runtime): InteriorExplorer step loop with all error paths"
```

---

## Task 10: Real `WalkInteriorVisionAdapter` + `InteriorEmulatorState` from existing emulator

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt` (add real implementations alongside interfaces, OR put in companion factory)
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` to wire deps

**Concern (spec §10.2):** PPU OAM API may not be exposed by current emulator wrapper. Verify before implementing.

- [ ] **Step 1: Verify OAM availability**

Search the codebase for OAM access:

```bash
rg -n "OAM|oam|Object Attribute Memory" knes-agent/src/main/kotlin --no-heading
```

Check `mcp__knes__get_state` API surface (look at how `AgentSession` already calls `get_state`). Document one of:
1. **OAM is already exposed** → wrap in `RealInteriorEmulatorState.captureOam()`.
2. **OAM not exposed** → return `null` from `captureOam`. `FrameChangeDetector` will fall back to pixel-hash.

Add finding as a `// NOTE:` comment in `InteriorExplorer.kt` noting which path was taken.

- [ ] **Step 2: Implement `RealInteriorEmulatorState`**

In `InteriorExplorer.kt` (or a sibling file `InteriorEmulatorAdapter.kt`):

```kotlin
class RealInteriorEmulatorState(
    private val toolset: KnesToolset,   // existing — same API used by other skills
    private val ramReader: FF1RamReader, // existing — used by OutfitBootPhase already
) : InteriorEmulatorState {
    override fun captureOam(): Set<FrameChangeDetector.SpriteSlot>? {
        // If OAM not exposed by toolset.getState(), return null.
        // Otherwise read 64-slot OAM (4 bytes/slot: y, tileId, attr, x) and map.
        return try {
            val state = toolset.getState()
            val oamBytes = state.oam ?: return null   // adjust to actual API
            (0 until 64).map { i ->
                val base = i * 4
                FrameChangeDetector.SpriteSlot(
                    slot = i,
                    tileId = oamBytes[base + 1].toInt() and 0xFF,
                    pixelX = oamBytes[base + 3].toInt() and 0xFF,
                    pixelY = oamBytes[base].toInt() and 0xFF,
                )
            }.toSet()
        } catch (_: Throwable) {
            null
        }
    }

    override fun capturePixels(): ByteArray =
        try { toolset.getScreenshotRawBytes() ?: ByteArray(0) } catch (_: Throwable) { ByteArray(0) }

    override fun captureScreenshotBase64(): String? =
        try { toolset.getScreenshotBase64() } catch (_: Throwable) { null }

    override fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int): String? {
        // Crop 32x32 px window centered on (screenTileX*16+8, screenTileY*16+8).
        val raw = capturePixels()
        if (raw.isEmpty()) return null
        return cropToBase64Png(raw, centerPixelX = screenTileX * 16 + 8, centerPixelY = screenTileY * 16 + 8, sizePx = 32)
    }

    override fun currentMapId(): Int = ramReader.currentMapId()
    override fun partyLocalX(): Int = ramReader.partyLocalX()
    override fun partyLocalY(): Int = ramReader.partyLocalY()
}
```

`cropToBase64Png` is a small new utility — implement using Java AWT BufferedImage if no existing helper:

```kotlin
private fun cropToBase64Png(raw: ByteArray, centerPixelX: Int, centerPixelY: Int, sizePx: Int): String? {
    return try {
        val img = ImageIO.read(ByteArrayInputStream(raw)) ?: return null
        val x = (centerPixelX - sizePx / 2).coerceIn(0, img.width - sizePx)
        val y = (centerPixelY - sizePx / 2).coerceIn(0, img.height - sizePx)
        val sub = img.getSubimage(x, y, sizePx, sizePx)
        val out = ByteArrayOutputStream()
        ImageIO.write(sub, "png", out)
        java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    } catch (_: Throwable) {
        null
    }
}
```

- [ ] **Step 3: Implement `RealWalkInteriorVisionAdapter`**

```kotlin
class RealWalkInteriorVisionAdapter(
    private val walk: WalkInteriorVision,   // existing skill, unchanged
) : WalkInteriorVisionAdapter {
    override suspend fun step(): WalkOutcome {
        // Map WalkInteriorVision's existing return type to WalkOutcome.
        return when (val r = walk.stepOnce()) {   // adjust method name to match
            // Check existing WalkInteriorVision public API; map appropriately.
            else -> TODO("map existing WalkInteriorVision outcome to WalkOutcome")
        }
    }
}
```

Read `WalkInteriorVision.kt` (skill, lines 24-169) to identify the actual public method and outcome type, then complete the mapping. **Do not modify `WalkInteriorVision` itself** — per spec §1, it is unchanged.

- [ ] **Step 4: Run module tests**

Run: `./gradlew :knes-agent:test`
Expected: still passing; new code compiled but not yet wired into AgentSession.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt
git commit -m "feat(runtime): real adapters for emulator state + WalkInteriorVision"
```

---

## Task 11: Wire `InteriorExplorer` into `OutfitBootPhase`

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt:678-686, 750-782`
- Test: `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseExplorerTest.kt`

**Spec reference:** §11 acceptance criterion #6.

- [ ] **Step 1: Write failing test — explorer is called when no cachedShop exists**

```kotlin
class OutfitBootPhaseExplorerTest {
    @Test
    fun `runOutfitBootPhase calls explorer when no cached weapon shop`() = runBlocking {
        // Set up a session with empty LandmarkMemory + scripted explorer that returns Found.
        // Verify: explorer.exploreUntilFound was called with NPC_SHOPKEEPER + capSteps=50;
        //        BuyAtShop downstream call sees the persisted landmark.
        // Stub WalkInteriorVision step-stub to return Stepped repeatedly;
        // FakeHaikuConsult scripted to return one shopkeeper candidate + Confirmed weapon.
        TODO("write test referring to AgentSession's existing test fixtures")
    }

    @Test
    fun `runOutfitBootPhase skips explorer when cachedShop landmark exists`() = runBlocking {
        // Pre-seed LandmarkMemory with a NPC_SHOPKEEPER kind=weapon landmark.
        // Verify explorer NOT called (FakeHaikuConsult.scanCalls == 0).
        TODO("write test referring to AgentSession's existing test fixtures")
    }
}
```

Refer to existing `AgentSession` tests (e.g. those covering `runOutfitBootPhase`) for fixture patterns. Mirror the construction of `AgentSession` they use.

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Modify `discoverWeaponShop` (lines 750-782) to delegate to explorer**

Replace the body:

```kotlin
private suspend fun discoverWeaponShop(
    outfitVision: HaikuConsult,
    outfitNavigator: VisionInteriorNavigator,
    outfitMapSession: InteriorMapSession,
    fog: FogOfWar,
): Landmark? {
    // Spec 4: replace probe-loop with goal-aware InteriorExplorer.
    val explorer = InteriorExplorer(
        walk = RealWalkInteriorVisionAdapter(buildWalkInteriorVision(outfitNavigator, outfitMapSession, fog)),
        scanner = InteriorScanner(outfitVision, landmarkMemory, runId),
        frameDetector = FrameChangeDetector(),
        emu = RealInteriorEmulatorState(toolset, ramReader),
        memory = landmarkMemory,
    )
    val outcome = explorer.exploreUntilFound(
        goal = LandmarkKind.NPC_SHOPKEEPER,
        predicate = { it.note.contains("kind=weapon") },
        capSteps = 50,
    )
    return when (outcome) {
        is InteriorExplorer.ExploreOutcome.Found -> {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "interior_explore_outcome: Found, " +
                       "scans=${outcome.stats.scansTriggered}, " +
                       "confirmed=${outcome.stats.confirmed}, " +
                       "walkSteps=${outcome.stats.walkSteps}, " +
                       "costUsd=${outcome.stats.costUsd}"))
            outcome.landmark
        }
        is InteriorExplorer.ExploreOutcome.NotFoundCapReached,
        is InteriorExplorer.ExploreOutcome.StuckBailout,
        is InteriorExplorer.ExploreOutcome.EncounterTriggered -> {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "interior_explore_outcome: ${outcome::class.simpleName}, stats=...
"))
            null
        }
    }
}
```

`buildWalkInteriorVision` is a new helper that constructs the existing `WalkInteriorVision` skill with the dependencies the old code path used. Lift the construction logic verbatim from the now-replaced probe loop.

The existing `cachedShop` lookup (line 678-679) is unchanged — it still hits `LandmarkMemory.findByKind(NPC_SHOPKEEPER).firstOrNull { ... kind=weapon ... }`, which is what the explorer persists.

- [ ] **Step 4: Run tests, expect PASS (unit) + 302 baseline still green**

Run: `./gradlew :knes-agent:test`

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseExplorerTest.kt
git commit -m "feat(session): wire InteriorExplorer into runOutfitBootPhase shop discovery"
```

---

## Task 12: Telemetry trace events

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt`

**Spec reference:** §7. Seven events:
1. `interior_scan_triggered`
2. `interior_scan_candidates`
3. `interior_scan_confirmed`
4. `interior_scan_rejected`
5. `interior_scan_error`
6. `interior_explore_outcome`
7. `frame_detector` (fallback notification)

- [ ] **Step 1: Add trace-event sink as ctor param**

Both `InteriorExplorer` and `InteriorScanner` should accept `traceSink: (TraceEvent) -> Unit = {}` so unit tests can stay quiet and `AgentSession` wires the real `trace.record`.

- [ ] **Step 2: Emit events at exact points specified in §7**

In `InteriorExplorer.exploreUntilFound`, after each scan trigger:
```kotlin
traceSink(TraceEvent(turn = walkSteps, role = "system", phase = "BOOT",
    note = "interior_scan_triggered: oamDelta=${oamDelta}, " +
           "totalScansThisRun=${scansTriggered}, fallback=${frameDetector.mode}"))
```

After Pass 1 returns:
```kotlin
traceSink(TraceEvent(... note = "interior_scan_candidates: count=${scan.candidates.size}, " +
    "kinds=[${scan.candidates.joinToString(",") { it.kind }}]"))
```

In `InteriorScanner.verifyAndPersist`, on `Confirmed`:
```kotlin
traceSink(TraceEvent(... note = "interior_scan_confirmed: kind=${landmark.kind}, " +
    "mapId=${landmark.mapId}, localXY=(${landmark.localX},${landmark.localY}), " +
    "note=${landmark.note}, runId=${landmark.discoveredRunId}"))
```

…and analogously for `rejected`/`error`. On final return from explorer:
```kotlin
traceSink(TraceEvent(... note = "interior_explore_outcome: ${outcome::class.simpleName}, " +
    "scans=${stats.scansTriggered}, confirmed=${stats.confirmed}, " +
    "candidates=${stats.candidatesEvaluated}, walkSteps=${stats.walkSteps}, " +
    "costUsd=${stats.costUsd}"))
```

`FrameChangeDetector` emits no traces directly — its mode is exposed via `frameDetector.mode`, which the explorer reads and includes in `interior_scan_triggered`.

- [ ] **Step 3: Add a unit test asserting all 6 explorer-emitted events fire on a Found path**

```kotlin
@Test
fun `Found path emits 5 trace events in order`() = runBlocking {
    val captured = mutableListOf<TraceEvent>()
    val explorer = InteriorExplorer(..., traceSink = { captured += it })
    // ... run successful exploration ...
    val notes = captured.map { it.note }
    assertTrue(notes.any { it.startsWith("interior_scan_triggered") })
    assertTrue(notes.any { it.startsWith("interior_scan_candidates") })
    assertTrue(notes.any { it.startsWith("interior_scan_confirmed") })
    assertTrue(notes.any { it.startsWith("interior_explore_outcome: Found") })
}
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :knes-agent:test`

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt \
        knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerTest.kt
git commit -m "feat(runtime): telemetry trace events for interior_scan_*/explore_outcome"
```

---

## Task 13: Live integration test (gated `KNES_LIVE_VISION`)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerLiveTest.kt`
- Test fixtures: `knes-agent/src/test/resources/interior-screenshots/coneria_*.png` (5 fixed screenshots from real runs)

**Spec reference:** §6.2.

- [ ] **Step 1: Capture 5 real Coneria interior screenshots**

Run a brief manual session against the headless emulator from a fresh title screen, walking party into Coneria interior. Save 5 representative screenshots to `knes-agent/src/test/resources/interior-screenshots/`:
- `coneria_entry.png` — just-stepped-in, near entry
- `coneria_corridor.png` — middle of an internal corridor
- `coneria_shop_visible.png` — shopkeeper on screen
- `coneria_king_room.png` — king on throne (if Coneria castle reachable; else from Coneria town's NPC layout)
- `coneria_stairs.png` — stairs visible

Document method (which run, which frame) in a sibling `README.md` in the same dir.

- [ ] **Step 2: Write the gated live test**

```kotlin
class InteriorExplorerLiveTest {

    @Test
    fun `live Coneria explorer finds weapon shopkeeper within 50 steps`() = runBlocking {
        Assumptions.assumeTrue(System.getenv("KNES_LIVE_VISION") == "true",
            "skip — KNES_LIVE_VISION not set")
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY")?.isNotEmpty() == true,
            "skip — GEMINI_API_KEY not set")
        Assumptions.assumeTrue(System.getenv("ANTHROPIC_API_KEY")?.isNotEmpty() == true,
            "skip — ANTHROPIC_API_KEY not set")

        // Boot real emulator with FF1 ROM at runtime path.
        // Walk to Coneria entry (146, 152) using existing WalkOverworldTo + warp settle.
        // Construct real explorer and call exploreUntilFound(NPC_SHOPKEEPER, kind=weapon, 50).
        // Assert outcome is Found within budget; print stats.
        //
        // Expected wall-clock: 2-3 min; expected cost: ~$0.20.

        TODO("scaffolding to be filled with the boot harness used by " +
             "OutfitBootPhaseE2EWithExplorerTest")
    }
}
```

- [ ] **Step 3: Wire test into Gradle as gated**

Confirm gradle `test` task respects `Assumptions.assumeTrue` (JUnit 5 standard). No build-config change required if so.

- [ ] **Step 4: Run gated test locally with `KNES_LIVE_VISION=true`** (manual, cost ~$0.20)

```bash
KNES_LIVE_VISION=true \
  GEMINI_API_KEY=$GEMINI_API_KEY \
  ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY \
  ./gradlew :knes-agent:test --tests "*InteriorExplorerLiveTest*"
```
Expected: PASS within 3 min.

- [ ] **Step 5: Commit (skipped tests + fixtures)**

```bash
git add knes-agent/src/test/kotlin/knes/agent/runtime/InteriorExplorerLiveTest.kt \
        knes-agent/src/test/resources/interior-screenshots/
git commit -m "test(runtime): gated live Coneria explorer test + screenshot fixtures"
```

---

## Task 14: Empirical validation runs + acceptance gate

**Files:** none (manual, runtime).

**Spec reference:** §11 acceptance criteria #9 and #10.

- [ ] **Step 1: Run #1 — cold start**

```bash
rm -f ~/.knes/ff1-landmarks.json ~/.knes/ff1-overworld-warps.json \
      ~/.knes/ff1-blockages.json ~/.knes/ff1-overworld-terrain.json \
      ~/.knes/ff1-interior-memory.json ~/.knes/ff1-ow-memory.json

# Pre-seed only Coneria TOWN_ENTRY (per HANDOFF.md run command).
cat > ~/.knes/ff1-landmarks.json <<'JSON'
{"version":1,"landmarks":[{"id":"interior_entry_8_146_152","kind":"TOWN_ENTRY","worldX":146,"worldY":152,"mapIdInterior":8,"visited":true,"note":"coneria-town entry","discoveredRunId":"preseed"}]}
JSON

KNES_VISION=gemini-pro \
  ./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
    --wall-clock-cap-seconds=600 --cost-cap-usd=3.0 --max-skill-invocations=120"
```

- [ ] **Step 2: Inspect trace for success criteria**

```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E '"interior_scan_|interior_explore_outcome|boot_outfit_summary"' "$LATEST/trace.jsonl"
```

Expected:
- `interior_scan_triggered` events ≥ 5.
- `interior_scan_confirmed: kind=NPC_SHOPKEEPER, ... kind=weapon` at least once.
- `interior_explore_outcome: Found, scans=N, confirmed=≥1, walkSteps≤50, costUsd≤0.50`.
- `boot_outfit_summary: weaponsBought=N` with N ≥ 1.

If any expected line missing → analyze failure mode, see Spec §9 escalation R1-R7.

- [ ] **Step 3: Run #2 — warm start**

```bash
# Do NOT clear ~/.knes/ff1-landmarks.json this time.
KNES_VISION=gemini-pro ./gradlew :knes-agent:run --args="..."
```

- [ ] **Step 4: Inspect trace — `cachedShop` hits, no new explorer call**

```bash
grep -E 'interior_scan_|cachedShop' "$LATEST/trace.jsonl"
```

Expected:
- ZERO `interior_scan_*` events (explorer not called because `cachedShop` populated from prior run).
- `boot_outfit_summary: weaponsBought=N` with N ≥ 1.

- [ ] **Step 5: Document acceptance**

Update `HANDOFF.md` with Spec 4 results:
- Cold-start outcome (pass/fail + scan count + cost).
- Warm-start outcome (cachedShop hit confirmed).
- Any escalation triggered (R1-R7 from spec §9).

```bash
git add HANDOFF.md
git commit -m "docs(handoff): Spec 4 empirical validation results"
```

If Run #1 fails: do NOT proceed to PR. Open spec §9 for escalation. Report outcome to user.

If both runs pass: spec §11 acceptance criteria 9 + 10 satisfied. Plan complete.

---

## Self-Review

- **Spec coverage:** §1 success criteria → covered by Task 14 empirical runs (#1, #2). §2 architecture → Tasks 5, 6, 7, 8, 9, 10. §3 prompts → Tasks 3, 4 (verbatim). §4 data flow → Task 9. §5 error handling → Task 9 tests + explorer impl. §6 testing → Tasks 1-12 unit + Task 13 live + Task 14 empirical. §7 telemetry → Task 12. §8 persistence → Task 1 + Task 7. §9 future work → out of MVP scope (acknowledged). §10 known concerns → Task 10 step 1 (OAM verification), Task 14 step 2 (mapId=8 confirmed via traces). §11 acceptance criteria checklist → Task 14 step 5 commits results to HANDOFF.md. §12 repo paths → File Structure section above.
- **Placeholder scan:** Steps with `TODO(...)` markers (Tasks 8 step 1 stubs, Task 10 WalkInteriorVision adapter, Task 11/13 fixtures) all reference existing code patterns the executing agent reads first. No `TBD` for the agent to invent design from scratch.
- **Type consistency:** `WalkOutcome` (Task 8) used by `WalkInteriorVisionAdapter.step()`. `ExploreOutcome` and `ExploreStats` defined Task 8 used Task 9, 11, 12. `PersistResult` defined Task 7 used Task 9. `CandidateLandmark` / `VerifyResult` defined Task 2 used Tasks 3, 4, 6, 7. `FrameChangeDetector.SpriteSlot` defined Task 5 used Task 10. All consistent.
- **Goal mapping:** §11 acceptance items 1 (enum) → Task 1; 2 (interface methods) → Task 2; 3 (Scanner unit tests) → Tasks 6, 7; 4 (FrameChangeDetector) → Task 5; 5 (Explorer 8 tests) → Task 9; 6 (OutfitBootPhase wire) → Task 11; 7 (trace events) → Task 12; 8 (302 baseline green) → checked at end of Tasks 1, 2, 9, 11; 9 (cold-start run) → Task 14 #1; 10 (warm-start run) → Task 14 #2; 11 (mapId=8 resolution) → Task 14 step 2; 12 (OAM availability documented) → Task 10 step 1.

Plan is internally consistent and covers every spec section with a concrete task.

---

**End of plan.**
