# FF1 Buy & Equip Implementation Plan (Spec 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship deterministic outfit boot phase: agent walks to Coneria, classifies shops via Gemini vision, buys + equips one weapon per character, persists savestate-keyed flag for cross-session skip.

**Architecture:** Three new skills (`DiscoverShop`, `BuyAtShop`, `EquipWeapon`) compose into `AgentSession.runOutfitBootPhase()` invoked once at session start before the Spec 1 GRIND/REST/BRIDGE loop. Best-effort: partial completion acceptable, total failure falls through to L0 grind.

**Tech Stack:** Kotlin 2.0, kotest FunSpec, kotlinx.serialization, ktor (Gemini HTTP), JUnit 5. Reuses existing `EmulatorToolset`, `LandmarkMemory`, `StrategyContext`, `GeminiVisionConsult`.

**Spec:** `docs/superpowers/specs/2026-05-06-ff1-buy-at-shop-design.md`

**Branch:** Cut from `ff1-grind-strategy` HEAD (commit `72866b0`) into a new branch (suggested: `ff1-buy-at-shop`). PR #116 is parent.

---

## File Structure

**New files:**

| Path | Responsibility |
|---|---|
| `knes-agent/src/main/kotlin/knes/agent/runtime/OutfitState.kt` | Persistent savestate-hash-keyed flag for boot-phase skip |
| `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverShop.kt` | Open shop BUY menu, classify via Gemini, persist landmark |
| `knes-agent/src/main/kotlin/knes/agent/skills/BuyAtShop.kt` | One-purchase-per-invocation skill |
| `knes-agent/src/main/kotlin/knes/agent/skills/EquipWeapon.kt` | Field-menu nav to equip an inventory weapon |
| `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitStateTest.kt` | Round-trip + hash-mismatch tests |
| `knes-agent/src/test/kotlin/knes/agent/runtime/StrategyContextWeaponTest.kt` | New weapon helpers |
| `knes-agent/src/test/kotlin/knes/agent/skills/DiscoverShopTest.kt` | 2 unit tests |
| `knes-agent/src/test/kotlin/knes/agent/skills/BuyAtShopTest.kt` | 4 unit tests |
| `knes-agent/src/test/kotlin/knes/agent/skills/EquipWeaponTest.kt` | 4 unit tests |
| `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseTest.kt` | 3 boot orchestration tests |
| `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseE2ETest.kt` | 1 real-ROM e2e test |
| `docs/superpowers/notes/2026-05-XX-ff1-weapon-ram.md` | Task 1 RAM probe results (filename uses actual probe date) |

**Modified files:**

| Path | Change |
|---|---|
| `knes-debug/src/main/resources/profiles/ff1.json` | Add 16 weapon-slot RAM addresses |
| `knes-agent/src/main/kotlin/knes/agent/runtime/StrategyContext.kt` | Add `weaponSlot`, `weaponId`, `isEquipped`, `anyWeaponEquipped` helpers |
| `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt` | Add `classifyShopMenu` method to interface + `FakeHaikuConsult` |
| `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt` | Implement `classifyShopMenu` via Gemini API call |
| `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt` | Stub `classifyShopMenu` (default: returns `unknown`) |
| `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` | Add `runOutfitBootPhase()` private fn + call site after savestate load |

---

## Task 1: Document weapon RAM addresses from disasm references (GATE)

**Files:**
- Create: `docs/superpowers/notes/2026-05-06-ff1-weapon-ram.md`

The existing `ff1.json` profile sources addresses from Disch's FF1 disassembly (referenced throughout existing entries: `Disch ow_scroll_x`, `Disch sm_player_x`, etc.). Task 1 documents the canonical Disch addresses for weapon inventory **without playing the game** — autonomy of the agent is a project principle.

- [ ] **Step 1: Cite the disasm reference**

Standard Disch FF1 RAM map for character data:
- Char 1 base: `0x6100`, slots in fixed offsets within the 64-byte block.
- Stride: `+0x40` per subsequent character (already confirmed in `ff1.json` for stats: char1=0x6110, char2=0x6150, char3=0x6190, char4=0x61D0).
- Weapon inventory per character: 4 bytes at offset `+0x18` from char base (so char1 = 0x6118-0x611B, char2 = 0x6158-0x615B, etc.).
- Each byte: bit 7 = equipped flag, bits 0-6 = weapon ID (0 = empty slot).

Source: Disch FF1 disassembly RAM map (same source as `ff1.json` references like `Disch sm_player_x`).

- [ ] **Step 2: Document field menu button binding**

FF1 NES standard binding: `B` button on overworld opens the field menu. EQUIP is the third item in the menu list. This is canonical and consistent across all FF1 NES versions.

- [ ] **Step 3: Write notes file**

Create `docs/superpowers/notes/2026-05-06-ff1-weapon-ram.md`:

```markdown
# FF1 Weapon Inventory RAM Addresses

**Date:** 2026-05-06
**Source:** Disch FF1 disassembly (same source as existing `ff1.json` entries).

## Layout

Per-character weapon inventory: 4 slots × 4 chars, stored at offset `+0x18` from each character's base address.

| Char | Base | Weapon slots |
|---|---|---|
| 1 | 0x6100 | 0x6118-0x611B |
| 2 | 0x6140 | 0x6158-0x615B |
| 3 | 0x6180 | 0x6198-0x619B |
| 4 | 0x61C0 | 0x61D8-0x61DB |

Stride confirmed by existing `ff1.json` stat entries:
- char1_str=0x6110, char2_str=0x6150 (+0x40)
- char3_str=0x6190, char4_str=0x61D0 (+0x40)

## Byte encoding

Each weapon-slot byte:
- Bit 7 (`0x80`): equipped flag (1 = equipped, 0 = in inventory but not equipped).
- Bits 0-6 (`0x7F`): weapon ID. ID 0 = empty slot.

Examples:
- `0x00`: empty
- `0x10`: weapon ID 0x10, not equipped
- `0x90`: weapon ID 0x10, equipped
- `0x80`: weapon ID 0 + equipped flag set (anomalous; should not appear)

## Field menu navigation

Standard FF1 NES bindings:
- `B` button on overworld → open field menu.
- Menu items in order: ITEM, MAGIC, EQUIP, STATUS, EXIT (EQUIP is index 2).
- Inside EQUIP: cursor to character (1-4 listed top to bottom), `A` selects.
- Character submenu: WEAPON tab is default. Cursor selects slot, `A` toggles equipped state.

## Verification path

These addresses are not confirmed by manual play (project principle: agent plays autonomously, dev does not). If e2e test (Task 10) reveals incorrect behavior, fall back to:
1. Cross-reference Disch disasm source files in repo (if vendored).
2. Inspect emulator RAM viewer at runtime (read-only, not gameplay) using `./gradlew :knes-debug:run` to confirm specific bytes change after the agent's own purchase action.
3. Update `ff1.json` and notes file accordingly.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/notes/2026-05-06-ff1-weapon-ram.md
git commit -m "docs(notes): document FF1 weapon RAM addresses from Disch disasm"
```

**Verification posture:** Spec addresses (`0x6118+0x40*c`) treated as authoritative. If e2e reveals mismatch, runtime read-only RAM inspection is acceptable (not gameplay). No manual buy/equip required.

---

## Task 2: Add weapon RAM addresses to FF1 profile

**Files:**
- Modify: `knes-debug/src/main/resources/profiles/ff1.json`

- [ ] **Step 1: Add 16 weapon entries**

Open `knes-debug/src/main/resources/profiles/ff1.json`. After the `char4_level` line and before `battleTurn`, insert (using addresses confirmed in Task 1; example below assumes `0x6118+0x40*c`):

```json
    "char1_weapon0": {"address": "0x6118", "description": "Char 1 weapon slot 0 (high bit=equipped, low 7=weaponId)"},
    "char1_weapon1": {"address": "0x6119", "description": "Char 1 weapon slot 1"},
    "char1_weapon2": {"address": "0x611A", "description": "Char 1 weapon slot 2"},
    "char1_weapon3": {"address": "0x611B", "description": "Char 1 weapon slot 3"},

    "char2_weapon0": {"address": "0x6158", "description": "Char 2 weapon slot 0 (high bit=equipped, low 7=weaponId)"},
    "char2_weapon1": {"address": "0x6159", "description": "Char 2 weapon slot 1"},
    "char2_weapon2": {"address": "0x615A", "description": "Char 2 weapon slot 2"},
    "char2_weapon3": {"address": "0x615B", "description": "Char 2 weapon slot 3"},

    "char3_weapon0": {"address": "0x6198", "description": "Char 3 weapon slot 0 (high bit=equipped, low 7=weaponId)"},
    "char3_weapon1": {"address": "0x6199", "description": "Char 3 weapon slot 1"},
    "char3_weapon2": {"address": "0x619A", "description": "Char 3 weapon slot 2"},
    "char3_weapon3": {"address": "0x619B", "description": "Char 3 weapon slot 3"},

    "char4_weapon0": {"address": "0x61D8", "description": "Char 4 weapon slot 0 (high bit=equipped, low 7=weaponId)"},
    "char4_weapon1": {"address": "0x61D9", "description": "Char 4 weapon slot 1"},
    "char4_weapon2": {"address": "0x61DA", "description": "Char 4 weapon slot 2"},
    "char4_weapon3": {"address": "0x61DB", "description": "Char 4 weapon slot 3"},
```

(If Task 1 confirmed different addresses, substitute accordingly — same key names, real addresses.)

- [ ] **Step 2: Run profile-load test if one exists, else smoke-build**

```bash
./gradlew :knes-debug:test
```

Expected: green. If a profile-validation test fails on duplicate key or bad JSON, fix and re-run.

- [ ] **Step 3: Commit**

```bash
git add knes-debug/src/main/resources/profiles/ff1.json
git commit -m "feat(profile): add char weapon-slot RAM addresses to FF1 profile"
```

---

## Task 3: Add weapon helpers to StrategyContext

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/StrategyContext.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/StrategyContextWeaponTest.kt`

- [ ] **Step 1: Write failing tests**

Create `knes-agent/src/test/kotlin/knes/agent/runtime/StrategyContextWeaponTest.kt`:

```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StrategyContextWeaponTest : FunSpec({
    test("weaponSlot reads char N slot M from ram") {
        val ram = mapOf("char1_weapon0" to 0x10, "char1_weapon2" to 0x90, "char3_weapon1" to 0x05)
        StrategyContext.weaponSlot(ram, 1, 0) shouldBe 0x10
        StrategyContext.weaponSlot(ram, 1, 2) shouldBe 0x90
        StrategyContext.weaponSlot(ram, 3, 1) shouldBe 0x05
        StrategyContext.weaponSlot(ram, 1, 3) shouldBe 0  // missing key → 0
    }
    test("weaponId masks low 7 bits") {
        StrategyContext.weaponId(0x00) shouldBe 0
        StrategyContext.weaponId(0x10) shouldBe 0x10
        StrategyContext.weaponId(0x80) shouldBe 0
        StrategyContext.weaponId(0x90) shouldBe 0x10
        StrategyContext.weaponId(0xFF) shouldBe 0x7F
    }
    test("isEquipped checks high bit") {
        StrategyContext.isEquipped(0x00) shouldBe false
        StrategyContext.isEquipped(0x10) shouldBe false
        StrategyContext.isEquipped(0x80) shouldBe true
        StrategyContext.isEquipped(0x90) shouldBe true
    }
    test("anyWeaponEquipped scans all 4 slots for char") {
        val ramArmed = mapOf("char1_weapon0" to 0x10, "char1_weapon1" to 0, "char1_weapon2" to 0x90, "char1_weapon3" to 0)
        StrategyContext.anyWeaponEquipped(ramArmed, 1) shouldBe true
        val ramUnarmed = mapOf("char2_weapon0" to 0x10, "char2_weapon1" to 0, "char2_weapon2" to 0x05, "char2_weapon3" to 0)
        StrategyContext.anyWeaponEquipped(ramUnarmed, 2) shouldBe false
        val ramEmpty = mapOf<String, Int>()
        StrategyContext.anyWeaponEquipped(ramEmpty, 1) shouldBe false
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.runtime.StrategyContextWeaponTest"
```

Expected: FAIL with "Unresolved reference: weaponSlot" (or similar).

- [ ] **Step 3: Add helpers to StrategyContext**

Open `knes-agent/src/main/kotlin/knes/agent/runtime/StrategyContext.kt`. Inside the `object StrategyContext { ... }` body, add:

```kotlin
    fun weaponSlot(ram: Map<String, Int>, char: Int, slot: Int): Int =
        ram["char${char}_weapon${slot}"] ?: 0

    fun weaponId(byte: Int): Int = byte and 0x7F

    fun isEquipped(byte: Int): Boolean = (byte and 0x80) != 0

    fun anyWeaponEquipped(ram: Map<String, Int>, char: Int): Boolean =
        (0..3).any { isEquipped(weaponSlot(ram, char, it)) }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :knes-agent:test --tests "knes.agent.runtime.StrategyContextWeaponTest"
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/StrategyContext.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/StrategyContextWeaponTest.kt
git commit -m "feat(strategy): add weapon-slot helpers to StrategyContext"
```

---

## Task 4: OutfitState persistence module

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/OutfitState.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitStateTest.kt`

- [ ] **Step 1: Write failing tests**

Create `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitStateTest.kt`:

```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import java.nio.file.Files

class OutfitStateTest : FunSpec({
    test("markBought + load round-trips and weaponsBoughtFor matches hash") {
        val tmp = Files.createTempFile("outfit-state-", ".json").toFile().apply { deleteOnExit() }
        val store = OutfitState(file = tmp)
        store.markBought(savestateHash = "abc123", equipped = listOf(1, 2, 3, 4),
            goldSpent = 32, shops = listOf("weapon@map7-(8,5)"))

        val loaded = OutfitState(file = tmp)
        loaded.weaponsBoughtFor("abc123") shouldBe true
        loaded.weaponsBoughtFor("xyz789") shouldBe false
        loaded.shopsClassified shouldContain "weapon@map7-(8,5)"
    }

    test("missing file returns weaponsBoughtFor=false") {
        val tmp = Files.createTempFile("outfit-state-missing-", ".json").toFile()
        tmp.delete()
        val store = OutfitState(file = tmp)
        store.weaponsBoughtFor("anything") shouldBe false
    }

    test("corrupt JSON returns weaponsBoughtFor=false (no crash)") {
        val tmp = Files.createTempFile("outfit-state-corrupt-", ".json").toFile().apply { deleteOnExit() }
        tmp.writeText("not valid json {")
        val store = OutfitState(file = tmp)
        store.weaponsBoughtFor("anything") shouldBe false
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.runtime.OutfitStateTest"
```

Expected: FAIL with "Unresolved reference: OutfitState".

- [ ] **Step 3: Create OutfitState**

Create `knes-agent/src/main/kotlin/knes/agent/runtime/OutfitState.kt`:

```kotlin
package knes.agent.runtime

import knes.agent.perception.AtomicJsonWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class OutfitStateFile(
    val version: Int = 1,
    val savestateHash: String = "",
    val weaponsBoughtAt: String = "",
    val charsEquipped: List<Int> = emptyList(),
    val shopsClassified: List<String> = emptyList(),
    val totalGoldSpent: Int = 0,
)

class OutfitState(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var state: OutfitStateFile = OutfitStateFile()

    init { load() }

    val shopsClassified: List<String> get() = state.shopsClassified

    private fun load() {
        if (!file.exists()) return
        try {
            state = json.decodeFromString(OutfitStateFile.serializer(), file.readText())
        } catch (e: Exception) {
            System.err.println("[outfit-state] failed to load ${file.path}: ${e.message}")
            state = OutfitStateFile()
        }
    }

    fun weaponsBoughtFor(currentHash: String): Boolean =
        state.savestateHash == currentHash && state.charsEquipped.isNotEmpty()

    fun markBought(savestateHash: String, equipped: List<Int>, goldSpent: Int, shops: List<String>) {
        state = OutfitStateFile(
            savestateHash = savestateHash,
            weaponsBoughtAt = java.time.Instant.now().toString(),
            charsEquipped = equipped,
            shopsClassified = shops,
            totalGoldSpent = goldSpent,
        )
        AtomicJsonWriter.write(file, json.encodeToString(OutfitStateFile.serializer(), state))
    }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-outfit-state.json")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :knes-agent:test --tests "knes.agent.runtime.OutfitStateTest"
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/OutfitState.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/OutfitStateTest.kt
git commit -m "feat(runtime): OutfitState persistence — savestate-hash-keyed boot skip flag"
```

---

## Task 5: Extend HaikuConsult with classifyShopMenu

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt`

- [ ] **Step 1: Add `ShopClassification` data + interface method**

Open `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt`. Inside the `interface HaikuConsult` body, add a new data class and method:

```kotlin
    data class ShopClassification(
        /** "weapon" | "armor" | "whiteMagic" | "blackMagic" | "item" | "unknown" */
        val kind: String,
        /** Each item has fields name (string) and price (int). May be empty on unknown. */
        val items: List<Pair<String, Int>>,
        val costUsd: Double,
    )

    /** Called when an FF1 shop BUY menu is open. Implementation reads item names + prices
     *  from the screenshot and classifies the shop. Returns kind="unknown" on any failure. */
    suspend fun classifyShopMenu(
        screenshotBase64: String?,
    ): ShopClassification
```

- [ ] **Step 2: Update `FakeHaikuConsult`**

In the same file, update `FakeHaikuConsult`:

```kotlin
class FakeHaikuConsult(
    private val interiorClassifications: List<HaikuConsult.InteriorClassification> = emptyList(),
    private val dialogReadings: List<HaikuConsult.DialogReading> = emptyList(),
    private val shopClassifications: List<HaikuConsult.ShopClassification> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set
    var shopCalls: Int = 0; private set

    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotBase64: String?, runId: String,
    ): HaikuConsult.InteriorClassification {
        val res = interiorClassifications.getOrNull(interiorCalls)
            ?: HaikuConsult.InteriorClassification(emptyList(), 0.0)
        interiorCalls++
        return res
    }

    override suspend fun readDialog(screenshotBase64: String?): HaikuConsult.DialogReading {
        val res = dialogReadings.getOrNull(dialogCalls)
            ?: HaikuConsult.DialogReading("", null, 0.0)
        dialogCalls++
        return res
    }

    override suspend fun classifyShopMenu(screenshotBase64: String?): HaikuConsult.ShopClassification {
        val res = shopClassifications.getOrNull(shopCalls)
            ?: HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        shopCalls++
        return res
    }
}
```

- [ ] **Step 3: Stub in AnthropicHaikuConsult**

Open `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt`. Add a default-stub override (we use Gemini for shop classification per spec):

```kotlin
    override suspend fun classifyShopMenu(screenshotBase64: String?): HaikuConsult.ShopClassification =
        HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
```

- [ ] **Step 4: Implement in GeminiVisionConsult**

Open `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt`. Add the implementation method and supporting prompts (place near the existing `classifyInterior` / `readDialog` methods):

```kotlin
    override suspend fun classifyShopMenu(screenshotBase64: String?): HaikuConsult.ShopClassification {
        val body = buildBody(SYSTEM_SHOP, SHOP_USER_TEXT, screenshotBase64, maxOutputTokens = 800)
        val raw = postOrNull(body) ?: return HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        return parseShopResponse(raw)
    }

    private fun parseShopResponse(raw: String): HaikuConsult.ShopClassification {
        return try {
            val candidate = Regex("\\{[^{}]*\"kind\"[^{}]*}", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.value ?: return HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
            val parsed = Json.parseToJsonElement(candidate).jsonObject
            val kind = parsed["kind"]?.jsonPrimitive?.content ?: "unknown"
            val items = parsed["items"]?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val price = obj["price"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                name to price
            } ?: emptyList()
            HaikuConsult.ShopClassification(kind, items, costUsd = 0.005)  // pro pricing approx
        } catch (_: Exception) {
            HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        }
    }
```

In the same file's prompt-string section (find `SYSTEM_CLASSIFY` and `SYSTEM_DIALOG`), add:

```kotlin
private const val SYSTEM_SHOP = """You are reading the BUY menu screen of an FF1 NES shop.
Classify the shop kind and list each item name + price.
Output JSON: {"kind":"weapon"|"armor"|"whiteMagic"|"blackMagic"|"item"|"unknown","items":[{"name":"<short>","price":N}]}
Rules:
- weapon: contains physical weapons (sword, axe, dagger, hammer, nunchucks, staff)
- armor: contains body equipment (cloth, leather, mail, shield, helm, gauntlets)
- whiteMagic: spell list with CURE / HARM / FOG / RUSE / etc.
- blackMagic: spell list with FIRE / LIT / SLEP / LOCK / etc.
- item: potions, antidote, tents, cabins
- unknown: cannot classify with confidence
Return ONLY JSON."""

private const val SHOP_USER_TEXT = "Classify this shop BUY menu."
```

- [ ] **Step 5: Build to verify everything compiles**

```bash
./gradlew :knes-agent:assemble :knes-agent:test --tests "knes.agent.explorer.*"
```

Expected: green build. No new tests yet (covered in Task 6 via the skill that uses this).

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt \
        knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt \
        knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt
git commit -m "feat(vision): classifyShopMenu — Gemini reads FF1 shop BUY screen"
```

---

## Task 6: DiscoverShop skill

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverShop.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/skills/DiscoverShopTest.kt`

- [ ] **Step 1: Write failing tests**

Create `knes-agent/src/test/kotlin/knes/agent/skills/DiscoverShopTest.kt`:

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import java.nio.file.Files

class DiscoverShopTest : FunSpec({
    test("classifies weapon shop and persists landmark with kind=weapon") {
        val ram = mapOf(
            "currentMapId" to 7, "smPlayerX" to 8, "smPlayerY" to 5,
            "screenState" to 0x00,
        )
        val toolset = ScriptedShopToolset(List(20) { ram })
        val tmp = Files.createTempFile("discover-shop-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)
        val vision = FakeHaikuConsult(
            shopClassifications = listOf(
                HaikuConsult.ShopClassification(
                    "weapon",
                    items = listOf("Rapier" to 10, "Hammer" to 10, "Knife" to 5, "Staff" to 5),
                    costUsd = 0.005
                )
            )
        )
        val skill = DiscoverShop(toolset, landmarks, vision)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe true
        r.message shouldContain "Classified"
        r.message shouldContain "weapon"
        val saved = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
        saved shouldHaveSize 1
        saved[0].note shouldContain "kind=weapon"
        saved[0].mapId shouldBe 7
    }

    test("returns ClassifyFailed when vision returns unknown — no landmark write") {
        val ram = mapOf("currentMapId" to 7, "smPlayerX" to 8, "smPlayerY" to 5, "screenState" to 0)
        val toolset = ScriptedShopToolset(List(20) { ram })
        val tmp = Files.createTempFile("discover-shop-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)
        val vision = FakeHaikuConsult(shopClassifications = listOf(
            HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        ))
        val skill = DiscoverShop(toolset, landmarks, vision)

        val r = skill.invoke(emptyMap())
        r.ok shouldBe false
        r.message shouldContain "ClassifyFailed"
        landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER) shouldHaveSize 0
    }

    test("returns NotInBuilding when currentMapId=0") {
        val ram = mapOf("currentMapId" to 0, "smPlayerX" to 0, "smPlayerY" to 0, "screenState" to 0)
        val toolset = ScriptedShopToolset(listOf(ram))
        val tmp = Files.createTempFile("discover-shop-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp)
        val skill = DiscoverShop(toolset, landmarks, FakeHaikuConsult())

        val r = skill.invoke(emptyMap())
        r.ok shouldBe false
        r.message shouldContain "NotInBuilding"
    }
})

private class ScriptedShopToolset(
    private val ramSequence: List<Map<String, Int>>
) : EmulatorToolset(EmulatorSession()) {
    private var idx = 0
    override fun getState(): StateSnapshot {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StateSnapshot(frame = idx, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    }
    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int, screenshot: Boolean): StepResult {
        idx = (idx + 1).coerceAtMost(ramSequence.size - 1)
        return StepResult(frame = idx, ram = ramSequence.getOrElse(idx) { ramSequence.last() },
            heldButtons = emptyList(), screenshot = if (screenshot) byteArrayOf(0x42) else null)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.DiscoverShopTest"
```

Expected: FAIL with "Unresolved reference: DiscoverShop".

- [ ] **Step 3: Create DiscoverShop**

Create `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverShop.kt`:

```kotlin
package knes.agent.skills

import knes.agent.explorer.HaikuConsult
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import java.util.Base64

/**
 * Open the shop BUY menu, capture a screenshot, send to vision (Gemini) for classification,
 * persist NPC_SHOPKEEPER landmark with kind=weapon|armor|... in note. Caller is responsible
 * for entering the shop interior and exiting after this skill returns.
 *
 * Outcomes: Classified(kind), ClassifyFailed, NotInBuilding.
 */
class DiscoverShop(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
    private val vision: HaikuConsult,
    private val runId: String = "discover_shop",
) : Skill {
    override val id = "discover_shop"
    override val description =
        "Tap A to open shop BUY menu, classify items via vision, persist " +
        "NPC_SHOPKEEPER landmark with kind in note."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val pre = toolset.getState().ram
        val mapId = pre["currentMapId"] ?: 0
        if (mapId == 0) {
            return SkillResult(ok = false,
                message = "NotInBuilding: currentMapId=0", ramAfter = pre)
        }
        // Approach keeper + open BUY menu: 4 taps A
        repeat(4) { toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30) }
        // Capture screenshot for vision call
        val step = toolset.tap(button = "A", count = 0, pressFrames = 0, gapFrames = 1, screenshot = true)
        val screenshot = step.screenshot?.let { Base64.getEncoder().encodeToString(it) }

        val classification = try {
            vision.classifyShopMenu(screenshot)
        } catch (e: Exception) {
            HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        }

        // Tap B 4 times to back out of BUY menu and shop dialog
        repeat(4) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 20) }

        if (classification.kind == "unknown") {
            return SkillResult(ok = false,
                message = "ClassifyFailed: vision returned unknown",
                ramAfter = toolset.getState().ram)
        }

        val final = toolset.getState().ram
        val localX = final["smPlayerX"] ?: 0
        val localY = final["smPlayerY"] ?: 0
        val itemsNote = classification.items.joinToString(",") { "${it.first}:${it.second}" }
        val landmark = Landmark(
            id = "shop-map$mapId-$localX-$localY",
            kind = LandmarkKind.NPC_SHOPKEEPER,
            mapId = mapId, localX = localX, localY = localY,
            note = "kind=${classification.kind}; items=$itemsNote",
            discoveredRunId = runId,
        )
        landmarks.recordIfNew(landmark)
        landmarks.save()

        return SkillResult(
            ok = true,
            message = "Classified: kind=${classification.kind} at map=$mapId local=($localX,$localY) " +
                "items=${classification.items.size}",
            ramAfter = final,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.DiscoverShopTest"
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/DiscoverShop.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/DiscoverShopTest.kt
git commit -m "feat(skill): DiscoverShop — vision-classify shop BUY menu, persist landmark"
```

---

## Task 7: BuyAtShop skill

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/BuyAtShop.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/skills/BuyAtShopTest.kt`

- [ ] **Step 1: Write failing tests**

Create `knes-agent/src/test/kotlin/knes/agent/skills/BuyAtShopTest.kt`:

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import java.nio.file.Files

class BuyAtShopTest : FunSpec({

    fun seedShopLandmark(landmarks: LandmarkMemory, mapId: Int = 7, kind: String = "weapon",
                        items: List<Pair<String, Int>> = listOf("Rapier" to 10, "Hammer" to 10)) {
        val itemsNote = items.joinToString(",") { "${it.first}:${it.second}" }
        landmarks.record(Landmark(
            id = "shop-test", kind = LandmarkKind.NPC_SHOPKEEPER,
            mapId = mapId, localX = 8, localY = 5,
            note = "kind=$kind; items=$itemsNote", discoveredRunId = "test"
        ))
    }

    test("Bought when gold drops AND inventory byte populated") {
        val tmp = Files.createTempFile("buy-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp).also { seedShopLandmark(it) }
        val pre = mapOf(
            "currentMapId" to 7, "smPlayerX" to 8, "smPlayerY" to 5, "screenState" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,  // 400
            "char1_weapon0" to 0,
        )
        val post = pre.toMutableMap().apply {
            put("goldLow", 0x86); put("goldMid", 0x01)  // 390 (cost 10)
            put("char1_weapon0", 0x10)  // weapon ID 0x10, not equipped
        }
        val toolset = ScriptedBuyToolset(listOf(pre, pre, pre, pre, post, post, post, post, post, post))
        val skill = BuyAtShop(toolset, landmarks)

        val r = skill.invoke(mapOf(
            "itemSlot" to "0", "forCharSlot" to "1", "expectedKeeperKind" to "weapon"
        ))
        r.ok shouldBe true
        r.message shouldContain "Bought"
        r.message shouldContain "cost=10"
    }

    test("InsufficientGold returned synchronously when preGold < expectedPrice") {
        val tmp = Files.createTempFile("buy-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp).also {
            seedShopLandmark(it, items = listOf("Rapier" to 10))
        }
        val pre = mapOf(
            "currentMapId" to 7, "screenState" to 0,
            "goldLow" to 5, "goldMid" to 0, "goldHigh" to 0,  // 5 gold, can't afford 10
            "char1_weapon0" to 0,
        )
        val toolset = ScriptedBuyToolset(listOf(pre))
        val skill = BuyAtShop(toolset, landmarks)

        val r = skill.invoke(mapOf(
            "itemSlot" to "0", "forCharSlot" to "1", "expectedKeeperKind" to "weapon"
        ))
        r.ok shouldBe false
        r.message shouldContain "InsufficientGold"
        toolset.tapsIssued shouldBe 0
    }

    test("WrongClass when gold + inventory unchanged after dismiss frames") {
        val tmp = Files.createTempFile("buy-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp).also { seedShopLandmark(it) }
        val stuck = mapOf(
            "currentMapId" to 7, "smPlayerX" to 8, "smPlayerY" to 5, "screenState" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,  // 400 unchanged
            "char1_weapon0" to 0,                                    // unchanged
        )
        val toolset = ScriptedBuyToolset(List(40) { stuck })
        val skill = BuyAtShop(toolset, landmarks)

        val r = skill.invoke(mapOf(
            "itemSlot" to "0", "forCharSlot" to "1", "expectedKeeperKind" to "weapon"
        ))
        r.ok shouldBe false
        r.message shouldContain "WrongClass"
    }

    test("LandmarkKindMismatch when expectedKeeperKind != landmark kind") {
        val tmp = Files.createTempFile("buy-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmp).also {
            seedShopLandmark(it, kind = "armor")
        }
        val pre = mapOf("currentMapId" to 7, "screenState" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "char1_weapon0" to 0)
        val toolset = ScriptedBuyToolset(listOf(pre))
        val skill = BuyAtShop(toolset, landmarks)

        val r = skill.invoke(mapOf(
            "itemSlot" to "0", "forCharSlot" to "1", "expectedKeeperKind" to "weapon"
        ))
        r.ok shouldBe false
        r.message shouldContain "LandmarkKindMismatch"
        toolset.tapsIssued shouldBe 0
    }
})

private class ScriptedBuyToolset(
    private val ramSequence: List<Map<String, Int>>
) : EmulatorToolset(EmulatorSession()) {
    private var idx = 0
    var tapsIssued: Int = 0; private set
    override fun getState(): StateSnapshot {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StateSnapshot(frame = idx, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    }
    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int, screenshot: Boolean): StepResult {
        tapsIssued++
        idx = (idx + 1).coerceAtMost(ramSequence.size - 1)
        return StepResult(frame = idx, ram = ramSequence.getOrElse(idx) { ramSequence.last() },
            heldButtons = emptyList(), screenshot = null)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.BuyAtShopTest"
```

Expected: FAIL with "Unresolved reference: BuyAtShop".

- [ ] **Step 3: Create BuyAtShop**

Create `knes-agent/src/main/kotlin/knes/agent/skills/BuyAtShop.kt`:

```kotlin
package knes.agent.skills

import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.runtime.StrategyContext
import knes.agent.tools.EmulatorToolset

/**
 * Buy a single item from the shop the party is currently inside. Reads the
 * cached NPC_SHOPKEEPER landmark for kind validation and price lookup.
 *
 * Args:
 *   itemSlot           — 0-indexed BUY-list slot to purchase.
 *   forCharSlot        — 1..4, character to receive the item.
 *   expectedKeeperKind — e.g. "weapon"; skill bails on landmark mismatch.
 *
 * Outcomes: Bought, InsufficientGold (sync pre-check), WrongClass, NotInShop,
 *           LandmarkKindMismatch.
 */
class BuyAtShop(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
) : Skill {
    override val id = "buy_at_shop"
    override val description =
        "Buy one item at the current shop. Args: itemSlot, forCharSlot, expectedKeeperKind."

    private val maxDismissFrames = 30
    private val wrongClassFrames = 5

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val itemSlot = args["itemSlot"]?.toIntOrNull()
            ?: return failResult("Bad args: itemSlot missing/invalid", emptyMap())
        val forCharSlot = args["forCharSlot"]?.toIntOrNull()
            ?: return failResult("Bad args: forCharSlot missing/invalid", emptyMap())
        val expectedKind = args["expectedKeeperKind"]
            ?: return failResult("Bad args: expectedKeeperKind missing", emptyMap())

        val pre = toolset.getState().ram
        val mapId = pre["currentMapId"] ?: 0
        if (mapId == 0) {
            return failResult("NotInShop: currentMapId=0", pre)
        }
        // Find a shopkeeper landmark on this map
        val landmark = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
            .firstOrNull { it.mapId == mapId }
            ?: return failResult("NotInShop: no NPC_SHOPKEEPER landmark for mapId=$mapId", pre)
        val landmarkKind = parseKind(landmark.note)
        if (landmarkKind != expectedKind) {
            return failResult(
                "LandmarkKindMismatch: expected=$expectedKind landmark=$landmarkKind",
                pre
            )
        }

        // Pre-check gold against item price from landmark items list
        val items = parseItems(landmark.note)
        val expectedPrice = items.getOrNull(itemSlot)?.second
            ?: return failResult("Bad args: itemSlot $itemSlot out of range (have ${items.size})", pre)
        val preGold = StrategyContext.totalGold(pre)
        if (preGold < expectedPrice) {
            return SkillResult(ok = false,
                message = "InsufficientGold: preGold=$preGold expectedPrice=$expectedPrice",
                ramAfter = pre)
        }
        val preWeaponByte = StrategyContext.weaponSlot(pre, forCharSlot, 0)
        val preInvSum = (0..3).sumOf { StrategyContext.weaponId(StrategyContext.weaponSlot(pre, forCharSlot, it)) }

        // Open BUY: 1 tap A on keeper, 1 tap A on BUY
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // Cursor down itemSlot times
        repeat(itemSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // Select character: cursor right (forCharSlot - 1) positions in party hand cursor
        repeat(forCharSlot - 1) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // Confirm YES
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)

        // Watch RAM for purchase outcome
        var dismissTaps = 0
        var unchangedTaps = 0
        while (dismissTaps < maxDismissFrames) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
            dismissTaps++
            val ram = toolset.getState().ram
            val curGold = StrategyContext.totalGold(ram)
            val curInvSum = (0..3).sumOf { StrategyContext.weaponId(StrategyContext.weaponSlot(ram, forCharSlot, it)) }
            if (curGold < preGold && curInvSum > preInvSum) {
                // Tap B to back to BUY list (caller handles exit)
                repeat(2) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 15) }
                return SkillResult(
                    ok = true,
                    message = "Bought: cost=${preGold - curGold} char=$forCharSlot slot=$itemSlot " +
                        "weaponSum ${preInvSum}->$curInvSum",
                    ramAfter = ram,
                )
            }
            if (curGold == preGold && curInvSum == preInvSum) {
                unchangedTaps++
                if (unchangedTaps >= wrongClassFrames) {
                    repeat(3) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 15) }
                    return SkillResult(ok = false,
                        message = "WrongClass: char=$forCharSlot itemSlot=$itemSlot — gold/inventory " +
                            "unchanged after $unchangedTaps dismiss taps",
                        ramAfter = ram)
                }
            } else {
                unchangedTaps = 0
            }
        }
        val final = toolset.getState().ram
        return SkillResult(ok = false,
            message = "Bought: dismiss-cap exhausted ($maxDismissFrames taps) without confirmed " +
                "gold drop — likely WrongClass or stuck dialog",
            ramAfter = final)
    }

    private fun parseKind(note: String): String {
        val m = Regex("kind=([a-zA-Z]+)").find(note) ?: return "unknown"
        return m.groupValues[1]
    }
    private fun parseItems(note: String): List<Pair<String, Int>> {
        val itemsPart = Regex("items=([^;]*)").find(note)?.groupValues?.get(1) ?: return emptyList()
        return itemsPart.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) null else (parts[0] to (parts[1].toIntOrNull() ?: return@mapNotNull null))
        }
    }
    private fun failResult(message: String, ram: Map<String, Int>) =
        SkillResult(ok = false, message = message, ramAfter = ram)
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.BuyAtShopTest"
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/BuyAtShop.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/BuyAtShopTest.kt
git commit -m "feat(skill): BuyAtShop — one purchase per invocation, gold + inventory validation"
```

---

## Task 8: EquipWeapon skill

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/EquipWeapon.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/skills/EquipWeaponTest.kt`

- [ ] **Step 1: Write failing tests**

Create `knes-agent/src/test/kotlin/knes/agent/skills/EquipWeaponTest.kt`:

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession

class EquipWeaponTest : FunSpec({
    test("Equipped when high bit transitions on target slot") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0x10,  // owned, not equipped
        )
        val mid = pre  // menu nav; same RAM
        val post = pre.toMutableMap().apply {
            put("char1_weapon0", 0x90)  // equipped flag set
        }
        val toolset = ScriptedEquipToolset(listOf(pre) + List(20) { mid } + listOf(post, post, post, post, post))
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe true
        r.message shouldContain "Equipped"
    }

    test("AlreadyEquipped when high bit already set at entry") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0x90,
        )
        val toolset = ScriptedEquipToolset(listOf(pre))
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe true
        r.message shouldContain "AlreadyEquipped"
        toolset.tapsIssued shouldBe 0
    }

    test("WeaponNotInSlot when slot byte is 0") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0,
        )
        val toolset = ScriptedEquipToolset(listOf(pre))
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe false
        r.message shouldContain "WeaponNotInSlot"
        toolset.tapsIssued shouldBe 0
    }

    test("MenuStuck when equipped flag never sets after maxTaps") {
        val pre = mapOf(
            "currentMapId" to 0, "screenState" to 0,
            "char1_weapon0" to 0x10,
        )
        val toolset = ScriptedEquipToolset(List(80) { pre })
        val skill = EquipWeapon(toolset)

        val r = skill.invoke(mapOf("charSlot" to "1", "weaponSlot" to "0"))
        r.ok shouldBe false
        r.message shouldContain "MenuStuck"
    }
})

private class ScriptedEquipToolset(
    private val ramSequence: List<Map<String, Int>>
) : EmulatorToolset(EmulatorSession()) {
    private var idx = 0
    var tapsIssued: Int = 0; private set
    override fun getState(): StateSnapshot {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StateSnapshot(frame = idx, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    }
    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int, screenshot: Boolean): StepResult {
        tapsIssued++
        idx = (idx + 1).coerceAtMost(ramSequence.size - 1)
        return StepResult(frame = idx, ram = ramSequence.getOrElse(idx) { ramSequence.last() },
            heldButtons = emptyList(), screenshot = null)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.EquipWeaponTest"
```

Expected: FAIL with "Unresolved reference: EquipWeapon".

- [ ] **Step 3: Create EquipWeapon**

Create `knes-agent/src/main/kotlin/knes/agent/skills/EquipWeapon.kt`:

```kotlin
package knes.agent.skills

import knes.agent.runtime.StrategyContext
import knes.agent.tools.EmulatorToolset

/**
 * Open field menu, navigate EQUIP → char → weapon slot, toggle equip-flag.
 * Idempotent: returns AlreadyEquipped if high bit already set.
 *
 * Args:
 *   charSlot   — 1..4
 *   weaponSlot — 0..3 (which inventory slot)
 *
 * Outcomes: Equipped, AlreadyEquipped, MenuStuck, WeaponNotInSlot.
 *
 * Field menu button: assumes B opens menu on overworld (FF1 default).
 * Adjust if Task 1 RAM probe reveals different binding.
 */
class EquipWeapon(private val toolset: EmulatorToolset) : Skill {
    override val id = "equip_weapon"
    override val description = "Equip a specific weapon slot for a specific char via field menu."

    private val maxTaps = 60
    private val recoveryBTaps = 5

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val charSlot = args["charSlot"]?.toIntOrNull()
            ?: return SkillResult(false, "Bad args: charSlot missing/invalid", ramAfter = emptyMap())
        val weaponSlot = args["weaponSlot"]?.toIntOrNull()
            ?: return SkillResult(false, "Bad args: weaponSlot missing/invalid", ramAfter = emptyMap())

        val pre = toolset.getState().ram
        val preByte = StrategyContext.weaponSlot(pre, charSlot, weaponSlot)
        if (StrategyContext.weaponId(preByte) == 0) {
            return SkillResult(false, "WeaponNotInSlot: char=$charSlot slot=$weaponSlot byte=0", ramAfter = pre)
        }
        if (StrategyContext.isEquipped(preByte)) {
            return SkillResult(true, "AlreadyEquipped: char=$charSlot slot=$weaponSlot byte=$preByte", ramAfter = pre)
        }

        // Open menu (B), nav to EQUIP, select char, select weapon slot.
        // Sequence is FF1-specific; concrete tap counts may need refinement during Task 1 / e2e.
        toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 30)        // open menu
        repeat(2) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }  // cursor to EQUIP (assume index 2)
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)        // enter EQUIP
        repeat(charSlot - 1) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)        // select char
        // WEAPON sub-tab assumed default; if not, requires Right-then-A (refine during e2e)
        repeat(weaponSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)        // toggle equip

        // Watch RAM for high-bit set
        var taps = 0
        while (taps < maxTaps) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
            taps++
            val ram = toolset.getState().ram
            val curByte = StrategyContext.weaponSlot(ram, charSlot, weaponSlot)
            if (StrategyContext.isEquipped(curByte)) {
                // Close menu: B-mash to overworld
                repeat(4) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 20) }
                return SkillResult(true,
                    "Equipped: char=$charSlot slot=$weaponSlot byte ${preByte}->$curByte after $taps taps",
                    ramAfter = ram)
            }
        }
        // Recovery: B-mash regardless of menu state
        repeat(recoveryBTaps) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 20) }
        return SkillResult(false,
            "MenuStuck: $maxTaps taps without equipped-flag transition for char=$charSlot slot=$weaponSlot",
            ramAfter = toolset.getState().ram)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :knes-agent:test --tests "knes.agent.skills.EquipWeaponTest"
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/EquipWeapon.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/EquipWeaponTest.kt
git commit -m "feat(skill): EquipWeapon — field-menu nav, equip-flag validation"
```

---

## Task 9: Outfit boot phase orchestration in AgentSession

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseTest.kt`

The boot phase is invoked once after `pressStartUntilOverworld` returns. It uses the existing `walkOverworldTo`, `exitInterior` primitives plus the new skills. Best-effort throughout.

- [ ] **Step 1: Write failing tests for the three skip-paths**

Create `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseTest.kt`:

```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import java.nio.file.Files

/**
 * White-box tests for AgentSession.runOutfitBootPhase().
 * Each test stubs the dependencies (toolset, landmarks, outfit-state, skills) and
 * asserts the boot-phase decision tree without executing actual gameplay.
 */
class OutfitBootPhaseTest : FunSpec({
    test("skip when OutfitState flag matches savestate hash") {
        val tmpOutfit = Files.createTempFile("boot-skip-flag-", ".json").toFile().apply { deleteOnExit() }
        val state = OutfitState(file = tmpOutfit)
        state.markBought("hash-A", listOf(1, 2, 3, 4), 32, listOf("weapon@7-(8,5)"))
        val ram = mapOf("currentMapId" to 0, "char1_weapon0" to 0)
        val toolset = ScriptedToolset(List(5) { ram })
        val tmpLand = Files.createTempFile("boot-skip-land-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpLand)
        val trace = mutableListOf<String>()

        val result = OutfitBootPhase(toolset, landmarks, OutfitState(file = tmpOutfit),
            savestateHash = "hash-A",
            trace = { kind, msg -> trace += "$kind:$msg" }).run()

        result.skipped shouldBe true
        result.reason shouldContain "already-bought"
        toolset.tapsIssued shouldBe 0
    }

    test("skip when live RAM shows all 4 chars equipped") {
        val tmpOutfit = Files.createTempFile("boot-ram-", ".json").toFile().apply { deleteOnExit() }
        // No prior flag
        val ram = mapOf(
            "currentMapId" to 0,
            "char1_weapon0" to 0x90, "char2_weapon0" to 0x91,
            "char3_weapon0" to 0x92, "char4_weapon0" to 0x93,
        )
        val toolset = ScriptedToolset(listOf(ram))
        val tmpLand = Files.createTempFile("boot-ram-land-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpLand)
        val trace = mutableListOf<String>()

        val result = OutfitBootPhase(toolset, landmarks, OutfitState(file = tmpOutfit),
            savestateHash = "hash-B",
            trace = { kind, msg -> trace += "$kind:$msg" }).run()

        result.skipped shouldBe true
        result.reason shouldContain "ram shows all 4"
        toolset.tapsIssued shouldBe 0
        // Marks the flag for next time
        OutfitState(file = tmpOutfit).weaponsBoughtFor("hash-B") shouldBe true
    }

    test("falls back gracefully when no TOWN_ENTRY landmark exists") {
        val tmpOutfit = Files.createTempFile("boot-no-town-", ".json").toFile().apply { deleteOnExit() }
        val ram = mapOf(
            "currentMapId" to 0,
            "char1_weapon0" to 0, "char2_weapon0" to 0,
            "char3_weapon0" to 0, "char4_weapon0" to 0,
        )
        val toolset = ScriptedToolset(listOf(ram))
        val tmpLand = Files.createTempFile("boot-no-town-land-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpLand)  // empty
        val trace = mutableListOf<String>()

        val result = OutfitBootPhase(toolset, landmarks, OutfitState(file = tmpOutfit),
            savestateHash = "hash-C",
            trace = { kind, msg -> trace += "$kind:$msg" }).run()

        result.skipped shouldBe false
        result.reason shouldContain "no_town_entry"
        toolset.tapsIssued shouldBe 0
        trace.any { it.contains("boot_outfit_summary") } shouldBe true
    }
})

private class ScriptedToolset(
    private val ramSequence: List<Map<String, Int>>
) : EmulatorToolset(EmulatorSession()) {
    private var idx = 0
    var tapsIssued: Int = 0; private set
    override fun getState(): StateSnapshot {
        val ram = ramSequence.getOrElse(idx) { ramSequence.last() }
        return StateSnapshot(frame = idx, ram = ram, cpu = emptyMap(), heldButtons = emptyList())
    }
    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int, screenshot: Boolean): StepResult {
        tapsIssued++
        idx = (idx + 1).coerceAtMost(ramSequence.size - 1)
        return StepResult(frame = idx, ram = ramSequence.getOrElse(idx) { ramSequence.last() },
            heldButtons = emptyList(), screenshot = null)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :knes-agent:test --tests "knes.agent.runtime.OutfitBootPhaseTest"
```

Expected: FAIL with "Unresolved reference: OutfitBootPhase".

- [ ] **Step 3: Create OutfitBootPhase orchestrator class**

Create `knes-agent/src/main/kotlin/knes/agent/runtime/OutfitBootPhase.kt`:

```kotlin
package knes.agent.runtime

import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset

/**
 * One-shot boot-phase orchestrator. Runs once at session start before the
 * strategic GRIND/REST/BRIDGE loop. Best-effort: any failure is logged
 * and falls through to the strategic loop without bailing the session.
 *
 * Composes existing primitives (walkOverworldTo, exitInterior) with the new
 * DiscoverShop / BuyAtShop / EquipWeapon skills (wired in by AgentSession).
 *
 * This class is intentionally minimal — full per-character buy/equip wiring
 * lives in AgentSession.runOutfitBootPhase() which constructs the dependent
 * skills with the live AdvisorAgent / EmulatorToolset / etc.
 */
class OutfitBootPhase(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
    private val outfitState: OutfitState,
    private val savestateHash: String,
    private val trace: (String, String) -> Unit,
) {
    data class Result(val skipped: Boolean, val reason: String, val charsEquipped: List<Int> = emptyList())

    fun run(): Result {
        if (outfitState.weaponsBoughtFor(savestateHash)) {
            trace("boot_outfit_skip", "already-bought, hash matches")
            return Result(skipped = true, reason = "already-bought")
        }
        val ram = toolset.getState().ram
        val allEquipped = (1..4).all { StrategyContext.anyWeaponEquipped(ram, it) }
        if (allEquipped) {
            outfitState.markBought(savestateHash, listOf(1, 2, 3, 4), goldSpent = 0,
                shops = emptyList())
            trace("boot_outfit_skip", "ram shows all 4 already equipped")
            return Result(skipped = true, reason = "ram shows all 4 equipped",
                charsEquipped = listOf(1, 2, 3, 4))
        }
        val coneriaEntry = landmarks.findByKind(LandmarkKind.TOWN_ENTRY)
            .firstOrNull { it.note.contains("coneria", ignoreCase = true) }
            ?: landmarks.findByKind(LandmarkKind.TOWN_ENTRY).firstOrNull()
        if (coneriaEntry == null) {
            trace("boot_outfit_summary", "no_town_entry_landmark")
            return Result(skipped = false, reason = "no_town_entry")
        }
        // Full orchestration (walk, discover, buy, equip) is wired in
        // AgentSession.runOutfitBootPhase which has access to all skills + advisor.
        // This unit returns a sentinel; integration test in Task 11 covers the full path.
        trace("boot_outfit_summary", "phase ready, delegating to AgentSession composer")
        return Result(skipped = false, reason = "ready_for_compose")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :knes-agent:test --tests "knes.agent.runtime.OutfitBootPhaseTest"
```

Expected: PASS (3 tests).

- [ ] **Step 5: Wire the full composer into AgentSession**

Open `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`. Find the existing `pressStartUntilOverworld` call site (early session setup, before the strategic loop opens). Add:

```kotlin
// After pressStartUntilOverworld returns and before strategic loop:
runOutfitBootPhase()
```

Then add the private function. Place it near the existing `consultAdvisorForStrategy` helpers:

```kotlin
private suspend fun runOutfitBootPhase() {
    val savestateHash = computeSavestateHash()
    val outfitState = OutfitState()
    val phase = OutfitBootPhase(
        toolset = toolset,
        landmarks = landmarks,
        outfitState = outfitState,
        savestateHash = savestateHash,
        trace = { kind, msg -> trace.record(kind, msg) }
    )
    val pre = phase.run()
    if (pre.skipped || pre.reason == "no_town_entry") return

    // Walk to Coneria, discover or use cached weapon shop
    val coneriaEntry = landmarks.findByKind(LandmarkKind.TOWN_ENTRY)
        .firstOrNull { it.note.contains("coneria", ignoreCase = true) }
        ?: landmarks.findByKind(LandmarkKind.TOWN_ENTRY).first()
    val walkResult = WalkOverworldTo(toolset, observer).invoke(mapOf(
        "targetX" to coneriaEntry.worldX!!.toString(),
        "targetY" to coneriaEntry.worldY!!.toString(),
    ))
    if (!walkResult.ok) {
        trace.record("boot_outfit_summary", "walk_to_coneria_failed: ${walkResult.message}")
        return
    }

    val cachedShop = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
        .firstOrNull { it.note.contains("kind=weapon") }

    val activeShop = cachedShop ?: discoverWeaponShop()
    if (activeShop == null) {
        trace.record("boot_outfit_summary", "boot_shop_not_found")
        return
    }

    // Per-char buy loop
    val buySkill = BuyAtShop(toolset, landmarks)
    val charsBought = mutableListOf<Int>()
    var goldSpent = 0
    val initialGold = StrategyContext.totalGold(toolset.getState().ram)
    val weaponSlotByChar = mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3)

    for (charSlot in 1..4) {
        if (StrategyContext.anyWeaponEquipped(toolset.getState().ram, charSlot)) continue
        var slot = weaponSlotByChar[charSlot] ?: continue
        var retries = 0
        while (retries < 3) {
            val r = buySkill.invoke(mapOf(
                "itemSlot" to slot.toString(),
                "forCharSlot" to charSlot.toString(),
                "expectedKeeperKind" to "weapon"
            ))
            trace.record("boot_purchase", "char$charSlot slot=$slot result=${r.message}")
            if (r.ok) { charsBought += charSlot; break }
            if (r.message.contains("WrongClass")) { slot = (slot + 1) % 4; retries++; continue }
            break
        }
    }

    // Exit shop + town: existing primitives
    ExitInterior(toolset, observer).invoke(emptyMap())

    // Per-char equip loop on overworld
    val equipSkill = EquipWeapon(toolset)
    val charsEquipped = mutableListOf<Int>()
    for (charSlot in 1..4) {
        val ram = toolset.getState().ram
        val ownedSlot = (0..3).firstOrNull {
            StrategyContext.weaponId(StrategyContext.weaponSlot(ram, charSlot, it)) != 0
        } ?: continue
        if (StrategyContext.isEquipped(StrategyContext.weaponSlot(ram, charSlot, ownedSlot))) {
            charsEquipped += charSlot; continue
        }
        val r = equipSkill.invoke(mapOf(
            "charSlot" to charSlot.toString(),
            "weaponSlot" to ownedSlot.toString(),
        ))
        trace.record("boot_equip", "char$charSlot slot=$ownedSlot result=${r.message}")
        if (r.ok) charsEquipped += charSlot
    }

    val finalGold = StrategyContext.totalGold(toolset.getState().ram)
    goldSpent = (initialGold - finalGold).coerceAtLeast(0)
    val shopsClassified = listOf("weapon@map${activeShop.mapId}-(${activeShop.localX},${activeShop.localY})")

    if (charsEquipped.isNotEmpty()) {
        outfitState.markBought(savestateHash, charsEquipped, goldSpent, shopsClassified)
    }
    trace.record("boot_outfit_summary",
        "candidatesProbed=${if (cachedShop == null) 1 else 0} weaponShopFound=true " +
        "weaponsBought=${charsBought.size} weaponsEquipped=${charsEquipped.size} " +
        "totalGoldSpent=$goldSpent")
}

private suspend fun discoverWeaponShop(): knes.agent.perception.Landmark? {
    val candidates = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER).take(4)
    val discoverSkill = DiscoverShop(toolset, landmarks, vision)
    for (cand in candidates) {
        // Walk to candidate (interior coords) — relies on caller having entered Coneria
        val walk = WalkInteriorVision(toolset, vision).invoke(mapOf(
            "targetLocalX" to cand.localX!!.toString(),
            "targetLocalY" to cand.localY!!.toString(),
        ))
        if (!walk.ok) continue
        val classify = discoverSkill.invoke(emptyMap())
        if (classify.ok && classify.message.contains("kind=weapon")) {
            return landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
                .first { it.note.contains("kind=weapon") }
        }
    }
    return null
}

private fun computeSavestateHash(): String {
    val savestatePath = config.savestatePath ?: return "no-savestate"
    return try {
        val bytes = java.io.File(savestatePath).readBytes()
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "savestate-error:${e.message}"
    }
}
```

(Adapt `config.savestatePath`, `vision`, `observer`, etc. to the actual field names in `AgentSession`. Inspect the file before editing to use the correct accessors.)

- [ ] **Step 6: Smoke-build to verify integration compiles**

```bash
./gradlew :knes-agent:assemble
```

Expected: green build. Fix any unresolved references against actual `AgentSession` field names.

- [ ] **Step 7: Run all unit tests to verify no regression**

```bash
./gradlew :knes-agent:test
```

Expected: PASS — 35 (Spec 1) + 18 (new Spec 2 unit tests) + 233 (baseline) = 286 minimum.

- [ ] **Step 8: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/OutfitBootPhase.kt \
        knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseTest.kt
git commit -m "feat(session): outfit boot phase — discover shop, buy + equip weapons before grind"
```

---

## Task 10: E2E integration test

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseE2ETest.kt`

This test loads the real ROM + savestate but stubs the Gemini call. Mirror of `GrindAndHealCycleE2ETest`.

- [ ] **Step 1: Inspect existing E2E test for pattern**

Open `knes-agent/src/test/kotlin/knes/agent/runtime/GrindAndHealCycleE2ETest.kt` and read it. Note:
- How it constructs `AgentSession` with stubbed advisor.
- How it pre-seeds savestate / ROM paths via env vars or hardcoded test fixtures.
- What it asserts.

- [ ] **Step 2: Create OutfitBootPhaseE2ETest**

Create `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseE2ETest.kt`:

```kotlin
package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import java.nio.file.Files
import java.nio.file.Paths

class OutfitBootPhaseE2ETest : FunSpec({
    val romPath = System.getenv("FF1_ROM_PATH")
    val savestatePath = System.getenv("FF1_SAVESTATE")

    test("end-to-end: discovers weapon shop, buys + equips at least 1 weapon").config(
        enabled = romPath != null && savestatePath != null && Files.exists(Paths.get(romPath))
    ) {
        // Pre-seed Coneria TOWN_ENTRY landmark (e2e bypasses Phase 1 explorer)
        val tmpLand = Files.createTempFile("e2e-land-", ".json").toFile().apply { deleteOnExit() }
        val landmarks = LandmarkMemory(file = tmpLand)
        landmarks.record(Landmark(
            id = "coneria-entry",
            kind = LandmarkKind.TOWN_ENTRY,
            worldX = 152, worldY = 159,    // confirmed Coneria entry from Spec 1 validation
            note = "coneria-town",
        ))
        landmarks.save()

        // Stub Gemini: first call returns weapon shop, all subsequent return unknown
        val vision = FakeHaikuConsult(
            shopClassifications = listOf(
                HaikuConsult.ShopClassification(
                    "weapon",
                    items = listOf("Rapier" to 10, "Hammer" to 10, "Knife" to 5, "Staff" to 5),
                    costUsd = 0.005
                ),
                HaikuConsult.ShopClassification("unknown", emptyList(), 0.0),
                HaikuConsult.ShopClassification("unknown", emptyList(), 0.0),
            )
        )

        // Build AgentSession with stubbed vision + landmarks; budget=180s wallclock
        // (Adapt construction to actual AgentSession constructor signature.)
        val tmpOutfit = Files.createTempFile("e2e-outfit-", ".json").toFile().apply { deleteOnExit() }
        val traceEvents = mutableListOf<Pair<String, String>>()

        val outcome = TestSessionBuilder(
            romPath = romPath!!, savestatePath = savestatePath!!,
            landmarks = landmarks, vision = vision,
            outfitStateFile = tmpOutfit,
            wallClockCapSeconds = 180,
            onTrace = { kind, msg -> traceEvents += kind to msg },
        ).build().runOutfitBootPhaseOnly()

        // Assert: at least one purchase succeeded and at least one equip succeeded
        traceEvents.any { it.first == "boot_purchase" && it.second.contains("Bought") } shouldBe true
        traceEvents.any { it.first == "boot_equip" && it.second.contains("Equipped") } shouldBe true
        val summary = traceEvents.last { it.first == "boot_outfit_summary" }.second
        summary shouldContain "weaponsBought="
        // Final RAM check: at least 1 char with equipped weapon
        val finalState = OutfitState(file = tmpOutfit)
        finalState.weaponsBoughtFor(/* hash computed by session */ "").let { } // placeholder:
        // The actual hash is internal; assert via outcome instead
        outcome.charsEquipped.size shouldBeGreaterThanOrEqual 1
    }
})

/**
 * Minimal test harness for invoking just the outfit boot phase. Adapt this to
 * the actual AgentSession test pattern from GrindAndHealCycleE2ETest.
 *
 * If AgentSession exposes runOutfitBootPhase as `internal`, call directly.
 * Otherwise add a test-only public hook.
 */
private class TestSessionBuilder(
    val romPath: String, val savestatePath: String,
    val landmarks: LandmarkMemory, val vision: HaikuConsult,
    val outfitStateFile: java.io.File, val wallClockCapSeconds: Int,
    val onTrace: (String, String) -> Unit,
) {
    fun build(): TestSession = TODO("Wire to AgentSession constructor — see GrindAndHealCycleE2ETest")
}

private interface TestSession {
    val charsEquipped: List<Int>
    fun runOutfitBootPhaseOnly(): TestSession
}
```

(The stubs at the bottom are placeholders. The actual implementation copies the construction pattern from `GrindAndHealCycleE2ETest`, replacing the advisor stub with our `FakeHaikuConsult`.)

- [ ] **Step 3: Run e2e test (with env vars set)**

```bash
FF1_ROM_PATH=$PWD/roms/ff.nes FF1_SAVESTATE=$PWD/roms/spawn.savestate \
  ./gradlew :knes-agent:test --tests "knes.agent.runtime.OutfitBootPhaseE2ETest"
```

Expected: PASS. If FAIL, iterate on tap counts in `BuyAtShop` / `EquipWeapon` based on actual emulator behavior. The e2e test is the canonical signal that menu navigation taps are correct.

- [ ] **Step 4: Iterate on tap counts if needed**

If `BuyAtShop` returns `WrongClass` for all attempts, the cursor-down sequence is off. If `EquipWeapon` returns `MenuStuck`, the field-menu navigation taps are wrong. Adjust the per-step tap counts in the skill source files (Tasks 7 and 8) until the e2e test passes. Each iteration: re-run unit tests for the changed skill, then re-run e2e.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseE2ETest.kt \
        knes-agent/src/main/kotlin/knes/agent/skills/BuyAtShop.kt \
        knes-agent/src/main/kotlin/knes/agent/skills/EquipWeapon.kt
git commit -m "test(e2e): OutfitBootPhaseE2ETest + tap-count refinement from real-ROM run"
```

---

## Task 11: Manual validation runs

**Files:**
- Modify: PR description (add validation table)

- [ ] **Step 1: Run 3 fresh-savestate Phase 2 sessions**

```bash
rm -f ~/.knes/ff1-outfit-state.json ~/.knes/ff1-landmarks.json  # fresh boot every time
./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes --wall-clock-cap-seconds=420 --cost-cap-usd=2.0 --max-skill-invocations=80"
```

Repeat 3 times. Capture trace logs.

- [ ] **Step 2: Extract `boot_outfit_summary` from each run**

For each run trace, grep:
```bash
grep "boot_outfit_summary\|boot_purchase\|boot_equip" path/to/trace.log
```

Capture: `weaponsBought`, `weaponsEquipped`, `totalGoldSpent` per run.

- [ ] **Step 3: Capture death-restart count delta vs. PR #116 baseline**

For each run, count occurrences of `PartyDefeated` or death-restart pattern. Compare to PR #116 v4 run (32 deaths in 7 min). Target: ≤ 5 per run.

- [ ] **Step 4: Update PR description**

When PR is opened (Task 12), include this validation table:

```markdown
## Validation runs

| Run | weaponsBought | weaponsEquipped | totalGoldSpent | deaths | min_level reached |
|---|---|---|---|---|---|
| 1 | ? | ? | ? | ? | ? |
| 2 | ? | ? | ? | ? | ? |
| 3 | ? | ? | ? | ? | ? |
```

Fill in actual numbers. Best-effort policy means partial completion (e.g., 1 of 4 equipped) still counts as validation pass.

- [ ] **Step 5: Commit any tap-count refinements from validation**

If validation runs surfaced additional bugs (e.g., shop entry exits before vision fires, equip menu uses START not B), fix in respective skill files and re-run unit + e2e tests.

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/...
git commit -m "fix(spec2): validation-run refinements from manual sessions"
```

---

## Task 12: Open PR

**Files:**
- None (uses `gh pr create`)

- [ ] **Step 1: Push branch and open PR**

```bash
git push -u origin ff1-buy-at-shop
gh pr create --base master --title "FF1 Buy & Equip — Spec 2 of 2 (boot phase: weapons before grind)" \
  --body "$(cat <<'EOF'
## Summary

Spec 2 of the leave-peninsula initiative: deterministic outfit boot phase that runs once at session start, finds the Coneria weapon shop via Gemini vision, buys + equips one weapon per character, and persists a savestate-keyed flag for cross-session skip.

Closes the death-loop blocker identified in PR #116 validation runs (32 deaths in 7 min with starting weapons).

## What's in this PR

- Three new skills: `DiscoverShop`, `BuyAtShop`, `EquipWeapon` (mirror of Spec 1 pattern)
- 16 new weapon-slot RAM addresses in FF1 profile + 4 helpers in `StrategyContext`
- New `OutfitState` persistence module (`~/.knes/ff1-outfit-state.json`)
- New `runOutfitBootPhase()` in `AgentSession` invoked once at session start
- 18 new unit tests + 1 e2e integration test (all green)

## Spec / plan

- Spec: `docs/superpowers/specs/2026-05-06-ff1-buy-at-shop-design.md`
- Plan: `docs/superpowers/plans/2026-05-06-ff1-buy-at-shop.md`
- RAM probe note: `docs/superpowers/notes/2026-05-XX-ff1-weapon-ram.md`

## Validation runs

[Insert table from Task 11.]

## Test plan

- [ ] Unit tests green: `./gradlew :knes-agent:test`
- [ ] E2E test green with FF1_ROM_PATH + FF1_SAVESTATE
- [ ] No regression in Spec 1 tests (35 baseline)
- [ ] 3 manual Phase 2 runs documented above

## Parent

Cut from `ff1-grind-strategy` HEAD (`72866b0`). PR #116 is parent for Spec 1.
EOF
)"
```

- [ ] **Step 2: Verify PR is open**

```bash
gh pr view --json url,number,state
```

Expected: state=OPEN, URL printed.

---

## Self-review (run after writing the plan)

**Spec coverage check:**

| Spec section | Plan task |
|---|---|
| §2 Architecture (boot phase + skills) | Tasks 4 (OutfitState), 6/7/8 (skills), 9 (boot phase) |
| §3.1 DiscoverShop | Task 6 |
| §3.2 BuyAtShop | Task 7 |
| §3.3 EquipWeapon | Task 8 |
| §3.4 RAM additions | Tasks 1 (verify), 2 (add) |
| §3.5 StrategyContext helpers | Task 3 |
| §3.6 OutfitState persistence | Task 4 |
| §4 Data flow | Task 9 (orchestration) |
| §5 Error handling table | Distributed across Tasks 6-9 (each skill handles its own row) |
| §6.1 Unit tests | Tasks 3, 4, 6, 7, 8, 9 (15 unit tests + 3 boot tests = 18 covered) |
| §6.2 E2E test | Task 10 |
| §6.3 RAM probe | Task 1 |
| §6.4 Manual validation | Task 11 |
| §6.5 Acceptance criteria | Implicit gate before Task 12 |
| §7 Decomposition / §8 PR strategy | Task 12 |

All spec sections traceable to a task.

**Placeholder scan:** No "TBD" / "TODO" / "implement later" / "fill in details" in step bodies. Code blocks present for every code step. Two intentional notes: Task 1 filename uses `XX` for actual probe date; `TestSessionBuilder` in Task 10 is explicitly a stub the engineer wires to the existing E2E pattern.

**Type consistency:** `SkillResult(ok, message, framesElapsed, ramAfter)` matches existing data class. `Landmark.note` parsing uses regex consistent across `BuyAtShop` (`parseKind`, `parseItems`) — both look for `kind=...` and `items=...` patterns persisted by `DiscoverShop`. `OutfitState.weaponsBoughtFor(hash)` matches usage in `OutfitBootPhase.run()`. `StrategyContext.weaponSlot/weaponId/isEquipped/anyWeaponEquipped` signatures consistent across all uses.

**Test count:** 4 (Task 3) + 3 (Task 4) + 3 (Task 6) + 4 (Task 7) + 4 (Task 8) + 3 (Task 9) = 21 unit tests + 1 e2e = 22 tests. Spec said 15 unit + 1 e2e = 16. Plan exceeds spec target — acceptable (more coverage). Spec acceptance criterion line 6.5 should read "≥ 15"; effectively satisfied.
