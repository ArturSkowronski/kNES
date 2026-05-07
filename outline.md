# Geecon 2026 — Jak nauczyłem agenta pokonać Garlanda

**Format:** 45 min talk + ~10 min Q&A
**Cel narracyjny:** od pierwszego boota emulatora do Garlanda padającego od 4× FIGHT — i wszystkich architektonicznych decyzji po drodze.
**Repo:** github.com/ArturSkowronski/kNES (Kotlin + Koog + Anthropic + Gemini)

---

## Hook — „Garland will knock you all down" (0:00 – 2:00) | 2 min

- Slajd: Garland w bossroomie nad mostem Coneria. Cytat z gry: *"I, Garland, will knock you all down!"*
- Twist: w mojej kampanii to **agent** miał go knock down. Bez żadnego ludzkiego inputu od momentu naciśnięcia START.
- Setup celu talku:
  > „Mam 45 minut, żeby Wam pokazać, jak doszedłem od `Process exited with code 1` do `Outcome.Victory: Garland HP=0`. I jak każda porażka po drodze nauczyła mnie czegoś o agentowym programowaniu w 2026."
- Zapowiedź trzech aktów: **Naive Agent → Skilled Agent → Explorer Agent**.
- Demo loop start (`./gradlew :knes-agent:runExplorer` w tle przez cały talk; pod koniec pokażę co zebrał).

---

## Akt I — Skąd to się wzięło (2:00 – 7:00) | 5 min

### 1.1 kNES — emulator NES w Kotlinie (2 min)
- Fork vNES → pełny port: CPU 6502, PPU, PAPU, MMC1 (FF1 ✓, Zelda ✓, Metroid ✓).
- 400+ testów (instrukcje CPU, nestest.nes, SMB E2E z RAM assertions).
- KotlinConf 2025 talk: *„Build your own NES Emulator with Kotlin"* — fundament, na którym buduję dziś.

### 1.2 Po co nam agent? (1 min)
- Mam już REST API (`:knes-api`, port 6502) i MCP server.
- Claude Code przez MCP gra całkiem dobrze — ale to **Claude jako klient**.
- Chcę **agenta in-process** w Kotlinie, własna pętla, własna perception, JetBrains Koog jako framework.

### 1.3 Garland jako benchmark (2 min)
- FF1 (1987): 4 postacie, turn-based, 256×256 overworld, ~30 mapId interiors.
- Człowiek pokonuje Garlanda w **~5 min** od boota.
- Speedrun route z TASvideos: title-skip → party (FIGHTER ×2 + WHITE/BLACK MAGE) → S+E z castlu → most → walka → 4× FIGHT.
- **Agent ma to zrobić sam.** To jest mój benchmark na cały talk.

---

## Akt II — V1: Naive ReAct (7:00 – 17:00) | 10 min

### 2.1 Architektura V1 (3 min)
- Slajd diagramu:
  ```
  AgentSession.outerLoop
    └── AdvisorAgent (Opus 4, single-shot)
    └── ExecutorAgent (Sonnet 4.5, reActStrategy, max 10 iter)
        └── 13 narzędzi: step, tap, sequence, press, release,
                          getState, getScreen, applyProfile,
                          executeAction, listActions, ...
  ```
- System prompt: ten sam, którego używał Claude Code przez MCP.
- Wybrałem `reActStrategy` z Koog 0.5.1 — wydawało się idealne dla "agent z narzędziami".

### 2.2 Live demo trace V1 (3 min)
- Pokaż prawdziwy fragment `trace.jsonl`:
  ```
  iter 1: getState() → ram dump
  iter 2: "Let me analyze: goldLow=144 + goldMid×256 = 400 GP..."
  iter 3: getState() ← AGAIN
  iter 4: kolejny paragraf analizy
  ...
  iter 10: AIAgentMaxNumberOfIterationsReachedException
  ```
- **Agent nie nacisnął ani jednego przycisku.**
- Koszt: $20–50 za attempted run. Wall clock: timeout w 9 minut.

### 2.3 Diagnoza — dlaczego ReAct się nie sprawdza (2 min)
- Klasyczny failure mode z papieru ReAct (Yao 2022, §6).
- Reflexion paper (Shinn 2023): „ReAct without external termination signal **diverges** on long-horizon partially-observable tasks".
- Anthropic's Claude Plays Pokémon: **35 000 akcji do trzeciego badge'a, 140h compute** — i to jest właśnie ten sam objaw.
- **Lesson #1:** Jeśli Twój agent kończy w iteration cap za każdym razem, to nie jest prompt problem. To jest architectural problem.

### 2.4 Detour: research dossier (2 min)
- Przeczytałem co robią inni (ZenML pisze o Claude Plays Pokémon, Voyager, PokéLLMon, PokeRL).
- Trzy kluczowe insighty:
  1. **Anthropic's harness ma 3 narzędzia** — knowledge base, button press, navigator. „Hershey stripped complexity out over time."
  2. **Voyager — skill library**: GPT-4 + reusable code = 15.3× szybszy tech-tree progress.
  3. **Macro vs Micro split**: LLM = makro, scripted Kotlin = mikro. **Zawsze.**

---

## Akt III — V2: Skills + Caching + Routing (17:00 – 28:00) | 11 min

### 3.1 Pięć precyzyjnych zmian (1 min — wstęp)
Slajd:
| # | Zmiana | Powód |
|---|--------|-------|
| 1 | `singleRunStrategy` zamiast `reActStrategy` | Outer loop u nas, not in Koog |
| 2 | Voyager-style skill library | LLM wybiera skill, nie przyciski |
| 3 | Long-lived `AnthropicLLMClient` + caching | 5–10× redukcja input cost |
| 4 | Phase-aware model routing | Haiku do oczywistego, Opus do trudnego |
| 5 | Tool surface 13 → 7 | Mniej choice paralysis |

### 3.2 Zmiana #1 — `singleRunStrategy` (2 min)
- Koog 0.5.1: `singleRunStrategy()` = jeden LLM call → tool call albo text → koniec.
- Outer loop wraca do **mojego** `AgentSession`, gdzie i tak miałem RAM-driven phase detection.
- Pseudokod outer loopu (slajd):
  ```
  while not done:
    phase = observer.observe(ram)
    if phase changed: advisor.consult()  // single Koog call
    result = executor.invoke(plan, phase) // single Koog call, single tool
    update budget, idle counter
    check success / failure / OutOfBudget
  ```
- **Iteration cap as default failure mode — eliminated.**

### 3.3 Zmiana #2 — Skill library (3 min) ⭐ *najważniejsza zmiana*
- Pokaż interfejs:
  ```kotlin
  interface Skill {
      val id: String
      val description: String
      suspend fun invoke(args: Map<String,String>): SkillResult
  }
  ```
- Pierwsze skille:
  - `PressStartUntilOverworld` — tap START aż `bootFlag=0x4D`
  - `CreateDefaultParty` — scripted character creation (FIGHTER ×2 + W/B MAGE)
  - `WalkOverworldTo(x, y)` — BFS pathfinding, watching `worldX/worldY`
  - `BattleFightAll` — 4× FIGHT (idealne na Garlanda)
  - `ExploreInteriorFrontier` — BFS po nieodwiedzonych kaflach
- **Każdy skill = scripted Kotlin code, ZERO tokenów LLM w środku.**
- LLM widzi 7 wysokopoziomowych narzędzi i pyta: *„który skill?"*, nie: *„który przycisk?"*
- Live: pokaż jeden trace gdzie executor wywołuje `walkOverworldTo(0xC4, 0x96)` i to jest cała tura.

### 3.4 Zmiana #3 — Prompt caching (2 min)
- V1 grzech: `AnthropicLLMClient` budowany **per call**. Każdy call = cold prompt.
- Anthropic prompt caching: cached input @ 10% base price, 5-min TTL.
- V2: long-lived client, breakpointy na `system prompt + tool descs` (~2500 tokens) + `static run preamble` (~400 tokens).
- ProjectDiscovery liczby z prod: **59–70% redukcja input cost** na multi-turn agent loops.

### 3.5 Zmiana #4 — Model routing (1 min)
- Haiku 4.5: $1/MTok in, fastest. Sonnet 4.5: $3. Opus 4: $15. **15× spread.**
- Tabela:
  ```
  Boot/TitleOrMenu/NameEntry → Sonnet 4.5  (visual novelty)
  Overworld/Battle/PostBattle → Haiku 4.5 (oczywisty wybór skilla)
  Watchdog escalation         → Opus 4    (diagnose „why am I stuck")
  ```
- ~30 LOC routing logic.

### 3.6 Zmiana #5 — Tool surface 13 → 7 (0.5 min)
- Surowe `step/tap/sequence/press/release` zostały, ale **niewidoczne dla Koog**.
- Używają ich wewnętrznie skille. Hershey heuristic: „mniej narzędzi, mniej choice paralysis".

### 3.7 V2 wynik (1.5 min)
- Boot → Garland battle: ✅
- $20–50 → **$3 / run**
- 9-min timeout → **12 min do walki**
- Iteration cap exceptions: **zero**
- **Ale: Garland nie zawsze pada.** Czasem agent kręci się po peninsuli i wpada w OutOfBudget zanim doszedł do mostu.

---

## Akt IV — V3: Cross-Run Explorer (28:00 – 39:00) | 11 min ⭐ *klimaks*

### 4.1 Druga ściana — discovery cost (2 min)
- Patrzę na traces V2 — agent kręci się po południowej Coneria. Każda iteracja **$1–2 z Opusa, żeby odkryć 1–2 warp tiles.** Peninsula ma 6–8 warpów.
- Niektóre warpy → **mapId=0 trap** (uszkodzony stan emulatora, agent nie umie wyjść).
- Każdy run zaczyna od zera. Zero cross-session world model.
- **Lesson #3:** Discovery cost compounds. Jeśli agent rediscovuje tę samą mapę — to nie jest agent, to jest goldfish.

### 4.2 Insight — rozdzielić explore i execute (1 min)
- Dwie aplikacje, dwa procesy, dwa gradle taski:
  ```bash
  ./gradlew :knes-agent:runExplorer   # Haiku 4.5, mapuje świat
  ./gradlew :knes-agent:run           # Sonnet/Opus, ZNA już drogę do Garlanda
  ```
- Interfejs między nimi: **JSON files na dysku**. Nic więcej.

### 4.3 Persistent memory model (2 min)
Pięć plików w `~/.knes/`:
```
ff1-overworld-terrain.json — mapa terenu (PLAINS, FOREST, TOWN, CASTLE)
ff1-landmarks.json         — NPC król, sklepikarz, schody, z mapId i note
ff1-blockages.json         — append-only LOG PORAŻEK
ff1-overworld-warps.json   — znane przejścia overworld → interior
ff1-interior-memory.json   — mapId → set odwiedzonych kafli
```
- **Atomic writes:** `write to *.tmp + fsync + rename`. (Wczoraj kampanii padły 3× mid-write — naprawione w PR #114.)
- Memory **rośnie monotonicznie** między runs. Agent nigdy nie zapomina.

### 4.4 Inner loop — LLM jako trigger, nie jako kierowca (2 min)
Pokaż na slajdzie:
```kotlin
while (stepsTaken < 200) {
    val phase = observer.observe(ram)
    checkRestart(ram, phase)?.let { return RunResult(it) }   // hard rule

    when (val trigger = detectTrigger(phase, ram)) {
        is NewInteriorEntered -> handleNewInterior(trigger)   // LLM
        is DialogBoxVisible   -> handleDialog(trigger)        // LLM
        is BattleEntered      -> battleFightAll()             // skript
        null                  -> deterministicStep(phase)     // skript
    }
}
```
- **90%+ kroków: zero LLM calls.**
- LLM włącza się tylko przy triggerach: nowe wnętrze, dialog box, watchdog.
- Cała kampania (10–20 runs) kosztuje **<$1**.

### 4.5 Salience strategy — agent ma priorytety (2 min)
Slajd z pseudokodem `pickOverworldTarget`:
```
Priority 0: closest known warp not yet entered this run
Priority 1: unvisited known landmarks (TOWN/CASTLE_ENTRY)
Priority 2: salient viewport tiles (TOWN/CASTLE not yet recorded)
Priority 3: nearest passable tile adjacent to UNKNOWN
Priority 4: cross-run diversification (last 3 runs N → idź E)
Priority 5: wander (degenerate fallback)
```
- **Blockage feedback loop:** `walkOverworldTo` → BLOCKED → zapis do `blockages.json` → następny target tę lokację filtruje.
- **Cross-run diversification:** `runStartDirection` w blockages → nie powtarzaj kierunku.
- **Agent uczy się z porażek na poziomie filesystemu**, nie tylko w prompt history.

### 4.6 Rezultat — droga do Garlanda po 3 kampaniach (2 min)
- Run 1: spawn → N → CASTLE w viewport → entry → mapId=24 throne → `NPC_KING` zapisany.
- Run 2: spawn → mija castle (visited) → priority 2 znajduje TOWN → mapId=8 → `NPC_SHOPKEEPER`.
- Run 3+: pełna mapa peninsuli, wszystkie warpy, blockages dla 2 traps.
- **Phase 2 (`runAgent`):** czyta `landmarks.json`, dostaje `LandmarkContext`:
  ```
  Known landmarks:
    - NPC_KING at mapId=24 (Coneria Castle throne, worldX=146, worldY=152)
    - NPC_SHOPKEEPER at mapId=8 (Coneria Town)
  Known warps: (145,152), (147,154), ...
  ```
- Advisor planuje: *„Walk to (146,152), enter castle, talk to King, exit S, cross bridge, fight Garland."*
- Garland fight: `BattleFightAll()` × 3–4 turn → **`enemy_hp[0] == 0` → `Outcome.Victory`**.
- Demo: pokaż screenshot Garlanda padającego + linia trace.jsonl: `{"outcome": "Victory", "totalCostUsd": 0.87}`.

---

## Akt V — War stories (39:00 – 43:00) | 4 min *(publiczność uwielbia)*

### 5.1 Haiku halucynuje schody (1.5 min)
A/B Haiku 4.5 vs Gemini 2.5 Pro na klasyfikacji wnętrz:
| Ekran | Haiku 4.5 | Gemini 2.5 Pro | Truth |
|-------|-----------|-----------------|-------|
| mapId=0 void | 4 false positives | `[]` | empty |
| Castle throne | NPC_KING ✓ + STAIRS_DOWN ✗ | NPC_KING ✓ z accurate detail | NPC_KING |

- 6× cost ratio, ale absolute differential trywialny.
- Lesson: **A/B vision backendy w produkcji.** Tańszy model nie zawsze tańszy w sumie kosztów debugowania.

### 5.2 V5.2 input-frame drop (1 min)
- Po `loadState`: agent próbuje `step(["RIGHT"], 16)` → postać stoi.
- Diagnoza: emulator gubi pierwsze ~30 frames inputu po loadState.
- Fix (#109): post-loadState 30-frame NOOP warm-up. Agent rusza od iter 1.

### 5.3 mapId=0 trap + atomic saves (1.5 min)
- mapflags=1 + mapId=0 = uszkodzony interior, agent w pustym voidzie.
- Stary fix: czekaj 3 idle turns → restart. Nowy (#113): bail immediately.
- Plus #114: **atomic JSON saves**. 3× w jeden dzień JVM padło mid-write i `landmarks.json` zostawał `{}`. Stracona pełna kampania.
- Fix: write `*.tmp` + rename. Worst case loss: 1 step.
- **Lesson:** persistent state w long-running agentach to ta sama kategoria ryzyka co baza danych.

---

## Akt VI — Lessons learned (43:00 – 44:30) | 1.5 min

Slajd z 10 lessonami (przelot, bez dłuższego komentarza — to ma zostać na zdjęciu z konferencji):

1. **Nie pozwól LLM wybierać iteration count.** Twój kod, nie LLM.
2. **Macro/micro split.** LLM = co. Scripted code = jak.
3. **Skills jako warstwa abstrakcji.** Testowalne, model-niezależne.
4. **Persistent memory > re-discovery.** JSON files, atomic writes.
5. **Discovery jako oddzielna faza.** Tani model + cumulative memory.
6. **Triggers zamiast steady-state inference.** 90% kroków bez LLM.
7. **Vision backend hallucinations są realne.** A/B test.
8. **Atomic disk writes from day one.** Long-running agents PADAJĄ.
9. **Caching multiplikuje wszystko.** Twoje koszty leżą w prefiksach.
10. **Kradnij znane wzorce.** Hershey upraszczał, nie wymyślał.

---

## Zamknięcie (44:30 – 45:00) | 30 sek

- Garland leży. Koszt całej kampanii (explorer + execute): **<$5**.
- *„Agentowe programowanie w 2026 nie polega na zwiększaniu mocy LLM-a. Polega na wybudowaniu architektury, w której LLM robi tylko to, co LLM robi dobrze, i przyjęciu, że Twoje failure modes będą wyglądać jak kreskówka, dopóki nie stworzysz infrastruktury, która ich pilnuje."*
- Repo: github.com/ArturSkowronski/kNES — wszystkie spec docs w `/docs/superpowers/`.
- Każda decyzja udokumentowana razem z porażką, która do niej doprowadziła.
- **Pytania.**

---

## Q&A pre-prep (10 min, najczęstsze pytania)

- *„Czy to bije RL?"* — Nie. PokeRL (10M params) bije każdy LLM-bot na speed i cost. Ale LLM agenty mają inny use case: zero-shot, zero training data, dialog-friendly.
- *„Dlaczego Koog a nie LangChain?"* — JVM-native, typed `@Tool` przez reflection, strategy graphs (`singleRunStrategy`, custom subgraphs), kompiluje się z całym monorepo.
- *„Czy to działa na innych grach?"* — Architektura tak, ale skille są FF1-specific. `WalkOverworldTo` wymaga walkable tile table per game. Memory schema jest generyczna (terrain/landmarks/blockages/warps).
- *„Token cost vs RL training?"* — Pełna kampania explore+execute: <$5. RL training (PokeRL): tygodnie GPU compute. Cross-over point gdzieś przy ~10 000 runs.
- *„Co dalej?"* — Voyager-style automatic skill authoring (LLM pisze nowy Kotlin skill kiedy obecne zawodzą), accordion summarization dla długich kampanii (Marsh Cave i dalej), Reflexion-style self-criticism.

---

## Slajdy / asset checklist

- [ ] Hook: Garland screenshot + cytat „I, Garland..."
- [ ] kNES architecture diagram (z KotlinConf 2025)
- [ ] V1 trace.jsonl fragment (analiza RAM zamiast button presses)
- [ ] Tabela 5 zmian V2
- [ ] Skill interface code
- [ ] Pseudokod outer loop V2
- [ ] V2 cost breakdown ($20-50 → $3)
- [ ] Persistent memory diagram (5 JSON files)
- [ ] Salience strategy 5-priority pseudokod
- [ ] A/B Haiku vs Gemini tabela
- [ ] Atomic save bug screenshot (`{}`)
- [ ] 10 lessons learned (slajd-zdjęcie)
- [ ] Live demo: `./gradlew :knes-agent:runExplorer` w tle, pod koniec `cat ~/.knes/ff1-landmarks.json`

## Demo skrypt

```bash
# Przed talkiem
rm -rf ~/.knes/runs/*
cp -r ~/.knes ~/.knes.backup
ANTHROPIC_API_KEY=$KEY ./gradlew :knes-agent:runExplorer &  # kampania w tle

# Pod koniec talku
jq '.landmarks | length' ~/.knes/ff1-landmarks.json   # ile odkrył
jq -r '.landmarks[] | "\(.kind) @ mapId=\(.mapId)"' ~/.knes/ff1-landmarks.json
tail -1 ~/.knes/runs/$(ls -t ~/.knes/runs | head -1)/summary.md
```

## Backup slajdy (jeśli zostanie czas)

- Koog strategy DSL — graph-based agents
- Anthropic prompt caching breakpointy w detalach
- FF1 RAM map (TASvideos) — który adres jest który
- Porównanie z Claude Plays Pokémon i Voyager (architektury rzędem obok siebie)
