---
marp: true
theme: default
paginate: true
size: 16:9
header: 'Advisor Agent gra w Final Fantasy · Artur Skowroński · Geecon 2026'
footer: 'github.com/ArturSkowronski/kNES'
style: |
  section { font-size: 26px; }
  section.title { font-size: 36px; }
  section.divider { background: #1a1a1a; color: white; font-size: 60px; }
  code { font-size: 0.85em; }
  pre { font-size: 0.85em; }
  table { font-size: 0.8em; }
  h1 { color: #2b6cb0; }
  h2 { color: #2b6cb0; border-bottom: 2px solid #2b6cb0; }
---

<!-- _class: title -->

# Advisor Agent gra w Final Fantasy

## Jak Anthropic's Orchestrator-Workers pattern dochodzi do Garlanda

**Artur Skowroński** · Geecon 2026

`github.com/ArturSkowronski/kNES`

<!--
Cześć! Mam 45 minut. Pokażę Wam architekturę agenta zbudowanego wokół wzorca Anthropic'a, który gra w Final Fantasy 1 na NES-ie. Mówię od razu: agent Garlanda nie pokonał. Ale doszedł do throne roomu Króla Coneria, zmapował peninsulę. To historia o architekturze, nie o ostatecznej wygranej.
-->

---

# „I, Garland, will knock you all down!"

![bg right:40% 90%](placeholder-garland.png)

- Garland — boss **Chaos Shrine** (Temple of Fiends)
- **NIE** scripted bridge encounter
- Cel agenta: `Battle.enemyId == 0x7C`
- Speedrunner: ~5 min od boota
- Mój agent: jeszcze nie

<!--
Garland to nie jest ten boss przy moście, jak pamięta wielu speedrunnerów — to boss interior dungeon. Żeby do niego dotrzeć, agent musi przejść overworld na północ od spawn, znaleźć wejście do Chaos Shrine, przejść dungeon, dojść do bossroomu. Każdy z tych kroków to inne wyzwanie architektoniczne — i o tym jest ten talk.
-->

---

# Trzy akty

1. **V1 — Naive Advisor** (~$30/run, nie nacisnął przycisku)
2. **V2 — Skilled Advisor** (~$3/run, dochodzi do peninsuli)
3. **V3 — Persistent Advisor** (~$1/kampania, zna mapę cross-session)

## Plus war stories i 10 lessons

<!--
Trzy akty + war stories + lessons. Każdy akt to jedna ewolucja tego samego wzorca: Advisor pattern od Anthropic'a. Pokażę co się zmieniło, dlaczego, ile to kosztowało, i co z tego dla Was wynika.
-->

---

<!-- _class: divider -->

# Akt I

## Architektura: Advisor pattern

---

# kNES — emulator NES w Kotlinie

- Fork vNES, full port: CPU 6502, PPU, PAPU, MMC1
- 400+ testów (instrukcje, nestest.nes, SMB E2E)
- KotlinConf 2025: *„Build your own NES Emulator with Kotlin"*
- REST API + MCP server → Claude Code już potrafi grać

**Dziś chcę agenta in-process, w Kotlinie, z Koog.**

<!--
Kontekst: kNES to mój side project. Pełny emulator NES, MMC1 czyli Final Fantasy działa. Mam REST API i MCP, więc Claude Code potrafi grać przez MCP. Ale to jest Claude jako klient. Ja chcę agenta in-process, w Kotlinie, z biblioteką Koog od JetBrains.
-->

---

# Anthropic „Building Effective Agents" (12/2024)

| Pattern | Use case |
|---------|----------|
| Augmented LLM | Single LLM + tools |
| Prompt chaining | Sequential tasks |
| Routing | Classify → dispatch |
| Parallelization | Independent subtasks |
| **Orchestrator-Workers** ⭐ | **Plan + delegate** |
| Evaluator-Optimizer | Iterative refinement |
| Agents | Open-ended exploration |

<!--
W grudniu 2024 Anthropic opublikował taksonomię agentowych wzorców. Najistotniejszy dla nas: Orchestrator-Workers. Jeden LLM planuje i deleguje, inne LLM-y wykonują. To jest wzorzec, na którym buduję cały system.
-->

---

# Orchestrator-Workers = Advisor pattern

```
   ┌──────────────┐         ┌──────────────┐
   │  ORCHESTRATOR│ delegates│   WORKER     │
   │  (Advisor)   │─────────▶│  (Executor)  │
   │              │          │              │
   │  • Plans     │  reports │  • Acts      │
   │  • Read-only │◀─────────│  • Full tools│
   │  • Expensive │          │  • Cheap     │
   └──────────────┘          └──────────────┘
```

- **Advisor** → Opus, read-only, drogi
- **Executor** → Sonnet/Haiku, full tools, tani
- Anthropic's Claude Plays Pokémon używa wariantu z 2 LLM

<!--
Mapowanie: orchestrator to Advisor, worker to Executor. Advisor jest mądry i drogi, ale ma read-only widok stanu. Executor jest tani i ma pełne narzędzia. Anthropic's własny Claude Plays Pokémon używa tego samego wzorca, tylko inaczej nazwany.
-->

---

# Dlaczego ten pattern dla gry?

| Podejście | Problem |
|-----------|---------|
| Single LLM ReAct | Diverguje, nie commituje akcji (Yao 2022) |
| Pure RL (PokeRL) | Działa, ale tygodnie trainingu |
| **Orchestrator-Workers** | LLM = makro, scripted Kotlin = mikro |

**Bonus:** Koog ma `createAgentTool` natywnie — advisor jest zarejestrowany w toolregistry executora pod `askAdvisor(reason)`.

<!--
Single LLM ReAct diverguje — pokażę za chwilę. Pure RL działa, ale to inna kategoria projektu. Orchestrator-Workers daje nam clean separation. I co najlepsze: Koog ma to natywnie — jeden agent może być narzędziem drugiego.
-->

---

# Nasza architektura

```
┌─────────────────────────────────────────────────┐
│  AgentSession (outer loop, RAM-driven)          │
│                                                 │
│  ┌─────────────────┐  consults   ┌────────┐    │
│  │ ExecutorAgent   │────────────▶│Advisor │    │
│  │ • Sonnet/Haiku  │             │• Opus 4│    │
│  │ • Skills (full) │◀────────────│• Read- │    │
│  │ • singleRun     │ plan text   │  only  │    │
│  └─────────────────┘             └────────┘    │
│           │                                     │
│           └──── EmulatorToolset (12 tools)──┐  │
└─────────────────────────────────────────────┴──┘
                                              │
                                              ▼
                              kNES (CPU, PPU, RAM)
```

Triggery advisora: `(1)` start sesji · `(2)` `askAdvisor` z executora · `(3)` watchdog (phase change / `idleTurns >= 20`)

<!--
Outer loop u nas — w AgentSession. Executor robi single tool call per LLM invocation. Advisor jest triggered w trzech sytuacjach: na początku sesji, kiedy executor sam o niego prosi, albo deterministycznie z watchdoga, kiedy wykryjemy zmianę fazy lub bezruch.
-->

---

<!-- _class: divider -->

# Akt II

## V1: Naive Advisor

---

# V1 — pierwsza implementacja

```kotlin
// Advisor — Opus, read-only
val advisor = AIAgent(
    promptExecutor = anthropic.executor,
    llmModel = AnthropicModels.Opus_4,
    toolRegistry = ToolRegistry { tools(ReadOnlyToolset(toolset)) },
    strategy = singleRunStrategy(),
    systemPrompt = ADVISOR_PROMPT,
)

// Executor — Sonnet, reActStrategy ⚠
val executor = AIAgent(
    promptExecutor = anthropic.executor,
    llmModel = AnthropicModels.Sonnet_4_5,
    toolRegistry = ToolRegistry { tools(toolset) },  // 13 raw tools
    strategy = reActStrategy(maxIterations = 10),    // ⚠
    systemPrompt = EXECUTOR_PROMPT,
)
```

<!--
V1 architektura. Advisor — singleRunStrategy, read-only, plan text out. Executor — reActStrategy z max 10 iteracjami. To wydawało się idealne. Ten executor to było źródło wszystkich problemów. Pokażę za chwilę dlaczego.
-->

---

# Advisor produkuje sensowne plany

```
Plan from Advisor (Opus):
1. Press START to leave title screen.
2. Choose NEW GAME, name party with default names.
3. Walk SOUTH from castle exit until worldY > 158.
4. Walk EAST until you see the bridge tile in the viewport.
5. If encounter triggers, fight with default actions.
```

✅ Pattern działa. Plan jest sensowny. Read-only contract trzymany.

**Problem nie po stronie advisora.**

<!--
Advisor produkuje sensowne plany. Pattern działa, contract jest trzymany. Problem nie był nigdy po stronie advisora — był po stronie executora.
-->

---

# Executor V1 — live trace

```jsonl
iter 1: getState() → ram dump
iter 2: "Let me analyze: goldLow=144 + goldMid×256 = 400 GP.
        The party has 100/100 HP. screenState=0x00..."
iter 3: getState() ← AGAIN
iter 4: "Looking at this more carefully: bootFlag is 0x4D
        which means we're in-game. The worldX=146..."
iter 5: getScreen() → image
iter 6: "The screenshot confirms we're on the overworld..."
...
iter 10: AIAgentMaxNumberOfIterationsReachedException
```

# **Executor nie nacisnął ani jednego przycisku.**

<!--
Pokazuję realny trace. Executor wywoływał getState w nieskończoność, produkował paragrafy analizy RAM-u, ale nigdy nie nacisnął przycisku. Po 10 iteracjach Koog rzucał exception. Cała tura — zero progresu w grze.
-->

---

# V1 metryki

| Metryka | Wartość |
|---------|---------|
| Koszt per attempted run | **$20–50** |
| Wall clock | timeout w 9 min |
| Akcji w grze | często 0 |
| Iteration cap exceptions | każda tura |
| Garland | nigdy |

<!--
Liczby. $20-50 za jedną próbę dotarcia do Garlanda. Timeout w 9 minut. Często zero akcji w grze. Każda tura kończyła iteration cap exception. To nie jest agent — to jest analytics pipeline z bugiem.
-->

---

# Diagnoza — Advisor OK, Executor zły

**Yao 2022 (ReAct paper) §6:**
> *„ReAct without external termination signal **diverges** on long-horizon partially-observable tasks."*

**Reflexion 2023 §4.1:** to samo, niezależnie potwierdzone.

**Anthropic Claude Plays Pokémon:** *„Stuck in Mt. Moon for 78 hours."*

## To nie jest prompt problem. To jest **architectural problem**.

<!--
ReAct bez external termination signal diverguje. Yao to opisał, Reflexion potwierdził, Anthropic obserwuje to samo na żywo na Twitchu. Nie da się tego naprawić promptem. Trzeba zmienić architekturę inner loopu.
-->

---

# Detour: research dossier

**3 insighty z literatury:**

1. **Anthropic's harness ma 3 narzędzia.** `update_knowledge_base` + `use_emulator` + `navigator`. Hershey: *„I've been stripping complexity out over time."*

2. **Voyager (NVIDIA, 2023):** skill library = 15.3× szybszy tech-tree progress.

3. **Macro vs Micro split:** LLM = makro. Scripted code = mikro. *Zawsze.*

<!--
Zatrzymałem się i zrobiłem porządny research. Trzy najważniejsze insighty: Anthropic upraszcza nie rozbudowuje. Voyager pokazał że skill libraries dają ogromny boost. I uniwersalna prawda: LLM nadaje się do makro decyzji, nie do mikro execution.
-->

---

<!-- _class: divider -->

# Akt III

## V2: Skilled Advisor

---

# V2 — pięć precyzyjnych zmian

| # | Zmiana | Wpływ na Advisor pattern |
|---|--------|--------------------------|
| 1 | `singleRunStrategy` zamiast `reActStrategy` | Outer loop u nas → advisor triggered deterministycznie |
| 2 | Voyager-style skill library | Advisor planuje na **skill names**, nie button presses |
| 3 | Long-lived client + caching | System prompt advisora cached @ 10% price |
| 4 | Phase-aware model routing | Advisor zostaje na Opusie; executor schodzi na Haiku |
| 5 | Tool surface 13 → 7 | Advisor widzi mniejszą domenę do planowania |

<!--
Pięć zmian. Każda dotyka inaczej naszego patternu. Ale wszystkie razem zachowują Advisor + Executor split — tylko go ulepszają.
-->

---

# #1 — `singleRunStrategy`

```kotlin
val executor = AIAgent(
    strategy = singleRunStrategy(),  // ← zmiana
    // ...
)
```

**Jeden LLM call → tool call lub text → koniec.**

```
while not done:
    phase = observer.observe(ram)
    if phase changed OR idleTurns >= 20:
        currentPlan = advisor.plan(phase, observation)   // ← TRIGGER
    result = executor.invoke(currentPlan, phase)         // ← single tool
    update budget, idle counter
```

Outer loop wraca do nas. Iteration cap exceptions: **eliminated**.

<!--
singleRunStrategy = jeden LLM call per agent.run. Outer loop wraca do mnie — i to jest dobrze, bo i tak miałem RAM-driven phase detection. Teraz advisor jest triggered w precyzyjnie zdefiniowanych momentach.
-->

---

# #2 — Skill library

```kotlin
interface Skill {
    val id: String
    val description: String
    suspend fun invoke(args: Map<String, String>): SkillResult
}
```

| Skill | Co robi |
|-------|---------|
| `pressStartUntilOverworld` | tap START aż `bootFlag=0x4D` |
| `walkOverworldTo(x, y)` | BFS pathfinding, aborts on encounter |
| `exitInterior` | BFS to nearest exit |
| `exploreInteriorFrontier` | BFS do unvisited tile + persists |
| `battleFightAll` | 4× FIGHT loop |
| `findPath(x, y)` | read-only path query |

**Każdy skill = scripted Kotlin. ZERO tokenów LLM w środku.**

<!--
Skill to scripted Kotlin code. Zero tokenów LLM w środku. To jest kluczowa zmiana w wzorcu: poziom abstrakcji narzędzi advisor planuje teraz na skill names, nie na button presses.
-->

---

# Advisor zmienia językowy poziom

**Przed (V1):**
```
Plan: walk SOUTH 8 tiles, then EAST 12 tiles, watching for encounter
Executor: step([DOWN], 16), getState, step([DOWN], 16), getState, ...
```

**Po (V2):**
```
Plan:
  1. pressStartUntilOverworld
  2. walkOverworldTo(146, 152)        ← Coneria Castle entry
  3. exploreInteriorFrontier          ← do King's throne
  4. exitInterior
  5. walkOverworldTo(...)             ← Chaos Shrine
```

Critical insight: **Advisor pattern działa o tyle, o ile worker ma narzędzia w odpowiedniej granularity.**

<!--
To jest może najważniejszy slajd talku. Advisor wcześniej musiał być pseudo-programistą — instrukcje z liczbami kroków. Teraz advisor planuje na poziomie celów. Worker (executor) wybiera skill name. Skill robi resztę. To jest właściwa granularity dla tego patternu.
-->

---

# #3 — Prompt caching

**V1 grzech:** `AnthropicLLMClient` budowany **per call** → 0% cache hit.

**Advisor system prompt z `AdvisorAgent.kt`:**
```
GROUND TRUTH ONLY (V5.21): your ONLY trustworthy sources are
  (1) the RAM dump,
  (2) the ASCII WORLD VIEW,
  (3) the screenshot if you call getScreen.
FF1 coordinates from your training data are UNRELIABLE...
```

~2.5k tokenów. Cache breakpoint na końcu → **5–10× redukcja input cost**.

ProjectDiscovery (prod): **59–70% input savings.**

<!--
V1 budował fresh klient per call. Każdy call to cold prompt. Advisor system prompt ma 2.5k tokenów — to wszystko płaciłem za każdym razem. Long-lived klient + cache breakpoint na końcu system prompt = 5-10x redukcja kosztu input.
-->

---

# #4 — Model routing, #5 — tool reduction

```
Phase                     Executor       Advisor
─────────────────────────────────────────────────
TitleOrMenu, NameEntry    Sonnet 4.5     Opus 4
Overworld, Battle         Haiku 4.5      Sonnet 4.5
Watchdog escalation       n/a            Opus 4
```

**Advisor zostaje na Opusie** dla najtrudniejszych decyzji (~$15/MTok).
**Haiku jako executor** = 15× tańszy.

**Tool surface 13 → 7**: raw `step/tap/sequence` ukryte za skillami. Advisor widzi tylko skill names.

<!--
Routing: tani Haiku do oczywistego, drogi Opus do trudnego. Advisor zostaje na Opusie kiedy musi diagnozować dlaczego utknęliśmy. To jest najdroższy cognitive load i jedyne miejsce, gdzie Opus naprawdę zarabia. Plus tool surface zmniejszony — advisor planuje w prostszej domenie.
-->

---

# V2 — wynik

| Metryka | V1 | V2 |
|---------|----|----|
| Koszt / run | $20–50 | **~$3** *(target)* |
| Iteration cap exceptions | każda tura | **zero** |
| Akcji w grze | często 0 | dziesiątki |
| Garland | nigdy | **nadal nie** |

Coniera peninsula: agent kręci się, traci budget na rediscovery.

**Architektura poprawna. Pattern poprawny. Ale brakuje pamięci.**

<!--
V2 wygrywa architektonicznie. Iteration cap exceptions zniknęły. Cost spadł rzędem wielkości. Ale Garland nadal nie pokonany — agent kręci się po Coneria peninsuli, tracąc budget na rediscovery. Każdy run zaczyna od zera.
-->

---

<!-- _class: divider -->

# Akt IV

## V3: Persistent Advisor

---

# Druga ściana — discovery cost

**Z traces V2:**
- każda iteracja Opusa = **$1–2** na odkrycie 1–2 warp tiles
- peninsula ma **6–8 warpów**
- niektóre warpy → mapId=0 trap (uszkodzony stan emulatora)
- każdy run zaczyna od zera

**Advisor traci pieniądze na to, co już raz wiedział.**

## Lesson #2: Advisor bez persistent memory = goldfish-orchestrator.

<!--
Druga ściana. Każda iteracja Opusa to $1-2 na rediscovery tych samych 1-2 warpów. Peninsula ma 6-8 warpów. Każdy run zaczyna od zera. Advisor jest goldfish: mądry w tej sesji, niczego nie pamięta na następną.
-->

---

# Insight — rozdzielić Discover od Execute

```bash
./gradlew :knes-agent:runExplorer   # Phase 1: TANI Haiku, mapuje
./gradlew :knes-agent:run           # Phase 2: Advisor + Executor
```

- **Phase 1 (Explorer) NIE używa Advisor pattern.** Deterministic salience strategy + Haiku triggers.
- **Phase 2 (AgentSession) NADAL używa Advisor pattern.** Ale advisor dostaje **cross-session memory** w prompcie.
- Interfejs między fazami: **JSON files na dysku.**

<!--
Insight: rozdzielić eksplorację od egzekucji. Phase 1 to tani explorer bez advisora — Haiku tylko gdzie naprawdę trzeba. Phase 2 to nasz Advisor pattern, ale z persistent memory. Interfejs między nimi to pliki na dysku.
-->

---

# Persistent memory — 5 plików

```
~/.knes/
├── ff1-overworld-terrain.json   PLAINS, FOREST, TOWN, CASTLE
├── ff1-landmarks.json           NPC król, sklepikarz, schody
├── ff1-blockages.json           append-only LOG PORAŻEK
├── ff1-overworld-warps.json     znane przejścia
└── ff1-interior-memory.json     mapId → odwiedzone kafle
```

**Atomic writes** (`AtomicJsonWriter`, PR #114):
`write *.tmp + fsync + rename`

Bez tego JVM crash mid-write zostawiał `{}` i traciłem całą kampanię.

**Memory rośnie monotonicznie.** Advisor nigdy nie zapomina.

<!--
Pięć plików. Atomic writes, bo bez tego trzykrotnie traciłem całą kampanię w jeden dzień. Memory rośnie monotonicznie — to jest serce V3. Cross-session knowledge przeżywa.
-->

---

# Phase 1 — LLM jako trigger, nie sterowanie

```kotlin
while (stepsTaken < 200) {
    val phase = observer.observeWithVision()
    checkRestart(ram, phase)?.let { return RunResult(it) }

    when (val trigger = detectTrigger(phase, ram)) {
        is NewInteriorEntered -> handleNewInterior(trigger)  // Haiku
        is DialogBoxVisible   -> handleDialog(trigger)       // Haiku
        is BattleEntered      -> battleFightAll()            // skript
        null                  -> deterministicStep(phase)    // skript
    }
}
```

**90%+ kroków: zero LLM calls.**
Cała kampania (10–20 runs): **<$1**. *Realnie zmierzone.*

<!--
Inner loop Phase 1. LLM włącza się tylko przy triggerach: nowe wnętrze, dialog box, walka idzie skryptem. 90% kroków bez żadnego LLM calla. Cała kampania mieści się pod dolarem — to zmierzone.
-->

---

# Salience strategy — 5 priorytetów

```
P0: closest known warp not yet entered this run
P1: unvisited known landmarks (TOWN/CASTLE)
P2: salient viewport tiles not yet recorded
P3: nearest unmapped frontier
P4: cross-run diversification (last 3 runs N → idź E)
P5: wander (fallback)
```

**Blockage feedback loop:**
failed `walkOverworldTo` → `Blockage` entry → następny target ten tile filtruje

**Cross-run diversification:** unika powtarzania kierunku startu

<!--
Salience strategy decyduje co eksplorować — nie LLM. Pięć priorytetów, deterministyczne. Plus dwa loopy: blockages (uczy się z niepowodzeń ścieżek) i diversification (jeśli ostatnie 3 runs poszły N, idź E). To jest wbudowane w salience, nie w LLM.
-->

---

# Phase 2 — Advisor czyta cross-session memory

`AgentSession.kt:96`:
```kotlin
val landmarkContext: String? = LandmarkContext.render(landmarkMemory)
```

**Advisor dostaje w obserwacji:**
```
KNOWN LANDMARKS (from explorer):
  - NPC_KING at mapId=24 (Coneria Castle, near worldX=146, worldY=152)
  - TOWN_ENTRY at worldX=147, worldY=154 (Coneria Town, mapId=8)
  - NPC_SHOPKEEPER at mapId=8 localX=14 localY=15
KNOWN WARPS (auto-blocked in pathfinder):
  - (145, 152), (147, 153), (147, 154), (144, 153)
```

<!--
Tu jest evolution Advisor patternu. Advisor już nie planuje od zera. Dostaje LandmarkContext w prompcie — wszystko, co explorer odkrył w poprzednich sesjach. Plus warpy, które są już zablokowane w pathfinderze.
-->

---

# Live evidence (HANDOFF.md, 2026-05-06)

> *„`runAgent` trace w `~/.knes/runs/.../trace.jsonl`: rendered landmark block w advisor input, advisor output references `(146,152)` + 'castle with King/throne room' verbatim."*

**Advisor decisions w 1–2 turach** zamiast 10–20.
Cross-session knowledge → ROI w prompt z dnia na dzień.

<!--
To nie jest spec. To jest evidence z prawdziwego trace.jsonl z wczoraj. Advisor dostał LandmarkContext, w outputie cytował dokładne koordynaty Króla. Pattern z V1 + persistent memory z V3 = decyzje w 1-2 turach zamiast 10-20.
-->

---

# Goal redefined: Garland w Chaos Shrine

Z `AdvisorAgent.kt:188-192`:

```
Goal: AtGarlandBattle = Battle.enemyId == 0x7C.
Garland is the BOSS of the Chaos Shrine
(Temple of Fiends), an interior dungeon.
He is NOT a scripted bridge encounter.

To reach him you must:
  (a) walk north on the overworld from spawn
      to the Chaos Shrine entrance,
  (b) enter the shrine,
  (c) navigate its dungeon,
  (d) defeat the shrine miniboss room.
```

**Honest cliffhanger:** architektura gotowa. Chaos Shrine — work pending.

<!--
Cel poprawnie zdefiniowany w prompt advisora. Garland to boss interior dungeon. Cztery kroki do niego. Mam architekturę, która wszystkie cztery powinna obsłużyć — advisor + skills + persistent memory. Ale ekspedycji do Chaos Shrine jeszcze nie zrobiłem. To jest następny milestone.
-->

---

<!-- _class: divider -->

# Akt V

## War stories

---

# Advisor halucynuje koordynaty

**Iter 1 + iter 2 evidence:**

> *„go WEST to x=140 first"* ← z training data memory of FF1

Plan prowadził party prosto w **ukryty interior entry (145, 152)**, dwa razy z rzędu.

**Fix:** `GROUND TRUTH ONLY` paragraph w system prompt:

```
NEVER cite a specific entry-tile coordinate unless
you can SEE the C/T glyph at that exact tile in the ASCII map.
```

**Lesson:** Advisor jest tak dobry, jak jego ground-truth contract.

<!--
Pierwsza wersja advisor nie miała ground-truth guardrail. Confidently produkował plany z training data — i prowadziły agenta prosto w trapy. Fix to było dodanie eksplicit reguły: zaufaj tylko temu, co widzisz w viewport. Lesson: advisor jest tak dobry jak jego ground-truth contract.
-->

---

# Haiku halucynuje schody

| Ekran | Haiku 4.5 | Gemini 2.5 Pro |
|-------|-----------|-----------------|
| mapId=0 void | **4 false positives** | `[]` ✓ |
| Castle throne | NPC_KING ✓ + STAIRS_DOWN ✗ | NPC_KING ✓ |

- 6× cost ratio ($0.001 vs $0.005-7 / call)
- Absolute differential trywialny: $0.05 vs $0.30 / 50-run kampanii
- **Switch via env var:** `KNES_VISION=gemini-pro`

**Lesson:** A/B vision backendy w produkcji.

<!--
Vision backend matters. Haiku confidently halucynuje schody, których nie ma. Gemini Pro precyzyjnie identyfikuje. 6x cost difference, ale w skali kampanii to centy. Precyzja warta więcej niż mikro-oszczędność. Switch via env var.
-->

---

# mapId=0 trap + atomic saves

**mapId=0 trap:** mapflags=1 + mapId=0 = uszkodzony interior void

- PR #113: bail immediately on detection (~80 frames saved per trap)
- PR #110: filter mapId=0 w auto-detected warpach

**Atomic saves:** PR #114, `AtomicJsonWriter`

- 3× w jeden dzień JVM padł mid-write
- `landmarks.json` zostawał `{}` → cała kampania utracona
- Fix: `write *.tmp + fsync + rename`

**Lesson:** persistent state w long-running agentach = baza danych.

<!--
mapId=0 trap to bug w emulatorze, ale agent musi sobie z nim radzić. Bail immediately + filter w warpach. Plus atomic saves — bo trzy razy w jeden dzień traciłem całą kampanię przez race condition. Persistent state w agentach to ta sama kategoria ryzyka co baza danych.
-->

---

# V5.2 input-frame drop

Po `loadState`:
```kotlin
emulator.loadState(savestate)
toolset.step(buttons = listOf("RIGHT"), frames = 16)
// → postać stoi
```

**Diagnoza:** emulator gubi pierwsze ~30 frames inputu po loadState.

**Fix (PR #109):** post-loadState 30-frame NOOP warm-up.

**Lesson:** Twój agent jest tak deterministic, jak deterministic jest substruktura. Quirki emulatora propagują w decyzje LLM.

<!--
Quirky bug emulatora — 30 frames inputu gubione po loadState. Bez tego, każdy run zaczynał się od „input not responding" i agent rzucał askAdvisor. Po fixie agent rusza od iteracji 1. Lesson: substruktura ma znaczenie. LLM podejmuje decyzje na podstawie obserwacji, jeśli obserwacje kłamią — decyzje są bzdurne.
-->

---

<!-- _class: divider -->

# Akt VI

## Lessons learned

---

# 10 lessons

**Architektura:**
1. **Advisor pattern działa** — ale orchestrator i worker muszą mieć **różne uprawnienia**
2. **Granularity narzędzi decyduje o sukcesie patternu** — skill names, nie button presses
3. **Persistent memory to evolution patternu, nie dodatek**
4. **Discovery jako oddzielna faza** — tani model + memory

**Implementacja:**

5. Nie pozwól LLM wybierać iteration count (`singleRunStrategy`)
6. Macro/micro split (LLM = co, scripted = jak)
7. Triggers zamiast steady-state inference (90% kroków bez LLM)

**Produkcja:**

8. Vision backend hallucinations są realne — A/B test
9. Atomic disk writes from day one
10. Caching multiplikuje wszystko — koszty leżą w prefiksach

<!--
Dziesięć lessonów. Pierwsze cztery o architekturze patternu, trzy o implementacji, trzy o produkcji. Slajd zostaje na zdjęcie. Nie czytam wszystkiego — wybieram 2-3 i komentuję krótko.
-->

---

<!-- _class: title -->

# Garland nadal stoi w Chaos Shrine

## Architektura jest gotowa.

**Advisor + Executor + Skills + Persistent Memory + 5 JSON files**

Repo: `github.com/ArturSkowronski/kNES`
Spec docs: `/docs/superpowers/`

**Pytania.**

<!--
Garland nadal stoi. Architektura jest gotowa — advisor, executor, skills, persistent memory, pięć plików JSON. Chaos Shrine to kolejny milestone, nie ostatni. Repo open-source, spec docs w środku, każda decyzja udokumentowana razem z porażką, która do niej doprowadziła. Pytania.
-->

---

<!-- _class: divider -->

# Q&A

---

# Najczęstsze pytania (backup)

**Q: Czemu advisor wraca tekst, nie strukturę?**
Plain text + ASCII map = LLM-friendly. Strukturalny output gorzej rozumuje.

**Q: Czemu nie jeden Opus z dużym promptem?**
(a) Drogo per turn. (b) Read-only contract advisora to safety boundary.

**Q: Czy to bije RL?**
Nie. PokeRL (10M params) bije każdy LLM-bot na speed. LLM = inny use case.

**Q: Czemu Koog a nie LangChain?**
JVM-native, typed `@Tool`, `createAgentTool` natywnie, monorepo build.

**Q: Co dalej?**
(1) Phase 2 z prawdziwym Garlandem. (2) Voyager-style auto skill authoring. (3) Reflexion-style self-criticism w advisorze.

<!--
Backup slajd na pytania. Mam tu pięć najczęstszych. Jeśli pytania będą inne — improwizuję.
-->

---

# Dziękuję!

**Artur Skowroński**

`github.com/ArturSkowronski/kNES`

Slides + spec docs + handoff notes w repo.

Każda decyzja udokumentowana razem z porażką, która do niej doprowadziła.
