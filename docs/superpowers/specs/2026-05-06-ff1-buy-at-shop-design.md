# FF1 Buy & Equip — Design (Spec 2)

**Date:** 2026-05-06
**Branch target:** TBD (cut from `ff1-grind-strategy` HEAD; PR #116 is parent for Spec 1).
**Parent context:** Spec 1 (`docs/superpowers/specs/2026-05-06-ff1-grind-and-heal-cycle-design.md`) shipped strategy decision infrastructure (GRIND/REST/BRIDGE) but validation runs on PR #116 showed party L0 with starting weapons death-loops in 3-4 round IMP encounters (32 deaths in one 7-min run). The dominant blocker is *combat damage*, not strategic decisions. Net consensus: buy Coneria weapons before grinding.

## 1. Goal & success criteria

**Goal:** At session start, the agent autonomously walks to Coneria, finds the weapon shop, buys one weapon per character class, and equips each weapon — before the strategic GRIND/REST/BRIDGE loop begins. Subsequent sessions skip this work via a persistent flag.

**Success criteria (measurable):**
1. **Functional:** in ≥ 70% of fresh-savestate Phase 2 runs, at least 1 character finishes the boot phase with a non-zero equipped weapon (high bit set in `char{N}_weapon{M}` RAM byte). Best-effort policy means partial completion (≥ 1 of 4) counts.
2. **Stability:** zero regressions in Spec 1's 35 tests; no infinite loops in shop discovery, BUY menu, or EQUIP menu (each gated by hard step caps).
3. **Resilience:** boot-phase failures (no shop found, vision unavailable, partial buys) never bail the session — strategic loop runs regardless.
4. **Persistence:** after one successful boot, the savestate-keyed flag prevents re-running boot phase on subsequent sessions of the same savestate.
5. **Validation hypothesis (longitudinal, not PR gate):** in ≥ 70% of full Phase 2 runs after Spec 2 ships, death-restart count (Spec 1 metric) drops to ≤ 5 per run (vs. 32 in PR #116 baseline). Documented in handoff memory across sessions.

**Non-goals:**
- Selling items, buying potions, buying spells (separate flows).
- Armor shops, white/black magic shops (Spec 3+).
- Re-equipping found-loot weapons mid-game (Spec 3+).
- Optimal weapon-per-class selection (we use a default mapping with brute-force retry on `WrongClass`).

## 2. Architecture

Spec 2 ships a deterministic **outfit boot phase** that runs once at session start before the strategic decision loop. Three new skills compose into the boot phase; the Spec 1 GRIND/REST/BRIDGE loop is unchanged.

```
SESSION START
   │
   ▼
┌──────────────────────────┐
│  OUTFIT BOOT PHASE       │  one-shot, best-effort
│  (AgentSession)          │
│                          │
│  1. resolveConeriaEntry  │  LandmarkMemory[TOWN_ENTRY]
│  2. walkToConeria        │  existing walkOverworldTo
│  3. findOrDiscoverShop   │  cached or probe loop
│  4. for c in 1..4:       │  best-effort per char
│      a. BuyAtShop(c)     │
│  5. exit shop, exit town │
│  6. for c in 1..4:       │  on overworld
│      a. EquipWeapon(c)   │
│  7. log boot_outfit_     │  one trace anchor
│     summary              │
└──────────┬───────────────┘
           │
           ▼
   STRATEGIC LOOP  (Spec 1: GRIND / REST / BRIDGE — unchanged)
```

**Key invariants:**
- Boot phase runs **at most once per session** and is **non-re-entrant**.
- Boot phase is **skipped** if `weaponsBoughtThisSavestate` flag matches current savestate hash, OR if live RAM shows all 4 chars already equipped.
- Boot phase **never bails the session**. Total boot failure → strategic loop runs L0 (today's behavior, no regression).
- Skills are atomic and reusable. AgentSession holds the only orchestration logic.

**Touch points in `AgentSession`:**
- New private `runOutfitBootPhase()` invoked once after savestate load + `pressStartUntilOverworld`, before strategic loop opens.
- New persistent flag file `~/.knes/ff1-outfit-state.json` keyed by SHA-256 of savestate file bytes.
- New trace event kinds: `boot_outfit_start`, `boot_shop_classified`, `boot_purchase`, `boot_equip`, `boot_outfit_summary`.

**Out of scope for Spec 2:**
- Re-entering shops mid-grind to upgrade.
- Selling, buying potions, buying magic.
- Armor shops (same pattern, deferred to Spec 3).
- Cross-town shopping (Pravoka, etc. — reachable only post-Garland).

## 3. Components

### 3.1 `DiscoverShop` skill

**Location:** `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverShop.kt`

**Pre-condition:** Party is inside a candidate shop interior (`currentMapId != 0`). Caller has walked to a building entry and crossed in.

**Behavior:**
1. Tap A toward the shopkeeper position to open the BUY/SELL menu (deterministic A taps with facing-keeper logic, or simple `tap A 4×`).
2. Tap A on BUY to open the item list.
3. Capture screenshot. Send to `GeminiVisionConsult` with prompt:
   `"This is an FF1 shop BUY menu. Classify the items shown. Return JSON: {kind: 'weapon'|'armor'|'whiteMagic'|'blackMagic'|'item'|'unknown', items: [{name, price}]}"`.
4. Press B until back at shop main, then tap to EXIT (or simply press B until overworld).
5. On classify success: persist `Landmark(kind=NPC_SHOPKEEPER, mapId=<current>, localX/Y=<keeper>, note="kind=weapon|..., items=<json>")` via `recordIfNew`.
6. Return `Classified(kind)` or `ClassifyFailed`.

**Args:** none beyond implicit state.
**Outcomes:** `Classified(kind)`, `ClassifyFailed`, `NotInBuilding`.

### 3.2 `BuyAtShop` skill

**Location:** `knes-agent/src/main/kotlin/knes/agent/skills/BuyAtShop.kt`

**Pre-condition:** Party is inside a shop interior whose `NPC_SHOPKEEPER` landmark has been classified (`note` contains `kind=weapon`).

**Args:**
- `itemSlot: Int` — 0-indexed position in BUY list (0..N-1).
- `forCharSlot: Int` — 1..4, character to receive the item.
- `expectedKeeperKind: String` — e.g. `"weapon"`. Skill bails if landmark mismatch.

**Behavior:**
1. RAM snapshot: `preGold`, `preInventory[forCharSlot]`.
2. Pre-check `expectedPrice` (read from cached landmark `note=items=...` for this `itemSlot`): if `preGold < expectedPrice` → return `InsufficientGold` synchronously, no menu nav.
3. Tap A toward keeper → BUY menu.
4. Cursor down `itemSlot` times → A.
5. Cursor to `forCharSlot - 1` (party hand cursor) → A.
6. Confirm prompt → A on YES.
7. Watch RAM ≤ 30 dismiss frames:
   - **Success:** `goldAfter < preGold` AND `inventoryAfter[forCharSlot]` non-zero new weapon byte → `Bought(cost, weaponByte)`.
   - **WrongClass:** gold unchanged AND inventory unchanged after 5 dismiss taps → `WrongClass`.
8. Tap B to back to shop main menu. Caller is responsible for actual exit-from-interior.

**Outcomes:** `Bought`, `InsufficientGold`, `WrongClass`, `NotInShop`, `BudgetExhausted`.

### 3.3 `EquipWeapon` skill

**Location:** `knes-agent/src/main/kotlin/knes/agent/skills/EquipWeapon.kt`

**Pre-condition:** Party is on overworld (`currentMapId == 0` AND `screenState ∉ battle states`). Weapon already in target char's inventory.

**Args:**
- `charSlot: Int` — 1..4.
- `weaponSlot: Int` — 0..3, which inventory slot to equip.

**Behavior:**
1. RAM snapshot: `preEquippedFlags[charSlot][weaponSlot]`.
2. Open field menu (button binding TBD in plan — `B` or `START`).
3. Cursor down to EQUIP, A.
4. Cursor to charSlot, A.
5. Cursor to WEAPON sub-tab if present, A on `weaponSlot`.
6. Watch RAM: equipped-flag bit transitions on the target slot → `Equipped`.
7. Press B to back out twice → close menu.
8. Verify `currentMapId == 0` and `screenState` back to overworld value before returning.

**Outcomes:** `Equipped`, `AlreadyEquipped` (idempotent), `MenuStuck`, `WeaponNotInSlot`.

### 3.4 RAM profile additions (`knes-debug/src/main/resources/profiles/ff1.json`)

Per-character weapon inventory: 4 slots × 4 chars. Believed disasm layout:
- char1: `0x6118-0x611B`
- char2: `0x6158-0x615B` (+0x40)
- char3: `0x6198-0x619B` (+0x80)
- char4: `0x61D8-0x61DB` (+0xC0)

Each byte: high bit (`0x80`) = equipped flag; low 7 bits = weapon ID.

New keys (16 total):
```
char{1..4}_weapon{0..3} : {address: "0x6118+offset", description: "Char N weapon slot M (high bit=equipped, low 7=weaponId)"}
```

**Caveat:** these addresses are unverified. Plan Task 1 is a one-shot RAM probe (load ROM, manually buy known weapon, dump RAM diff) to confirm or correct. All subsequent tasks gated on Task 1 outcome.

### 3.5 New helpers in `StrategyContext`

```kotlin
fun weaponSlot(ram: Map<String, Int>, char: Int, slot: Int): Int = ram["char${char}_weapon${slot}"] ?: 0
fun weaponId(byte: Int): Int = byte and 0x7F
fun isEquipped(byte: Int): Boolean = (byte and 0x80) != 0
fun anyWeaponEquipped(ram: Map<String, Int>, char: Int): Boolean = (0..3).any { isEquipped(weaponSlot(ram, char, it)) }
```

### 3.6 New persistence: `OutfitState`

**Location:** `knes-agent/src/main/kotlin/knes/agent/runtime/OutfitState.kt` (or `knes/agent/perception/`).

**File:** `~/.knes/ff1-outfit-state.json`
```json
{
  "version": 1,
  "savestateHash": "<sha256 of savestate file>",
  "weaponsBoughtAt": "2026-05-06T22:00:00Z",
  "charsEquipped": [1, 2, 3, 4],
  "shopsClassified": ["weapon@map7-(8,5)"],
  "totalGoldSpent": 32
}
```

**API:**
```kotlin
object OutfitState {
    fun load(): OutfitState
    fun weaponsBoughtFor(currentHash: String): Boolean
    fun markBought(hash: String, equipped: List<Int>, goldSpent: Int, shops: List<String>)
}
```

`AtomicJsonWriter` reuse for the write path (already in `LandmarkMemory`).

## 4. Data flow

### 4.1 Boot phase entry guard

```kotlin
private suspend fun runOutfitBootPhase() {
    val state = OutfitState.load()
    if (state.weaponsBoughtFor(savestateHash)) {
        trace.record("boot_outfit_skip", "already-bought, hash matches")
        return
    }
    val ram = toolset.getState().ram
    if ((1..4).all { StrategyContext.anyWeaponEquipped(ram, it) }) {
        OutfitState.markBought(savestateHash, listOf(1,2,3,4), 0, emptyList())
        trace.record("boot_outfit_skip", "ram shows all 4 already equipped")
        return
    }
    // ... actual boot phase ...
}
```

Two skips: persistent flag (cross-session) + live RAM pre-check (handles flag/savestate mismatch).

### 4.2 Coneria entry resolution

```kotlin
val coneriaEntry = landmarks.findByKind(LandmarkKind.TOWN_ENTRY)
    .firstOrNull { it.note.contains("coneria", ignoreCase = true) }
    ?: landmarks.findByKind(LandmarkKind.TOWN_ENTRY).firstOrNull()
    ?: run {
        trace.record("boot_outfit_summary", "no_town_entry_landmark, fallback to overworld grind")
        return
    }
```

If LandmarkMemory has no town entry, boot phase exits gracefully — strategic loop runs on L0 with no regression.

### 4.3 Shop discovery

**Cached path** (subsequent runs / same savestate):
```kotlin
val cachedShop = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
    .firstOrNull { it.note.contains("kind=weapon") }
if (cachedShop != null) {
    walkOverworldTo(coneriaEntry.worldX!!, coneriaEntry.worldY!!)
    walkInteriorTo(cachedShop.localX!!, cachedShop.localY!!)
    // proceed to purchases
}
```

**Probing path** (no cached weapon shop):
1. Walk to Coneria entry, enter town.
2. Get candidate list: existing `NPC_SHOPKEEPER` landmarks (any classification) + interior frontier candidates. If empty → one Gemini consult on town interior screenshot to list candidates.
3. For each candidate (max `MAX_SHOP_CANDIDATES = 4`):
   - `walkInteriorTo(candidate.localX, candidate.localY)`
   - Verify entered shop (mapId/screen transition).
   - Invoke `DiscoverShop`.
   - On `Classified(weapon)` → break, proceed to purchases.
   - On `Classified(other)` or `ClassifyFailed` → exit shop, continue loop.
4. Loop exhausted → log `boot_shop_not_found`, exit boot phase.

### 4.4 Per-character purchase loop

```kotlin
val weaponSlotByChar = mapOf(
    1 to 0,  // char1: BUY-list slot 0
    2 to 1,
    3 to 2,
    4 to 3,
)
// Default mapping; on WrongClass, mini-retry advances slot up to 3 times per char.
```

```kotlin
for (charSlot in 1..4) {
    if (StrategyContext.anyWeaponEquipped(toolset.getState().ram, charSlot)) {
        trace.record("boot_purchase_skip", "char$charSlot already armed")
        continue
    }
    var slotAttempt = weaponSlotByChar[charSlot] ?: continue
    var retries = 0
    while (retries < 3) {
        val buyResult = buyAtShop.invoke(mapOf(
            "itemSlot" to slotAttempt.toString(),
            "forCharSlot" to charSlot.toString(),
            "expectedKeeperKind" to "weapon",
        ))
        trace.record("boot_purchase", "char$charSlot slot=$slotAttempt result=${buyResult.message}")
        if (buyResult.ok) break
        if (buyResult.message.contains("WrongClass")) {
            slotAttempt = (slotAttempt + 1) % 4
            retries++
            continue
        }
        break  // InsufficientGold / NotInShop / BudgetExhausted: stop
    }
}
```

### 4.5 Exit + equip phase

After purchases, walk out of shop and town (existing `exitInterior` + `walkOverworldTo(spawnX, spawnY)`). Equip on overworld (preconditions hold there):

```kotlin
for (charSlot in 1..4) {
    val ram = toolset.getState().ram
    val ownedSlot = (0..3).firstOrNull {
        StrategyContext.weaponId(StrategyContext.weaponSlot(ram, charSlot, it)) != 0
    } ?: continue
    if (StrategyContext.isEquipped(StrategyContext.weaponSlot(ram, charSlot, ownedSlot))) continue
    val equipResult = equipWeapon.invoke(mapOf(
        "charSlot" to charSlot.toString(),
        "weaponSlot" to ownedSlot.toString(),
    ))
    trace.record("boot_equip", "char$charSlot slot=$ownedSlot result=${equipResult.message}")
}
```

### 4.6 Persistence on success

After loop completes (any non-zero number of equips):
```kotlin
OutfitState.markBought(savestateHash, charsEquipped, totalGoldSpent, shopsClassified)
```

Wipe + retry semantics: deleting `~/.knes/ff1-outfit-state.json` re-runs the boot phase.

### 4.7 Trace summary

Final event `boot_outfit_summary`:
```
{candidatesProbed, weaponShopFound, weaponsBought, weaponsEquipped, totalGoldSpent, durationFrames}
```
Single anchor diagnostic for validation runs.

### 4.8 RAM-snapshot fields used in boot phase

| Field | Source | Use |
|---|---|---|
| `currentMapId` | profile | enter/exit verification |
| `screenState` | profile | battle interception, overworld verification |
| `goldLow/Mid/High` | profile | `preGold` / `goldAfter` for purchase validation |
| `char{N}_weapon{M}` | **NEW** in profile | inventory + equipped-flag validation |
| `smPlayerX/Y` | profile | walk-to-keeper, walk-to-shop-tile |

## 5. Error handling

| Situation | Detection | Handling |
|---|---|---|
| **No `TOWN_ENTRY` landmark for Coneria** | `landmarks.findByKind(TOWN_ENTRY)` empty | Log `boot_outfit_summary: no_town_entry`, return. Strategic loop runs L0. |
| **`walkOverworldTo(coneria)` returns BLOCKED / WANDERED** | Existing skill outcome | Log, return. Best-effort. |
| **`MAX_SHOP_CANDIDATES=4` probed, no weapon shop** | Loop exhaustion | Log `boot_shop_not_found`, persist already-classified shops to landmarks, return. |
| **`DiscoverShop` returns `ClassifyFailed`** | `result.message.contains("ClassifyFailed")` | Caller exits shop, advances candidate. After 2 consecutive Gemini failures, treat remaining candidates as `unknown` (skip rather than retry). |
| **`DiscoverShop` `NotInBuilding`** (`currentMapId == 0`) | Skill outcome | Caller logs, advances candidate. |
| **`BuyAtShop` returns `InsufficientGold`** | Pre-check inside skill: `preGold < expectedPrice` (price read from cached landmark `note=items=...`). Returned synchronously before menu nav. | Skip remaining purchases. Continue to equip phase. |
| **`BuyAtShop` returns `WrongClass`** | RAM gold + inventory unchanged | Mini-retry: try next item slot up to 3 attempts. After 3 fails per char, skip char. |
| **`BuyAtShop` returns `NotInShop`** (warped out mid-skill) | RAM check at skill entry | Caller breaks per-char loop, proceeds to exit + equip. |
| **Random encounter during boot phase** | `screenState ∈ {0x68, 0x63}` | Existing `BattleFightAll` PostBattle handler intercepts (boot calls battle-aware primitives, not raw movement). Resume boot at next step after battle. |
| **`PartyDefeated` during boot** | `FfPhase.PartyDefeated` | Bail entire session with `Outcome.PartyDefeated`. Same as Spec 1. |
| **`EquipWeapon` returns `MenuStuck`** | RAM equipped flag unchanged after N taps + `currentMapId` not back to 0 | Skill attempts B-mash recovery (5 B taps); if still stuck, returns `MenuStuck`. AgentSession logs, continues to next char. |
| **`EquipWeapon` returns `WeaponNotInSlot`** | `weaponId(ram, char, slot) == 0` at skill entry | Caller logs, tries next non-zero slot for that char up to 4 attempts. |
| **`EquipWeapon` returns `AlreadyEquipped`** | High bit already set at skill entry | No-op success. Idempotent. |
| **Wallclock budget exhausted mid-boot** | Existing budget check | Skill returns `BudgetExhausted`; AgentSession logs, exits boot, strategic loop also exits. |
| **`OutfitState.json` corrupt** | JSON parse error on load | Treat as missing, fresh boot runs, file overwritten on success. |
| **Savestate hash mismatch** | `loaded.savestateHash != currentHash` | Treated as missing flag → boot runs. RAM pre-check (4.1) skips back if equipment already there. |
| **Anti-thrash: boot loops forever** | `MAX_BOOT_STEPS = 200` | Hard cap: bail with `boot_outfit_summary: step_cap_exhausted`. Strategic loop runs as-is. |

**Explicitly NOT handled in Spec 2:**
- Shopkeeper crash bugs (rare engine issues) — manual debug.
- Selling unwanted items.
- Multi-shop tour (armor + magic in same boot).
- Re-equipping different weapons mid-game.
- Recovery from partial equip — partial state is acceptable.

**Best-effort promise:** boot phase never bails the session on its own except `PartyDefeated` and `BudgetExhausted` (which would bail the strategic loop too).

## 6. Testing strategy

### 6.1 Unit tests (JUnit, deterministic)

Location: `knes-agent/src/test/kotlin/knes/agent/skills/` + `runtime/`.

| Test | Verifies |
|---|---|
| `DiscoverShopClassificationTest` | Stub Gemini returns `{kind:"weapon",items:[...]}` → `Classified(weapon)` + landmark persisted with `note=kind=weapon`. `unknown` → `ClassifyFailed`, no write. Throws → `ClassifyFailed`. |
| `DiscoverShopNotInBuildingTest` | RAM `currentMapId=0` at entry → `NotInBuilding`, no taps. |
| `BuyAtShopGoldDropTest` | Pre `gold=400, weapon0=0`; after taps `gold=390, weapon0=0x10` → `Bought(cost=10)`. |
| `BuyAtShopInsufficientGoldTest` | Pre `gold=5`, landmark `items=[{price=10,...}]` → `InsufficientGold` returned synchronously, no menu taps issued. |
| `BuyAtShopWrongClassTest` | Pre `gold=400`; gold + inventory unchanged for 5 dismiss frames → `WrongClass`. |
| `BuyAtShopLandmarkKindMismatchTest` | `expectedKeeperKind="weapon"` but landmark `kind=armor` → returns immediately, no BUY menu entry. |
| `EquipWeaponSetsFlagTest` | Pre `char1_weapon0=0x10`; after nav `char1_weapon0=0x90` → `Equipped`. |
| `EquipWeaponAlreadyEquippedTest` | Pre `char1_weapon0=0x90` → `AlreadyEquipped`, no menu nav. |
| `EquipWeaponNotInSlotTest` | Pre `char1_weapon0=0` → `WeaponNotInSlot`, no menu nav. |
| `EquipWeaponMenuStuckTest` | RAM equipped flag never updates after 30 taps → `MenuStuck`, B-mash recovery attempted. |
| `OutfitStateRoundTripTest` | `markBought(hash, [1..4], 32, ["weapon@..."])` → file written → `load().weaponsBoughtFor(hash)==true`. Different hash → false. Corrupt file → load returns empty. |
| `StrategyContextWeaponHelpersTest` | `weaponSlot()`, `weaponId()`, `isEquipped()`, `anyWeaponEquipped()` for representative bytes (0x00, 0x10, 0x80, 0x90). |
| `BootPhaseSkipWhenAlreadyEquippedTest` | RAM all 4 chars equipped → `runOutfitBootPhase()` returns immediately, no skill invocations, persists outfit state. |
| `BootPhaseSkipWhenFlagSetTest` | OutfitState matching savestateHash + flag=true → returns immediately. |
| `BootPhaseFallbackNoTownEntryTest` | LandmarkMemory has no `TOWN_ENTRY` → returns gracefully, logs `no_town_entry`, no skill invocations. |

Target: **15 unit tests**. All use stub `EmulatorToolset` and stub `GeminiVisionConsult`.

### 6.2 Integration test (real ROM, savestate-driven)

One test: `OutfitBootPhaseE2ETest`.
- Loads FF1 ROM + savestate=spawn (Coneria, party L0, no weapons, gold=400).
- Stub Gemini: returns `{kind:"weapon",items:[...]}` for first non-empty BUY-menu screenshot; `{kind:"unknown"}` otherwise.
- Stub LandmarkMemory pre-seeded with Coneria `TOWN_ENTRY`.
- Runs `AgentSession.runOutfitBootPhase()` with wallclock=180s.
- Asserts:
  - ≥ 1 `boot_purchase` trace event with `result.ok=true`.
  - ≥ 1 `boot_equip` trace event with `result.ok=true`.
  - `boot_outfit_summary.weaponsBought >= 1`.
  - Final RAM: `anyWeaponEquipped(ram, charSlot)==true` for ≥ 1 char.
  - `OutfitState.load().weaponsBoughtFor(currentHash)==true`.
  - `Outcome != PartyDefeated`.

Target: **1 e2e test**, no real Gemini API calls in CI.

### 6.3 RAM verification probe (one-shot, manual, pre-implementation)

Plan **Task 1, gate before all subsequent tasks**:
1. Load FF1 ROM + savestate=spawn.
2. Manually walk to Coneria weapon shop, buy known weapon for char1.
3. Dump RAM region `0x6100-0x61FF` before/after.
4. Confirm: which byte changed, value matches expected weapon ID, equipped-flag location.
5. If addresses match spec assumption → proceed.
6. If different → update `ff1.json` to actual addresses, update spec & plan accordingly.

Result captured in `docs/superpowers/notes/2026-05-XX-ff1-weapon-ram.md`. ~30-60 min effort.

### 6.4 Manual validation runs (sanity, not automated)

Pre-merge: 3 Phase 2 runs with real Anthropic advisor + real Gemini vision on Spec 2 branch.

Metrics in PR description:
- `boot_outfit_summary` per run (weaponsBought, weaponsEquipped, totalGoldSpent).
- Did `min_level` reach 3 within budget? (Spec 1 success criterion, retested with weapons.)
- Death-restart count (target: drop from PR #116 baseline of 32 to ≤ 5).
- Spec 1 regressions (must remain green).

Merge **not** blocked on "all 3 reach L3" — same posture as Spec 1.

### 6.5 Acceptance criterion (Spec 2 PR)

- All 15 unit tests green.
- Integration test green.
- Spec 1 + baseline test count not regressed (35 + 233 = 268 minimum, plus 16 Spec 2 = 284 expected).
- RAM probe note committed and addresses verified.
- 3 manual runs documented in PR.

### 6.6 Out of scope

- Real-Gemini stress test in CI.
- Cross-savestate boot phase matrix.
- Full GRIND-after-buying validation (Spec 1 retest, documented but not gated).

## 7. Decomposition context

This is **Spec 2 of 2** for the leave-peninsula initiative.

| Spec | Builds | Status |
|---|---|---|
| **Spec 1** | `grindLoop` + `restAtInn` + `discoverInn` skills + AgentSession strategic decision point | **PR #116 open** |
| **Spec 2** (this doc) | `discoverShop` + `buyAtShop` + `equipWeapon` skills + outfit boot phase + RAM additions | **Now** |

After Spec 2: full grind→heal→bridge flow ships. Reaching Garland depends on this plus existing bridge traversal (already implemented, just unreachable today without armed party).

## 8. PR strategy

Spec 2 ships as its own PR cut from `ff1-grind-strategy` HEAD with PR #116 as parent. Keeps Spec 1 review surface stable; Spec 2 reviewable independently. Merge order: PR #116 → Spec 2 PR. Worst case (Spec 1 needs revisions during Spec 2 review): rebase Spec 2 onto updated #116.

## 9. Open items resolved in plan, not spec

- Exact Coneria shop interior `mapId` and shopkeeper `localX/Y` — discovered at runtime by `DiscoverShop`, persisted as landmark `note`.
- Field menu button binding (B vs START) for `EquipWeapon` — verified during plan Task 1 (RAM probe also dumps button-press effects).
- Default `weaponSlotByChar` mapping (slot 0 → char 1 etc.) — placeholder; first probe run + `WrongClass` retries refine. Validation runs document the converged mapping.
- Inn-style "already-full / no-need" branch equivalent for shops — N/A (purchase succeeds or fails by RAM signal; no analogous branch).
