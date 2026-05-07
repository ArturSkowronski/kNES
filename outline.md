# Geecon 2026 — Advisor Agent gra w Final Fantasy

**Format:** 45 min talk + ~10 min Q&A
**Architectural through-line:** Anthropic's **Orchestrator-Workers / Advisor pattern** (z „Building Effective Agents", Dec 2024) jako kręgosłup całego systemu, evolved przez trzy iteracje.
**Goal:** dotrzeć do Garlanda — boss Chaos Shrine, czyli `Battle.enemyId == 0x7C`. **Honest cliffhanger:** mam architekturę i agent dochodzi do landmarków, ale Garland jeszcze leży nietknięty.
**Repo:** github.com/ArturSkowronski/kNES (Kotlin + Koog + Anthropic + Gemini)

---

## Hook — „I, Garland, will knock you all down!" (0:00 – 2:00) | 2 min

- Slajd: Garland w Chaos Shrine. Cytat z gry: *„I, Garland, will knock you all down!"*
- Garland to **NIE** scripted bridge encounter (jak speedrun guide sugerują). To **boss interior dungeon — Chaos Shrine**.
- Setup celu talku:
  > „Mam 45 minut, żeby pokazać architekturę agenta, którego buduję od pół roku. Jest oparta o **Advisor pattern** od Anthropica. Mój agent nie pokonał jeszcze Garlanda — ale doszedł do Coneria Castle, znalazł Króla, zmapował peninsulę. I to jest historia o tym, jak nie zbudować **kontrolera**, tylko jak zbudować **strateg-wykonawca**."
- Trzy akty: **V1 Naive Advisor → V2 Skilled Advisor → V3 Persistent Advisor**.
- Demo loop start (`./gradlew :knes-agent:runExplorer` w tle przez cały talk).

---

## Akt I — Architektura: Advisor pattern (2:00 – 9:00) | 7 min ⭐ *to jest fundament całego talku*

### 1.1 Kontekst: kNES + Final Fantasy (1.5 min)
- Emulator NES w Kotlinie, fork vNES — pełny CPU 6502, PPU, PAPU, MMC1.
- Już istnieje REST API + MCP server. Claude Code potrafi grać przez MCP.
- Ja chcę **agenta in-process**, w Kotlinie, z biblioteką **Koog** od JetBrains.

### 1.2 Anthropic „Building Effective Agents" (2 min)
- W grudniu 2024 Anthropic opublikował taksonomię agentowych wzorców:
  - Augmented LLM, Prompt chaining, Routing, Parallelization
  - **Orchestrator-Workers** ← *to jest nasz pattern*
  - Evaluator-Optimizer, Agents
- **Orchestrator-Workers**: jeden LLM (orchestrator) **planuje i deleguje**, inne LLM-y (workers) **wykonują**. Orchestrator ma read-only widok stanu, workers mają pełne narzędzia.
- W naszej terminologii: **Advisor** = orchestrator (Opus, drogi, mądry, bez tools mutujących), **Executor** = worker (Sonnet/Haiku, tani, robi).
- Anthropic's Claude Plays Pokémon używa wariantu z dwoma LLM: główny + secondary do przeglądu knowledge base. To **ten sam pattern**, w innym kostiumie.

### 1.3 Dlaczego ten pattern dla gry? (1.5 min)
Slajd z porównaniem trzech podejść:
| Podejście | Problem |
|-----------|---------|
| Single LLM ReAct | Diverguje w analizie, nie commituje akcji (Yao 2022 §6) |
| RL agent (PokeRL) | Działa, ale dni training + nie używa wiedzy ludzkiej |
| **Orchestrator-Workers** | LLM planuje na poziomie `which skill`, scripted code wykonuje |

Plus-bonus: w Koog 0.5+ jest **native support** — `createAgentTool` pozwala jednemu agentowi wywoływać innego jako narzędzie. Advisor jest zarejestrowany w toolregistry executora pod nazwą `askAdvisor(reason)`.

### 1.4 Pokaż architekturę (2 min)
Slajd diagramu:
```
┌─────────────────────────────────────────────────┐
│  AgentSession (outer loop, RAM-driven)          │
│                                                 │
│  ┌─────────────────┐    consults    ┌────────┐  │
│  │ ExecutorAgent   │───────────────▶│Advisor │  │
│  │ • Sonnet/Haiku  │                │• Opus 4│  │
│  │ • Skills (full) │◀───────────────│• Read- │  │
│  │ • singleRun     │  returns plan  │  only  │  │
│  └─────────────────┘                └────────┘  │
│           │                              │      │
│           └──────── Koog ToolRegistry ───┘      │
└─────────────────────────────────────────────────┘
        │
        ▼
   EmulatorToolset (12 narzędzi: step, tap, getState, ...)
        │
        ▼
   kNES (CPU, PPU, RAM)
```
- **Trigger advisora** (z `AgentSession.kt`):
  1. Once at session start (initial plan).
  2. By executor via `askAdvisor("I'm stuck because...")`.
  3. By runtime watchdog: phase boundary (RAM detected) lub `idleTurns >= 20`.
- Advisor **NIGDY** nie mutuje stanu emulatora. `ReadOnlyToolset` ma tylko `getState` + `getScreen`.
- To jest invariant — naruszenie = utrata read-only contract = możliwe inconsistency state. Enforcowane na poziomie typu (`AdvisorAgent` nie dostaje `EmulatorToolset` w pełnej formie).

---

## Akt II — V1: Naive Advisor (9:00 – 18:00) | 9 min

### 2.1 Pierwsza implementacja Advisor pattern (2 min)
- Architektura V1:
  - **AdvisorAgent** = Opus 4, `single-shot`, read-only tools.
  - **ExecutorAgent** = Sonnet 4.5, **`reActStrategy`** (max 10 iter), 13 raw narzędzi (step, tap, sequence, press, release, ...).
- Advisor zwraca **numerowany plan tekstowy**, np.:
  ```
  1. Press START to leave title screen.
  2. Choose NEW GAME, name party with default names.
  3. Walk SOUTH from castle exit until worldY > 158.
  4. Walk EAST until you see the bridge tile.
  ```
- Executor dostaje plan + recent observation w prompcie, ma rzucać tool calls.

### 2.2 Live demo trace V1 (3 min)
- Pokaż prawdziwy `trace.jsonl`:
  ```
  iter 1: getState() → ram dump
  iter 2: "Let me analyze: goldLow=144 + goldMid×256 = 400 GP..."
  iter 3: getState() ← AGAIN
  iter 4: kolejny paragraf analizy
  ...
  iter 10: AIAgentMaxNumberOfIterationsReachedException
  ```
- **Executor nie nacisnął ani jednego przycisku.**
- Koszt: $20–50 za attempted run.

### 2.3 Diagnoza — Advisor był OK, Executor był zły (2 min)
Kluczowe rozróżnienie:
- **Advisor pattern jest poprawny.** Plan jest sensowny. Read-only contract działa.
- **Executor implementacja była zła.** `reActStrategy` bez external termination signal **diverguje** na long-horizon partially-observable taskach (Yao 2022 §6, Reflexion 2023 §4.1).
- W każdej turze model znajduje powód, żeby **zinspekować stan jeszcze raz**, zamiast naciskać przyciski. Reasoning > action bias.
- Anthropic's Claude Plays Pokémon ma to samo — 35 000 akcji do trzeciego badge'a, 140h compute. *„Stuck in Mt. Moon for 78 hours."*

### 2.4 Detour: research dossier (2 min)
- Trzy insighty z pełnego badania (`docs/superpowers/research/2026-05-01-llm-game-agents.md`):
  1. **Anthropic's harness ma 3 narzędzia**: `update_knowledge_base`, `use_emulator`, `navigator`. Hershey: *„I've been stripping complexity out over time."*
  2. **Voyager — skill library**: GPT-4 + reusable code units = 15.3× szybszy tech-tree progress.
  3. **Macro vs Micro split**: LLM = makro decyzje, scripted Kotlin = mikro execution.
- **Lesson #1:** Advisor pattern to nie magia. Bez właściwego executora i właściwej granularity narzędzi, advisor produkuje plany, których executor nie potrafi wykonać.

---

## Akt III — V2: Skilled Advisor (18:00 – 28:00) | 10 min

### 3.1 Pięć precyzyjnych zmian (1 min — wstęp)
Slajd:
| # | Zmiana | Wpływ na Advisor pattern |
|---|--------|--------------------------|
| 1 | `singleRunStrategy` zamiast `reActStrategy` | Outer loop wraca do nas → advisor może być triggerowany deterministycznie |
| 2 | Voyager-style skill library | Advisor planuje na poziomie *skill names*, nie button presses |
| 3 | Long-lived `AnthropicLLMClient` + caching | Advisor system prompt (~2.5k tokenów) cached @ 10% price |
| 4 | Phase-aware model routing | Advisor zostaje na Opusie; executor schodzi na Haiku/Sonnet |
| 5 | Tool surface 13 → 7 | Advisor widzi mniejszą domenę do planowania |

### 3.2 Zmiana #1 — `singleRunStrategy` (1.5 min)
- Koog `singleRunStrategy()` = jeden LLM call → tool call lub text → koniec.
- Outer loop wraca do **mojego** `AgentSession`, gdzie mam RAM-driven phase detection.
- Pseudokod outer loopu:
  ```
  while not done:
    phase = observer.observe(ram)
    if phase changed OR idleTurns >= 20:
      currentPlan = advisor.plan(phase, observation)   // ← Advisor trigger
    result = executor.invoke(currentPlan, phase)        // single tool call
    update budget, idle counter
  ```
- **Advisor jest triggerowany deterministycznie**, nie kiedy executor sam zdecyduje.

### 3.3 Zmiana #2 — Skill library, Advisor zmienia językowy poziom (3 min) ⭐ *najważniejsze*
- Skill interface (z `knes-agent/src/main/kotlin/knes/agent/skills/Skill.kt`):
  ```kotlin
  interface Skill {
      val id: String
      val description: String
      suspend fun invoke(args: Map<String,String>): SkillResult
  }
  ```
- Pierwsze skille:
  - `pressStartUntilOverworld` — tap START aż `bootFlag=0x4D`
  - `walkOverworldTo(x, y)` — BFS pathfinding na overworld, aborts on encounter
  - `exitInterior` — BFS to nearest interior exit (~13% step success on town overlays)
  - `exploreInteriorFrontier` — BFS do nearest unvisited tile, persists w `InteriorMemory`
  - `battleFightAll` — 4× FIGHT loop
  - `findPath` / `findPathToExit` — read-only path queries
- **Każdy skill = scripted Kotlin code, ZERO tokenów LLM w środku.**
- Advisor teraz produkuje plany na poziomie skill calls:
  ```
  1. pressStartUntilOverworld
  2. walkOverworldTo(146, 152)   ← Coneria Castle entry
  3. exploreInteriorFrontier      ← do King's throne room
  4. exitInterior
  5. walkOverworldTo(...)         ← Chaos Shrine
  ```
- **To jest critical insight:** advisor pattern działa o tyle, o ile worker ma narzędzia w odpowiedniej granularity. Surowe `step([RIGHT], 16)` = za nisko. `walkOverworldTo` = w sam raz.

### 3.4 Zmiana #3 — Prompt caching (1.5 min)
- V1 grzech: `AnthropicLLMClient` budowany **per call**.
- Advisor system prompt to ~2.5k tokenów (FF1 game knowledge, skill repertoire, ASCII map convention).
- Pokaż na slajdzie fragment realnego advisor prompt z kodu (`AdvisorAgent.kt`):
  ```
  GROUND TRUTH ONLY (V5.21): your ONLY trustworthy sources are
    (1) the RAM dump,
    (2) the ASCII WORLD VIEW / interior map block,
    (3) the screenshot if you call getScreen.
  FF1 coordinates from your training data are UNRELIABLE...
  ```
- Cache breakpoint na końcu system prompt → **5–10× input cost reduction** na multi-turn.

### 3.5 Zmiana #4 — Model routing + Advisor zostaje na Opusie (1.5 min)
- Tabela:
  | Phase | Executor | Advisor |
  |-------|----------|---------|
  | TitleOrMenu, NameEntry | Sonnet 4.5 | **Opus 4** |
  | Overworld, Battle | Haiku 4.5 | Sonnet 4.5 |
  | Watchdog escalation | n/a | **Opus 4** |
- **Advisor zostaje na Opusie** dla najtrudniejszych sytuacji. Diagnoza *„dlaczego utknąłem"* to najdroższy cognitive load — wart $15/MTok.
- Haiku jako executor **15× tańszy niż Opus**. Skoro skill name to oczywisty wybór w Battle, nie ma sensu palić Opusem.

### 3.6 Zmiana #5 + V2 wynik (1.5 min)
- Tool surface 13 → 7 dla Koog (raw `step/tap/sequence` ukryte za skillami).
- **Cost: $20–50 → ~$3 / run** (target z V2 spec, nie measured).
- **Iteration cap exceptions: zero.**
- Advisor produkuje sensowne plany. Executor je wykonuje. Pipeline architektonicznie poprawny.
- **Ale: Garland nadal nieosiągnięty.** Agent kręci się po Coneria peninsuli, tracąc budget na rediscovery.

---

## Akt IV — V3: Persistent Advisor + Cross-Run Explorer (28:00 – 39:00) | 11 min ⭐ *klimaks*

### 4.1 Druga ściana — discovery cost (1.5 min)
- Patrzę na traces V2. Każda iteracja Opusa kosztuje **$1–2 na odkrywanie 1–2 warp tiles** w południowej Coneria peninsuli.
- Niektóre warpy → mapId=0 trap (uszkodzony stan emulatora).
- Każdy run zaczyna od zera. Zero cross-session memory.
- **Advisor traci pieniądze na to, co już raz wiedział.**
- **Lesson #2:** Advisor pattern bez persistent memory = goldfish-orchestrator. Każde nowe uruchomienie = pełny re-discovery cost.

### 4.2 Insight — rozdzielić Discover od Execute (2 min)
- Dwa procesy, dwa gradle taski:
  ```bash
  ./gradlew :knes-agent:runExplorer   # Phase 1: TANI Haiku, mapuje świat
  ./gradlew :knes-agent:run           # Phase 2: Advisor + Executor, używa wiedzy
  ```
- **Phase 1 (Explorer) NIE używa Advisor pattern.** Zamiast tego: deterministic salience strategy + Haiku triggers tylko na wyjątkowe momenty (nowe wnętrze, dialog).
- **Phase 2 (AgentSession) NADAL używa Advisor pattern.** Ale teraz advisor **dostaje cross-session memory w prompcie** przez `LandmarkContext.render(landmarkMemory)`.
- Interfejs: **JSON files na dysku.** Nic więcej. Phase 1 pisze, Phase 2 czyta.

### 4.3 Persistent memory — 5 plików (2 min)
```
~/.knes/
├── ff1-overworld-terrain.json   — mapa terenu (PLAINS, FOREST, TOWN, CASTLE)
├── ff1-landmarks.json           — NPC król, sklepikarz, schody, z mapId
├── ff1-blockages.json           — append-only LOG PORAŻEK
├── ff1-overworld-warps.json     — znane przejścia overworld → interior
└── ff1-interior-memory.json     — mapId → set odwiedzonych kafli
```
- **Atomic writes** (`AtomicJsonWriter`, PR #114): `write *.tmp + fsync + rename`. Bez tego JVM crash mid-write zostawiał `{}` i traciłem całą kampanię.
- Memory **rośnie monotonicznie** między runs. **Advisor nigdy nie zapomina.**

### 4.4 Phase 1 inner loop — LLM jako trigger, nie sterowanie (2 min)
Slajd z prawdziwego `SingleRun.kt`:
```kotlin
while (stepsTaken < 200) {
    val phase = observer.observeWithVision()
    checkRestart(ram, phase)?.let { return RunResult(it) }   // hard rule

    when (val trigger = detectTrigger(phase, ram)) {
        is NewInteriorEntered -> handleNewInterior(trigger)   // Haiku call
        is DialogBoxVisible   -> handleDialog(trigger)        // Haiku call
        is BattleEntered      -> battleFightAll()             // skript, no LLM
        null                  -> deterministicStep(phase)     // skript, no LLM
    }
}
```
- **90%+ kroków: zero LLM calls.**
- Cała kampania (10–20 runs): **<$1**. Realnie zmierzone.
- Salience strategy (5 priorytetów) decyduje co eksplorować — nie LLM:
  ```
  P0: closest known warp not yet entered this run
  P1: unvisited known landmarks
  P2: salient viewport tiles (TOWN/CASTLE not recorded)
  P3: nearest unmapped frontier
  P4: cross-run diversification
  P5: wander
  ```
- **Blockage feedback loop**: failed `walkOverworldTo` → blockage entry → następny target ten tile filtruje.

### 4.5 Phase 2 — Advisor czyta cross-session memory (2.5 min) ⭐
- W `AgentSession.kt:96`:
  ```kotlin
  val landmarkContext: String? = LandmarkContext.render(landmarkMemory)
  ```
- Advisor dostaje w obserwacji:
  ```
  KNOWN LANDMARKS (from explorer):
    - NPC_KING at mapId=24 (Coneria Castle throne, near worldX=146, worldY=152)
    - TOWN_ENTRY at worldX=147, worldY=154 (Coneria Town, mapId=8)
    - NPC_SHOPKEEPER at mapId=8 localX=14 localY=15
  KNOWN WARPS (auto-blocked in pathfinder):
    - (145, 152), (147, 153), (147, 154), (144, 153)
  ```
- Live evidence z HANDOFF.md:
  > *„`runAgent` trace w `~/.knes/runs/.../trace.jsonl`: rendered landmark block w advisor input, advisor output references (146,152) + 'castle with King/throne room' verbatim."*
- **To jest evolution Advisor patternu**: Advisor już nie planuje od zera. Planuje *„jak najszybciej dotrzeć do znanych celów"*. Persistent memory w prompt → advisor decisions w 1–2 turach zamiast 10–20.

### 4.6 Goal redefined: Garland w Chaos Shrine (1 min)
- Z system prompt advisora (slajd-screenshot z `AdvisorAgent.kt:188-192`):
  ```
  Goal: AtGarlandBattle = Battle.enemyId == 0x7C.
  Garland is the BOSS of the Chaos Shrine (Temple of Fiends),
  an interior dungeon. He is NOT a scripted bridge encounter.
  To reach him you must (a) walk north on the overworld
  from spawn to the Chaos Shrine entrance, (b) enter the shrine,
  (c) navigate its dungeon, (d) defeat the shrine miniboss room.
  ```
- **Honest cliffhanger:** mam advisor + persistent landmarks + Coneria zmapowana. Garland to dungeon położony na NORTH od spawn. Architektura jest gotowa, ale eksploracja Chaos Shrine to praca, której jeszcze nie zrobiłem.

---

## Akt V — War stories: gdzie architektura spotkała rzeczywistość (39:00 – 43:00) | 4 min

### 5.1 Advisor halucynuje koordynaty (1 min)
- Pierwsza wersja advisor system prompt nie miała "GROUND TRUTH ONLY" guardrail.
- Advisor confidently produkował plany typu *„go WEST to x=140"* — z training data memory of FF1.
- Iter 1 + iter 2 evidence: planowana grass corridor at x=140 prowadziła party prosto w ukryty interior entry (145, 152), dwa razy z rzędu.
- Fix: `GROUND TRUTH ONLY` paragraph w system prompt (`AdvisorAgent.kt:103-114`):
  > *„FF1 coordinates from your training data are UNRELIABLE. NEVER cite a specific entry-tile coordinate unless you can SEE the C/T glyph at that exact tile in the ASCII map."*
- **Lesson:** Advisor jest tak dobry, jak jego ground-truth contract.

### 5.2 Haiku halucynuje schody (1 min)
A/B Haiku 4.5 vs Gemini 2.5 Pro na klasyfikacji wnętrz:
| Ekran | Haiku 4.5 | Gemini 2.5 Pro |
|-------|-----------|-----------------|
| mapId=0 void | 4 false positives | `[]` (poprawnie) |
| Castle throne | NPC_KING ✓ + STAIRS_DOWN ✗ | NPC_KING ✓ |

- 6× cost ratio. Absolute differential trywialny ($0.05 vs $0.30 / 50-run kampanii).
- Switch via env var: `KNES_VISION=gemini-pro`.

### 5.3 mapId=0 trap + atomic saves (1 min)
- mapflags=1 + mapId=0 = uszkodzony interior stan, agent w pustym voidzie.
- PR #113: bail immediately on detection (oszczędność ~80 frames per trap).
- PR #114: atomic JSON saves. 3× w jeden dzień JVM padło mid-write i `landmarks.json` zostawał `{}`. Cała kampania utracona.
- **Lesson:** persistent state w long-running agentach to ta sama kategoria ryzyka co baza danych.

### 5.4 V5.2 input-frame drop (1 min)
- Po `loadState`: agent próbuje `step(["RIGHT"], 16)` → postać stoi.
- Diagnoza: emulator gubi pierwsze ~30 frames inputu po loadState.
- Fix (PR #109): post-loadState 30-frame NOOP warm-up.
- **Lesson:** Twój agent jest tak deterministic, jak deterministic jest twoja substruktura. Quirki emulatora propagują się w decyzje LLM.

---

## Akt VI — 10 lessons learned + Advisor pattern takeaways (43:00 – 44:30) | 1.5 min

Slajd-zdjęcie:

**O architekturze:**
1. **Advisor pattern (Orchestrator-Workers) działa** — ale orchestrator i worker muszą mieć **różne uprawnienia**. Read-only contract dla advisora to inwariant, nie sugestia.
2. **Granularity narzędzi decyduje o sukcesie patternu.** Advisor planuje na poziomie skill names, nie button presses.
3. **Persistent memory to evolution, nie dodatek.** Advisor bez cross-session memory płaci za rediscovery każdego runa.
4. **Discovery jako oddzielna faza.** Tani model + heurystyki + cumulative memory. Drogi advisor dopiero gdy cel = wykonać znany plan.

**O implementacji:**
5. **Nie pozwól LLM wybierać iteration count.** `singleRunStrategy` zamiast `reActStrategy`.
6. **Macro/micro split.** LLM = co. Scripted code = jak.
7. **Triggers zamiast steady-state inference.** 90% kroków bez LLM.

**O produkcji:**
8. **Vision backend hallucinations są realne.** A/B test.
9. **Atomic disk writes from day one.** Long-running agents PADAJĄ.
10. **Caching multiplikuje wszystko.** System prompt advisora = ~2.5k tokenów × N turn = leży w prefiksach.

---

## Zamknięcie (44:30 – 45:00) | 30 sek

> Garland nadal stoi w Chaos Shrine. Mój agent doszedł do throne roomu Króla Coneria, zmapował peninsulę, zna swoje warpy i swoje porażki. Architektura — Advisor + Executor + persistent memory + skill library — jest gotowa.
>
> *„Agentowe programowanie w 2026 nie polega na zwiększaniu mocy LLM-a. Polega na wybudowaniu architektury, w której orchestrator wie tyle, ile naprawdę musi wiedzieć, worker robi tylko to, co naprawdę musi robić, i cała wiedza odkryta drogą żyje dłużej niż jedna sesja."*
>
> Repo: github.com/ArturSkowronski/kNES. Garland leci w kolejnej sesji. **Pytania.**

---

## Q&A pre-prep (10 min, najczęstsze pytania)

- *„Czemu advisor wraca tekst, nie strukturę?"* — Plain text + ASCII map = LLM-friendly. Strukturalny output (JSON skill calls) testowałem, model gorzej rozumuje. Tekst → executor parsuje pierwszą `walkOverworldTo(x,y)` linię.
- *„Czy nie da się tego rozwiązać jednym Opusem z dużym prompt?"* — Można. Ale (a) drogo per turn, (b) read-only contract advisora to safety boundary. Opus z `step([RIGHT], 16)` raz na milion turn wybierze przypadkowy `reset()`. Separacja eliminuje tę kategorię ryzyka.
- *„Czy to bije RL?"* — Nie. PokeRL (10M params, czyste RL) bije każdy LLM-bot na speed i cost. LLM agenty mają inny use case: zero-shot, zero training data, dialog-friendly.
- *„Czemu Koog a nie LangChain?"* — JVM-native, typed `@Tool` przez reflection, `createAgentTool` natywnie, kompiluje się z całym monorepo.
- *„Co dalej?"* — (1) Phase 2 z prawdziwą walką z Garlandem. (2) Voyager-style automatic skill authoring. (3) Reflexion-style self-criticism w advisorze (when stuck, write a paragraph about why, inject into next turn).

---

## Slajdy / asset checklist

- [ ] Hook: Garland w Chaos Shrine + cytat „I, Garland..."
- [ ] Anthropic Building Effective Agents diagram (Orchestrator-Workers)
- [ ] kNES architecture diagram
- [ ] Advisor + Executor architecture diagram (z naszego kodu)
- [ ] V1 trace.jsonl fragment (analiza RAM zamiast button presses)
- [ ] Tabela 5 zmian V2
- [ ] Skill interface code (`Skill.kt`)
- [ ] Advisor system prompt fragment (`AdvisorAgent.kt`, "GROUND TRUTH ONLY")
- [ ] Pseudokod outer loop V2
- [ ] V2 cost target ($20-50 → $3)
- [ ] Persistent memory diagram (5 JSON files)
- [ ] Salience strategy 5-priority pseudokod
- [ ] LandmarkContext.render output (advisor input fragment)
- [ ] Goal paragraph z `AdvisorAgent.kt:92-96` (Garland w Chaos Shrine)
- [ ] A/B Haiku vs Gemini tabela
- [ ] Atomic save bug screenshot
- [ ] 10 lessons learned (slajd-zdjęcie)

## Demo skrypt

```bash
# Przed talkiem
cp -r ~/.knes ~/.knes.backup-pre-talk
ANTHROPIC_API_KEY=$KEY ./gradlew :knes-agent:runExplorer &  # explorer w tle

# W trakcie Aktu IV
jq '.landmarks | length' ~/.knes/ff1-landmarks.json
jq -r '.landmarks[] | "\(.kind) @ mapId=\(.mapId)"' ~/.knes/ff1-landmarks.json

# Pod koniec talku — pokaż advisor input z ostatniego runa
jq -r 'select(.role == "advisor") | .input' \
   ~/.knes/runs/$(ls -t ~/.knes/runs | head -1)/trace.jsonl | head -50
```

## Backup slajdy (jeśli zostanie czas)

- Koog `createAgentTool` API — jak technicznie advisor jest zarejestrowany w executor toolregistry
- Anthropic prompt caching breakpointy — gdzie postawić w naszym layout
- FF1 RAM map fragment (TASvideos) — `bootFlag`, `worldX/Y`, `screenState`
- Porównanie z Claude Plays Pokémon (knowledge base = nasze persistent JSON)
- ASCII map renderer output — co advisor naprawdę widzi (`AsciiMapRenderer.render`)
