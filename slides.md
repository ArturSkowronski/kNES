---
marp: true
theme: default
paginate: true
size: 16:9
header: 'Iteracje agenta gracza FF1 · Artur Skowroński · Geecon 2026'
footer: 'github.com/ArturSkowronski/kNES'
style: |
  section { font-size: 25px; }
  section.title { font-size: 36px; }
  section.divider { background: #1a1a1a; color: white; font-size: 56px; }
  section.problem { background: #fff5f5; border-left: 12px solid #c53030; }
  section.solution { background: #f0fff4; border-left: 12px solid #2f855a; }
  section.gen { background: #ebf8ff; border-left: 12px solid #2b6cb0; }
  section.quote { font-size: 36px; font-style: italic; text-align: center; }
  section.next { background: #1a1a1a; color: #f6ad55; font-size: 40px; text-align: center; }
  code { font-size: 0.85em; }
  pre { font-size: 0.78em; }
  table { font-size: 0.78em; }
  h1 { color: #2b6cb0; }
  h2 { color: #2b6cb0; border-bottom: 2px solid #2b6cb0; }
  small { font-size: 0.7em; color: #666; }
---

<!-- _class: title -->

# Iteracje agenta gracza FF1

## Krótka historia agentowego programowania w 6 problemach

**Artur Skowroński** · Geecon 2026

`github.com/ArturSkowronski/kNES`

<!--
Cześć. 45 minut, sześć iteracji agenta grającego w Final Fantasy 1. Każda zaczyna się od konkretnego problemu, który mnie blokował. Każde rozwiązanie generalizuje się do wzorca, który ktoś już opisał. Każde rozwiązanie odsłania nowy problem.
-->

---

# Setup w jednym slajdzie

- **kNES** — emulator NES w Kotlinie. KotlinConf 2025 talk.
- **Final Fantasy 1** (1987) — long-horizon, partial-observable, deterministyczny stan.
- **Cel:** dotrzeć do **Garlanda** — bossa Chaos Shrine.
- **Stack:** Kotlin + Koog (JetBrains) + Anthropic Claude.

---

<!-- _class: title -->

# Pętla, którą Wam pokażę

## **Problem → Rozwiązanie → Nowy problem**

6 iteracji.

Każda kończy się tym samym pytaniem:
*„OK, ale co teraz?"*

<!--
Ta pętla to nie jest mój zwyczaj. To jest definicja agentowego programowania w 2026. Pokażę Wam sześć takich iteracji.
-->

---

<!-- _class: divider -->

# Loop 1

## Naive ReAct

---

<!-- _class: problem -->

# Problem #1

Pierwszy agent: Advisor (Opus, single-shot) + Executor (Sonnet, **`reActStrategy`**, max 10 iter, **13 raw narzędzi**).

```jsonl
iter 1: getState() → ram dump
iter 2: "Let me analyze: goldLow=144 + goldMid×256 = 400 GP..."
iter 3: getState() ← AGAIN
iter 4: "Looking at this more carefully..."
iter 5: getScreen() → image
...
iter 10: AIAgentMaxNumberOfIterationsReachedException
```

# **Executor nie nacisnął przycisku.**
**$30 / próbę.**

<!--
Pierwszy agent. Klasyczny ReAct. Trzynaście raw narzędzi. Live trace pokazuje to co się działo: getState w nieskończoność, paragrafy analizy RAM-u, exception po 10 iteracjach. Trzydzieści dolarów za jedną próbę. Zero akcji w grze.
-->

---

<!-- _class: gen -->

# To nie jest mój bug

> *„Reasoning stuck on hallucinated facts; action repetition when observation doesn't move state."*
> — **Yao 2022 (ReAct paper §6)**

> *„ReAct without external termination signal **diverges** on long-horizon partially-observable tasks."*
> — **Reflexion paper, Shinn 2023 §4.1**

**TDS analysis 2025:** tool-name hallucination = **90.8% wasted retries**; **18% rate of „thinking instead of acting"** when history > 2.

**Hershey, Claude Plays Pokémon:** „Stuck in Mt. Moon for 78 hours."

<!--
Czytam research i okazuje się — to nie jest mój bug. To jest opisany failure mode. ReAct paper sam to opisał w sekcji 6. Reflexion paper potwierdza. TDS w 2025 daje liczby: 91% wasted retries z tool hallucinations. Hersheyowy Claude na Twitchu utknął 78 godzin w Mt. Moon. Ten sam mechanizm.
-->

---

<!-- _class: solution -->

# Rozwiązanie #1

```kotlin
val executor = AIAgent(
    strategy = singleRunStrategy(),  // ← jeden tool call per LLM
    // ...
)

// Outer loop wraca DO NAS
while (not done) {
    if (phase changed || idleTurns >= 20) {
        currentPlan = advisor.plan(phase, observation)
    }
    result = executor.invoke(currentPlan, phase)
}
```

> *„Find the simplest solution possible, and only increase complexity when needed."*
> — Anthropic, Building Effective Agents (Dec 2024)

**Iteration cap exceptions: zero.**

<!--
Rozwiązanie. singleRunStrategy z Koog: jeden LLM call per agent run. Outer loop wraca do nas — gdzie i tak miałem RAM-driven phase detection. Anthropic's reguła: zacznij od najprostszego rozwiązania. Rezultat: iteration cap exceptions zniknęły. Zero.
-->

---

<!-- _class: next -->

# Ale agent dalej nie commituje akcji.

# Wybiera `step([RIGHT], 16)`,
# nie `walkToBridge`.

# **Co jest narzędziem?**

---

<!-- _class: divider -->

# Loop 2

## Wrong tool granularity

---

<!-- _class: problem -->

# Problem #2

Executor z **13 raw button-level narzędziami**:

```
step, tap, sequence, press, release,
getState, getScreen, applyProfile,
executeAction, listActions, ...
```

Plany advisora są długie i brittle:
> *„step DOWN 16 frames, then RIGHT 32, getState, then DOWN 8..."*

**Koszt na decyzję:** 1500-3000 tokenów input × 200+ decyzji per run.

<!--
Drugi problem. Trzynaście narzędzi button-level. Plany advisora są długie, kruche, pełne arytmetyki. Każda decyzja kosztuje 1500-3000 tokenów input, mam 200+ decyzji per run. Matematyka się nie zgadza.
-->

---

<!-- _class: gen -->

# Voyager (NVIDIA, 2023) — skill library

- LLM pisze JS funkcje, indexed by **embedding of NL description**
- Top-5 retrieval → exec feedback + self-verification critic
- **vs ReAct/Reflexion/AutoGPT:**
  - 3.3× more unique items
  - **15.3× faster wood, 8.5× stone, 6.4× iron**
  - **Only system to reach diamond level**
- Ablation: bez self-verification −73%, bez curriculum −93%

<small>[arXiv 2305.16291]</small>

<!--
Voyager z 2023. NVIDIA + Caltech + Stanford. Skill library w Minecrafcie. LLM pisze funkcje JavaScript, indexed by embedding opisu. Top-5 retrieval. Wyniki: 3-krotnie więcej itemów, 15-krotnie szybciej do drewna, jedyny system do diamentu. To jest moment, w którym społeczność zrozumiała, że skill library to game-changer.
-->

---

<!-- _class: quote -->

> *„I've actually stripped out complexity over time, not added it."*

— David Hershey, **Claude Plays Pokémon**

`update_knowledge_base` · `use_emulator` · `navigator`

**3 narzędzia. To wszystko.**

<!--
Hershey, Anthropic. Claude Plays Pokémon. Tylko trzy narzędzia. update_knowledge_base — self-managed memory. use_emulator — button press. navigator — pathfinding. To wszystko. Cytat: stripped out complexity over time, NOT added. To jest najważniejszy slajd całego talku może.
-->

---

<!-- _class: gen -->

# Anthropic mówi to samo

> *„We spent more time optimizing tools than the prompt."*
> — **Building Effective Agents** (Dec 2024)

> *„If a human engineer can't say which tool to use, an AI agent can't either."*
> — **Writing Effective Tools** (Sept 2025)

**Anthropic Code Execution z MCP (Nov 2025):**
Loading raw MCP schemas → 150K tokenów. Wrap as code APIs → **2K tokenów. 98.7% redukcja.**

<!--
Anthropic mówi to samo na trzy różne sposoby. Buidling Effective Agents — więcej czasu na tools niż na prompty. Writing Effective Tools — jeśli człowiek nie wie którego narzędzia użyć, agent też nie. I Code Execution z MCP — wrap as code, dropuj kontekst o 99 procent. Wszystko prowadzi do tego samego: granularity narzędzi to wszystko.
-->

---

<!-- _class: solution -->

# Rozwiązanie #2 — skill library w FF

```
pressStartUntilOverworld     tap START aż bootFlag=0x4D
walkOverworldTo(x, y)        BFS pathfinding, aborts on encounter
exitInterior                 BFS to nearest exit
exploreInteriorFrontier      BFS do unvisited tile + persists
battleFightAll               4× FIGHT loop
findPath(x, y)               read-only path query
```

**Każdy skill = scripted Kotlin. ZERO tokenów LLM w środku.**

Tool surface 13 → 7. Raw `step/tap/sequence` ukryte.

**Cost: $20–50 → ~$3 / run** (target). Iteration cap exceptions: zero.

<!--
Rozwiązanie. Skill library w Kotlinie. Sześć wysokopoziomowych narzędzi zamiast trzynastu raw. Każdy skill to scripted code z zero tokenów LLM. Tool surface widoczny dla Koog: siedem narzędzi. Cost spadł rzędem wielkości — z 20-50 dolarów do trzech.
-->

---

<!-- _class: next -->

# Ale agent dochodzi do peninsuli i tam ginie.

# **Każda iteracja Opusa = $1-2 na rediscovery 1-2 warpów.**

# Każdy run zaczyna od zera.

---

<!-- _class: divider -->

# Loop 3

## Memory across sessions

---

<!-- _class: problem -->

# Problem #3 — goldfish-orchestrator

- Każdy run zaczyna od zera.
- Discovery cost compound.
- Advisor mądry w tej sesji, **niczego nie pamięta na następną**.
- Peninsula ma 6-8 warpów. Niektóre prowadzą do mapId=0 trap.
- $1-2 z Opusa **per warp odkryty**.

> Klasyczny goldfish-orchestrator.

<!--
Trzeci problem. Advisor goldfish. Mądry w tej sesji, zapomina na następną. Każdy run rediscovuje to samo. Sześć do ośmiu warpów na peninsuli, dolar dwa za każdy. Discovery cost compound.
-->

---

<!-- _class: gen -->

# Long context to nie magic bullet

**Chroma „Context Rot" (2024-2025):** 18 SOTA modeli (GPT-4.1, Claude 4, Gemini 2.5).

- Performance degrades **non-uniformly** as input grows
- Distractors, semantic similarity, haystack structure all matter
- **NIAH passing ≠ long context working**

> *„NIAH underestimates what most long-context tasks require in practice."*

<small>[research.trychroma.com/context-rot]</small>

<!--
Chroma research. Bardzo ważny post. Przetestowali 18 SOTA modeli. Long context to nie magic bullet. NIAH passing nie znaczy że long context działa. Trzeba mieć structured memory, nie wpychać wszystkiego w context window.
-->

---

<!-- _class: gen -->

# Anthropic 3 primitives (Sept 2025)

| Primitive | API | Co robi |
|-----------|-----|---------|
| **Compaction** | `compact_20260112` | Summarize at threshold |
| **Tool clearing** | `clear_tool_uses_20250919` | Drop old outputs |
| **Memory tool** | `memory_20250818` | Filesystem-based persistent |

**Cookbook:** peak context **335K → 169K (compact)**.

> *„Find the smallest possible set of high-signal tokens that maximize likelihood of desired outcome."*

**Wisedocs case:** 97% reduction in first-pass errors.

<!--
Anthropic context engineering, wrzesień 2025. Trzy primitives już jako API. Compaction, clearing, memory tool. W ich własnym cookbooku peak context spada z 335 do 169K. Cytat to jest definicja context engineeringu. Real case study Wisedocs: 97% redukcji first-pass errors po włączeniu memory tool.
-->

---

<!-- _class: gen -->

# MemGPT → Mem0 → Zep → Letta

**MemGPT** (Packer 2023): OS-inspired hierarchy.
- DMR: **93.4% vs 35.3%** baseline

**Mem0** (2025): vector + graph + KV hybrid.
- LOCOMO: **91% latency cut, 14× token cut** at near-parity

**Zep** (2025): temporal knowledge graph.
- LongMemEval: **63.8% vs Mem0's 49.0%**

**Sleep-time compute** (Letta + Berkeley, 2025):
- **5× less test-time compute** for same accuracy

> Dla JVM crowd: virtual memory dla LLMs.

<!--
Ekosystem memory framework w jednym slajdzie. MemGPT 2023, OS-inspired. Mem0 2025, hybrid. Zep 2025, temporal KG. Sleep-time compute 2025, pre-compute na kontekście zanim pyta user. Wszystkie z konkretnymi liczbami. Mental model dla JVM: virtual memory dla LLM.
-->

---

<!-- _class: solution -->

# Rozwiązanie #3 — 5 JSON files

```
~/.knes/
├── ff1-overworld-terrain.json   PLAINS, FOREST, TOWN, CASTLE
├── ff1-landmarks.json           NPC król, sklepikarz, schody
├── ff1-blockages.json           append-only LOG PORAŻEK
├── ff1-overworld-warps.json     znane przejścia
└── ff1-interior-memory.json     mapId → odwiedzone kafle
```

**Atomic writes** (`AtomicJsonWriter`, PR #114): `*.tmp + fsync + rename`.
3× JVM padło mid-write i straciłem kampanię.

**Memory rośnie monotonicznie cross-session.**

Phase 2: advisor czyta `LandmarkContext.render(landmarkMemory)` w prompcie → decisions w 1-2 turach zamiast 10-20.

<!--
Rozwiązanie. Pięć plików JSON. Atomic writes — bo trzy razy w jeden dzień JVM padał mid-write. Memory rośnie monotonicznie. Phase 2 advisor czyta cross-session memory bezpośrednio z prompt — decyzje w 1-2 turach zamiast 10-20.
-->

---

<!-- _class: next -->

# Ale advisor dalej drogi.

# **Mapowanie peninsuli to deterministyczny problem.**

# Po co tam Opus za $15/MTok?

---

<!-- _class: divider -->

# Loop 4

## Discovery is its own phase

---

<!-- _class: problem -->

# Problem #4 — drogie eksplorowanie

- Mapowanie terenu = problem deterministyczny.
- Identyfikacja NPC = ostry problem visual classification.
- Battle handling = scripted (4× FIGHT).
- **Czemu wszystko to robi Opus za $15/MTok?**

Każdy advisor call w fazie eksploracji to overkill.

<!--
Czwarty problem. Mapowanie terenu jest deterministyczne — BFS sobie poradzi. Identyfikacja NPC potrzebuje vision, ale niekoniecznie planowania. Battle to skript. Czemu we wszystkich tych miejscach pali się drogi Opus?
-->

---

<!-- _class: gen -->

# Voyager curriculum + library = dwa loopy

```
┌─────────────────────────┐    ┌─────────────────────────┐
│ Curriculum (drogie LLM) │    │ Skill library (cheap)   │
│ Generuje cele           │    │ Reuse'uje rozwiązania   │
└─────────────────────────┘    └─────────────────────────┘
```

**Anthropic „Multi-agent research system" (Jun 2025):**
- Lead Opus + sub Sonnet → **+90.2% vs single Opus**
- ALE: **15× tokens** vs chat
- *„Multi-agent only viable when value of task > token cost."*

**Macro/micro principle:** discovery to micro. Skoro deterministic — niech będzie.

<!--
Voyager z 2023 sprzedał dwa loopy: curriculum dla generowania celów (drogi LLM), library dla reuse (tani). Anthropic dorobił to w 2025 jako orchestrator-worker. 90% lepiej niż single agent, ale 15-krotnie tokenów. Reguła: multi-agent dopiero gdy value > cost. Macro micro: discovery to micro, niech będzie deterministic.
-->

---

<!-- _class: solution -->

# Rozwiązanie #4 — dwa procesy

```bash
./gradlew :knes-agent:runExplorer   # Phase 1: Haiku, salience-driven
./gradlew :knes-agent:run           # Phase 2: Advisor + Executor
```

**Phase 1 inner loop** — LLM jako trigger, nie sterowanie:

```kotlin
when (val trigger = detectTrigger(phase, ram)) {
    is NewInteriorEntered -> handleNewInterior(trigger)  // Haiku
    is DialogBoxVisible   -> handleDialog(trigger)       // Haiku
    is BattleEntered      -> battleFightAll()            // skript
    null                  -> deterministicStep(phase)    // skript
}
```

- **90%+ kroków: zero LLM calls.**
- Cała kampania (10-20 runs): **<$1**. Realnie zmierzone.
- Interfejs między Phase 1 i 2: **JSON files na dysku.**

<!--
Rozwiązanie. Dwa procesy, dwa Gradle taski. Phase 1 explorer — Haiku triggerowany salience strategy. Phase 2 — pełny advisor plus executor, czyta wynik Phase 1. 90% kroków w Phase 1 to zero LLM calls. Cała kampania pod dolarem — to zmierzone.
-->

---

<!-- _class: next -->

# Ale advisor halucynuje koordynaty z training data.

# *„Walk WEST to x=140"*

# **prowadzi prosto w trap.**

---

<!-- _class: divider -->

# Loop 5

## Ground truth, hallucination, vision

---

<!-- _class: problem -->

# Problem #5a — advisor halucynuje koordynaty

Iter 1 + iter 2 evidence:

> *„Coneria Castle is at (152, 159). Walk WEST to x=140 first."*

Plan prowadził party prosto w **ukryty interior entry (145, 152)**, dwa razy z rzędu.

**Skąd advisor to ma?** Z training data — FF1 wiki, FAQs.

# **Confidently wrong.**

<!--
Piąty problem. Advisor cytuje koordynaty z training data. FF1 wiki, GameFAQs, speedrun guides — wszystko w training data. Confidently wrong. Plan prowadzi w ukryty trap dwa razy z rzędu zanim zorientuję się co się dzieje.
-->

---

<!-- _class: problem -->

# Problem #5b — Haiku halucynuje schody

A/B Haiku 4.5 vs Gemini 2.5 Pro na klasyfikacji wnętrz:

| Ekran | Haiku 4.5 | Gemini 2.5 Pro |
|-------|-----------|-----------------|
| mapId=0 void (uszkodzony stan) | **4 false positives** | `[]` ✓ |
| Castle throne | NPC_KING ✓ + STAIRS_DOWN ✗ | NPC_KING ✓ |

6× cost ratio ($0.001 vs $0.005-7 / call). Absolute differential trywialny: $0.05 vs $0.30 / 50-run kampanii.

<!--
Drugi problem piąty. Vision halucynuje. Haiku confidently widzi schody, których nie ma. Gemini Pro precyzyjnie identyfikuje. Sześciokrotnie droższy, ale absolute differential to centy w skali kampanii. Precyzja warta więcej.
-->

---

<!-- _class: gen -->

# To też nie jest mój bug

> *„Reasoning RL-trained models hallucinate tools more — fabricating tool existence, inventing args, fabricating success after a failure."*
> — **„The Reasoning Trap"** (arXiv 2510.22977, Oct 2025)

> *„We caught Claude appending a wrong year to web-search queries — fixed via description, NOT code."*
> — **Anthropic Writing Tools** (Sept 2025)

> *„Trust only observed game state, not training-data knowledge of Pokémon."*
> — **Hershey, CPP system prompt**

<!--
Trzy źródła w jednym slajdzie. Reasoning Trap z października 2025: reasoning models halucynują BARDZIEJ. Anthropic złapał Claude'a appending wrong year do web-search queries — fix przez description, nie code. Hershey w system prompt CPP eksplicit: zaufaj tylko observed state, nie training data.
-->

---

<!-- _class: solution -->

# Rozwiązanie #5 — GROUND TRUTH ONLY

```text
GROUND TRUTH ONLY (V5.21): your ONLY trustworthy sources are
  (1) the RAM dump,
  (2) the ASCII WORLD VIEW / interior map block,
  (3) the screenshot if you call getScreen.
FF1 coordinates from your training data are UNRELIABLE.
NEVER cite a specific entry-tile coordinate ("Coneria Castle
is at (152, 159)") unless you can SEE the C/T glyph at that
exact tile in the ASCII map.
```

— `AdvisorAgent.kt:103-114`

**Plus:**
- ASCII map renderer (`AsciiMapRenderer`) → textual viewport dla advisora
- Vision backend swap przez env var: `KNES_VISION=gemini-pro`

> Advisor jest tak dobry, jak jego ground-truth contract.

<!--
Rozwiązanie. Real fragment z mojego AdvisorAgent.kt linia 103. GROUND TRUTH ONLY paragraph. Plus ASCII map renderer — daje advisorowi textual viewport zamiast samego screenshota. Plus swap vision backend przez env var. Lesson: advisor jest tak dobry jak jego ground-truth contract.
-->

---

<!-- _class: next -->

# Advisor i executor robią dobre decyzje.

# Ale gdybym dał advisorowi `reset()` jako tool —

# **co by się stało?**

---

<!-- _class: divider -->

# Loop 6

## Production safety

---

<!-- _class: problem -->

# Problem #6 — co jeśli agent ma destructive verbs?

Hipotetycznie: dałem advisorowi pełny `EmulatorToolset`.

Halucynuje, że dla testów warto zrobić `reset()`. Albo `loadState(path)` z null.

**Co się dzieje?**

Pytanie nie jest hipotetyczne. **Inni już to przeżyli.**

<!--
Szósty problem. Hipotetycznie: co jeśli dałbym advisorowi pełny toolset, włącznie z destructive verbs? On halucynuje że dla testów warto zrobić reset. Pytanie nie jest hipotetyczne. Inni już to przeżyli w produkcji.
-->

---

<!-- _class: gen -->

# Listopad 2025: $47 000

- 4 LangChain A2A agents. Analyzer ↔ Verifier.
- **11 dni. $47 000.**
- Mieli budget *alerts* na Slacku — nikt nie czytał.

> *„Token budget alerts aren't budget enforcement. The agent doesn't read Slack."*

<small>[dev.to/$47k loop]</small>

---

<!-- _class: gen -->

# Lipiec 2025: Replit + 1190 firm

- Eksperyment „vibe coding". Dzień 8: explicit code freeze.
- Agent **deletował produkcyjną bazę**.
- Sfabrykował test results żeby ukryć bug.
- Skłamał, że rollback niemożliwy.

<small>[The Register, SaaStr / Jason Lemkin]</small>

---

<!-- _class: gen -->

# 2025: terraform destroy na 2.5 roku

- DataTalks.Club: Claude Code czyści „duplicate resources".
- *„terraform destroy is cleaner and simpler."*
- VPC + RDS + ECS + automatic snapshots zniknęły.
- 100 000 studentów.
- Saved by ukryty AWS snapshot.

<small>[Tom's Hardware / Alexey Grigorev]</small>

---

<!-- _class: gen -->

# Luty 2024: Air Canada

Chatbot wymyślił bereavement-fare policy.

> *„Companies remain liable for information provided by an AI chatbot."*
> — **BC Civil Resolution Tribunal**

**Klarna 2024-2025:** 700 agentów AI → repeat-contacts wzrosły → CEO admit *„cut too aggressively"* → rehiring.

<!--
Air Canada. Chatbot wymyślił bereavement-fare policy. Tribunal: company liable. Klarna paralela: zastąpili ludzi AI, repeat contacts wzrosły, znów zatrudniają. Każda customer-facing odpowiedź to potencjalny kontrakt.
-->

---

<!-- _class: solution -->

# Rozwiązanie #6a — read-only contract

```kotlin
class AdvisorAgent(
    private val toolset: EmulatorToolset,   // FULL toolset
    // ...
) {
    // ↓ Wrapper. Advisor NIGDY nie dostanie pełnego registry.
    private val readOnlyTools = ReadOnlyToolset(toolset)
    private val registry = ToolRegistry { tools(readOnlyTools) }

    suspend fun plan(phase, observation): String {
        return newAgent(phase).run(augmented)
    }
}
```

**Read-only contract = invariant.** Naruszenie = kompilacja nie przejdzie.

> *„Decisions, not messages, are what conflict."* — Cognition

<!--
Rozwiązanie. Real fragment z AdvisorAgent. Wrapper ReadOnlyToolset, advisor nigdy nie dostaje pełnego toolsetu. Read-only contract enforced przez typ. Naruszenie nie kompiluje się. Cytat Cognition: decisions, not messages, are what conflict — implicit assumption advisora że może mutować to decyzja, którą musimy zablokować architektonicznie.
-->

---

<!-- _class: solution -->

# Rozwiązanie #6b — JVM mappings

| Anti-pattern | JVM equivalent |
|--------------|----------------|
| Budget alerts | **Resilience4j circuit breaker** (per-token) |
| Destructive verbs | **Spring Security `@PreAuthorize`** |
| Race conditions | **JPA optimistic lock `@Version`** |
| Context bloat | **JIT loading**, Anthropic 3 primitives |
| No retry budget | **Bulkhead + jittered exponential backoff** |
| No sandbox | **microVM** (Firecracker / Kata) |
| No prompt caching | **`cache_control` na stable prefixes** |

**JVM context:** SecurityManager deprecated. Agentowy świat reodkrywa **capability-based security**.

<!--
JVM mappings. Każdy agentowy anti-pattern ma swój odpowiednik w JVM disciplinie, którą znamy. Resilience4j dla budgetu. Spring Security dla destructive verbs. JPA optimistic lock dla race conditions. Bulkhead dla retry. microVM dla sandboxu. To nie jest nowa dziedzina — to ta sama discipline, applied to non-deterministic caller.
-->

---

<!-- _class: gen -->

# Cost optimization (bonus, generalizuje się dobrze)

**Prompt caching** ([ProjectDiscovery prod](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching)):
- 59% → 70% input savings, **74% cache hit rate**
- Anthropic limits: 4 breakpoints, 20-block lookback

**Batch API:** 50% off, ~30 min SLA. **Stacks z caching → ~5% standard cost.**

**Model routing** (Cursor Auto):
```
Boot/NameEntry      → Sonnet 4.5
Overworld/Battle    → Haiku 4.5  (15× cheaper than Opus)
Watchdog escalation → Opus 4
```

<!--
Cost optimization. Prompt caching w produkcji u ProjectDiscovery: 70% savings, 74% cache hit rate. Anthropic limits: cztery breakpoints, dwadzieścia bloków lookback. Batch API 50% off, stackuje z cachingiem do 5% standardu. Model routing: Sonnet do uncertain, Haiku do oczywistego (15-krotnie tańszy od Opusa), Opus tylko do watchdog.
-->

---

<!-- _class: next -->

# Architektura jest gotowa.

# **Garland nadal stoi w Chaos Shrine.**

# Co dalej?

---

<!-- _class: divider -->

# Loop 7

## Where we are

---

# Stan na dziś (HANDOFF.md, 6 maja 2026)

- 9 PRów merged w jeden dzień
- Phase 1.5 + Phase 2 verified live
- Agent dochodzi do throne roomu Króla Coneria (mapId=24)
- LandmarkContext rendered, advisor cytuje koordynaty verbatim
- Pipeline: `runExplorer` (Haiku, <$1) + `runAgent` (Advisor + Executor)
- **Honest cliffhanger:** Garland w Chaos Shrine pending

```bash
$ jq '.landmarks | length' ~/.knes/ff1-landmarks.json
7

$ jq '.landmarks[] | select(.kind=="NPC_KING")' ~/.knes/ff1-landmarks.json
{
  "id": "coneria_castle_throne_npc_king",
  "kind": "NPC_KING",
  "mapId": 24,
  "visited": true
}
```

<!--
Live evidence. HANDOFF z 6 maja. Dziewięć PRów w jeden dzień. Phase 1.5 i Phase 2 verified live. Agent dochodzi do tronu Króla. LandmarkContext rendered. Garland w Chaos Shrine pending — to jest następny milestone, nie ostatni.
-->

---

# Recap: 6 problemów × 6 generalizacji

| Loop | Problem | Wzorzec | Source |
|------|---------|---------|--------|
| 1 | ReAct diverguje | `singleRunStrategy`, outer loop u nas | Reflexion 2023 |
| 2 | Wrong granularity | Skill library, macro/micro | Voyager, Hershey |
| 3 | No memory | 5 JSON files, atomic | MemGPT, Mem0, Anthropic |
| 4 | Discovery expensive | Explorer ≠ execute phase | Multi-agent value > cost |
| 5 | Hallucinated context | GROUND TRUTH ONLY | Reasoning Trap, Anthropic Tools |
| 6 | No safety | Read-only contract | Cognition, Anthropic sandboxing |

**Każdy problem miał swój odpowiednik w literaturze. Nie ja go wymyśliłem.**

<!--
Sześć loopów, sześć problemów, sześć generalizacji. Każdy problem miał swój odpowiednik w literaturze. Nie ja go wymyśliłem. To jest pointa.
-->

---

<!-- _class: title -->

# Garland nadal stoi w Chaos Shrine

## Ale każda decyzja w tym kodzie

## ma swoje imię w literaturze

`github.com/ArturSkowronski/kNES`

> Spec docs udokumentowane razem z porażkami,
> które do nich doprowadziły.

**Pytania.**

<!--
Zacząłem od „napiszę agenta, który pokona Garlanda". Skończyłem na sześciu iteracjach, w których za każdym razem moja FF-specyficzna porażka okazała się mieć imię w literaturze. Garland nadal stoi w Chaos Shrine. Każda decyzja w tym repozytorium ma swój odpowiednik w jednym z papierów albo postów dziś pokazanych. Nie zbudowałem niczego unikalnego — przeszedłem ścieżkę, którą zna ekosystem. To nie jest słabość. To jest pointa. Pytania.
-->

---

<!-- _class: divider -->

# Q&A

---

# Backup — najczęstsze pytania

**Q: Multi-agent yes czy no?**
Zależy. Anthropic +90.2% (research, parallel-friendly). Cognition single-threaded (coding, tightly-coupled).

**Q: Czy long context zabija RAG?**
Nie. Chroma context-rot: NIAH passing ≠ working. Hybrid wins.

**Q: Najlepszy stack 2026?**
There isn't. Match to task. Anthropic upraszcza, Cursor RL-ed, Devin używa playbooks.

**Q: Czemu Koog?**
JVM-native, typed `@Tool`, `createAgentTool` natywnie, JetBrains dogfoods.

**Q: LLM vs RL?**
PokeRL bije każdy LLM-bot na speed (10M params). Different use case.

---

# Bibliography (top 15)

**Anthropic:** [Building Effective Agents](https://www.anthropic.com/research/building-effective-agents) · [Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) · [Multi-agent](https://www.anthropic.com/engineering/multi-agent-research-system) · [Writing Tools](https://www.anthropic.com/engineering/writing-tools-for-agents) · [MCP code exec](https://www.anthropic.com/engineering/code-execution-with-mcp)

**Critique:** [Cognition: Don't Build Multi-Agents](https://cognition.ai/blog/dont-build-multi-agents) · [Chroma: Context Rot](https://research.trychroma.com/context-rot)

**Papers:** [ReAct](https://arxiv.org/abs/2210.03629) · [Reflexion](https://arxiv.org/abs/2303.11366) · [Voyager](https://arxiv.org/abs/2305.16291) · [MemGPT](https://arxiv.org/abs/2310.08560) · [Mem0](https://arxiv.org/html/2504.19413v1) · [Reasoning Trap](https://arxiv.org/abs/2510.22977)

**Production:** [$47k loop](https://dev.to/waxell/the-47000-agent-loop-why-token-budget-alerts-aren-t-budget-enforcement-389i) · [Replit DB](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/) · [ProjectDiscovery 70%](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching)

---

# Dziękuję!

**Artur Skowroński**

`github.com/ArturSkowronski/kNES`

> Każdy problem miał swój odpowiednik w literaturze.
> Nie ja go wymyśliłem.
