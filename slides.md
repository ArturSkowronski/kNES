---
marp: true
theme: default
paginate: true
size: 16:9
header: 'Architektura systemów agentowych w 2026 · Artur Skowroński · Geecon 2026'
footer: 'github.com/ArturSkowronski/kNES'
style: |
  section { font-size: 25px; }
  section.title { font-size: 36px; }
  section.divider { background: #1a1a1a; color: white; font-size: 56px; }
  section.quote { font-size: 36px; font-style: italic; text-align: center; }
  code { font-size: 0.85em; }
  pre { font-size: 0.78em; }
  table { font-size: 0.78em; }
  h1 { color: #2b6cb0; }
  h2 { color: #2b6cb0; border-bottom: 2px solid #2b6cb0; }
  small { font-size: 0.7em; color: #666; }
---

<!-- _class: title -->

# Jak zbudować agenta, który nie zbankrutuje Cię w nocy

## Architektura systemów agentowych w 2026 — z literatury, produkcji i Final Fantasy

**Artur Skowroński** · Geecon 2026

`github.com/ArturSkowronski/kNES`

<!--
Cześć. Mam 45 minut. Dziś o budowaniu agentów — nie o LLM-ach, o tym co dookoła nich. Pokażę co robi Anthropic, OpenAI, Cursor, Devin, Cognition, MemGPT i inni — i dlaczego, mimo tych wszystkich praktyk, agenci jednak padają w produkcji. Plus mój własny case study: agent, którego napisałem w Kotlinie żeby grał w Final Fantasy.
-->

---

# Listopad 2025: $47 000

- 4 agenty LangChain A2A. Analyzer ↔ Verifier.
- Ping-pong bez external termination signal.
- **11 dni. $47 000.**
- Mieli budget *alerts* na Slacku — nikt nie czytał.

> *„Token budget alerts aren't budget enforcement. The agent doesn't read Slack."*

<small>[dev.to/$47k loop, Nov 2025]</small>

<!--
Listopad 2025. Cztery agenty od jednego startupu. Analyzer pyta Verifiera, Verifier prosi Analyzer o doprecyzowanie. Brak terminacji. Jedenaście dni. Czterdzieści siedem tysięcy dolarów. Mieli alerty — leciały na Slacka, którego nikt nie czytał. Lesson: alerts to nie enforcement.
-->

---

# Lipiec 2025: Replit kasuje prod DB

- Eksperyment „vibe coding". Dzień 8: explicit code freeze.
- Agent **deletował produkcyjną bazę** 1190 firm + 1200 executives.
- Sfabrykował test results. Skłamał, że rollback niemożliwy.
- Lemkin recover'ował manualnie.

<small>[The Register, SaaStr / Jason Lemkin]</small>

<!--
Lipiec 2025. Jason Lemkin, znany VC, robi eksperyment vibe coding na Repliucie. Mówi explicit: code freeze. Agent kasuje produkcyjną bazę 1190 firm. Fabrykuje test results żeby ukryć bug. Kłamie, że rollback nie jest możliwy. Replit CEO przeprasza publicznie, dorabia forkable dev/prod DBs i planning-only mode.
-->

---

# 2025: terraform destroy na 2.5 roku danych

- DataTalks.Club: Claude Code czyści „duplicate resources".
- Decyzja agenta: *„terraform destroy is cleaner and simpler."*
- VPC + RDS + ECS + automatic snapshots zniknęły.
- 100 000 studentów. 2.5 roku submissions.
- Saved by ukryty AWS snapshot, którego nawet konsola nie pokazała.

<small>[Tom's Hardware, Alexey Grigorev]</small>

<!--
DataTalks.Club. Alexey Grigorev. Forgot Terraform state file. Claude Code zrobił duplicate resources, on prosi o cleanup. Agent decyduje: destroy is cleaner. Zniknęło wszystko — VPC, RDS, ECS, snapshots. 2.5 roku danych. Uratowany hidden AWS snapshot, którego nawet konsola nie wyświetlała.
-->

---

# Co łączy te trzy katastrofy?

## **To nie był LLM.**

## To był **harness**.

- Brak hard budget enforcement
- Brak read-only contracts
- Brak human-in-the-loop na destructive verbs
- Brak observability na decisions, nie tylko tool calls

> Dziś o tym, jak nie zbudować takiego harness.

<!--
Wszystkie trzy historie mają coś wspólnego: model nie był winny. Każdy z tych modeli sam w sobie mógł zachować się dobrze w izolacji. Pomijano harness — czyli wszystko, co dookoła. Budget enforcement, read-only contracts, human approval gates, observability na decisions a nie tylko na tool calls. Dziś talk o tym jak budować właśnie ten harness.
-->

---

<!-- _class: divider -->

# Akt I

## Co to jest agent

---

# Anthropic Building Effective Agents (Dec 2024)

**Augmented LLM** = LLM + retrieval + tools + memory (building block)

**5 workflow patterns:**
1. Prompt Chaining
2. Routing
3. Parallelization
4. **Orchestrator-Workers** ⭐
5. Evaluator-Optimizer

**Agents** = LLM dynamicznie steruje własnym procesem

> *„Find the simplest solution possible, and only increase complexity when needed."*

<!--
Grudzień 2024, Anthropic publikuje taksonomię, która stała się industry standard. Augmented LLM jako klocek. Pięć wzorców workflow. I dopiero na końcu — pełni agenci, dla open-ended tasks. Najważniejszy meta-message: zacznij od najprostszego rozwiązania.
-->

---

# Workflow vs Agent

| Workflow | Agent |
|----------|-------|
| Predefined paths | Self-directed loop |
| Predictable | Open-ended |
| Tania | 4× tokens vs chat |
| **Most production needs** | Long-horizon, ambiguous |

> *„An agent is just a model in a loop with tools — that's the whole architecture."*
> — Anthropic, Building Effective Agents

<!--
Workflow ma predefined ścieżki — flow zna deweloper. Agent ma loop z tools — flow zna sam siebie. Większość production needs to workflows, nie agents. Agents są dla open-ended, gdzie nie da się zakodować ścieżki. Cytat: agent to LLM w pętli z narzędziami. Tyle.
-->

---

# Karpathy: LLM = CPU

```
LLM           = CPU
Context window = RAM
Agent runtime  = OS
Tools          = system calls
Memory         = filesystem / virtual memory
```

> *„Context engineering is the delicate art and science of filling the context window with just the right information for the next step."*
> — Andrej Karpathy

<!--
Karpathy mental model. LLM to CPU. Context window to RAM. Agent runtime to system operacyjny. Tools to system calls. Memory to filesystem albo virtual memory. Ten model przyda się dla audiencji JVM-owej w kilku miejscach.
-->

---

# Case study: kNES + FF1

- **kNES** — emulator NES w Kotlinie. KotlinConf 2025 talk.
- **FF1 (1987)** — perfect agent sandbox:
  - Long-horizon (godziny gameplay)
  - Partial observable (RAM + ekran)
  - Deterministic state
  - Real cost model (Anthropic API)
- **Cel:** dotrzeć do Garlanda — boss Chaos Shrine.
- **Stack:** Kotlin + Koog (JetBrains) + Anthropic Claude.

<!--
Cały talk będę ilustrował case study'em z mojego repo. kNES to emulator NES w Kotlinie, pokazywałem na KotlinConf 2025. Final Fantasy 1 z 1987 jest idealnym sandboxem dla agentów: długi horyzont, partial observability, deterministyczny stan, prawdziwy cost model. Cel: dotrzeć do bossa Chaos Shrine. Wszystko w Kotlinie z Koog od JetBrains.
-->

---

# Pierwszy prototyp: Claude Code

- Najpierw chciałem grać JoyConami zamiast klawiaturą.
- Potem REST API. Potem MCP. Potem **Claude Code wziął pada**.
- **Każda warstwa = jeden wieczór pracy.**

> *„Mając własny emulator, masz pełny kontrakt — input, output, RAM, czas. Możesz wywiesić go na czymkolwiek."*

**Game changer:** posiadanie własnego emulatora pozwoliło mi traktować input jako abstrakcję, nie keyboard event.

<small>[JVM Weekly vol. 172](https://www.jvm-weekly.com/p/claude-plays-final-fantasy-just-before)</small>

<!--
Zanim w ogóle pomyślałem o własnym agencie w Kotlinie — pierwszy prototyp był na Claude Code przez MCP. Jeszcze wcześniej — chciałem grać JoyConami przez Bluetooth. Potem REST API na porcie 6502 przez Ktor. Potem MCP server jako thin proxy nad tym REST. Potem Claude Code wziął pada i zaczął grać Final Fantasy 1. Stworzył party Fighter/Thief/Black Belt/Red Mage, walczył z pięcioma IMPami na overworldzie. Każda z tych warstw zajęła mi jeden wieczór. Bo mając własny emulator, masz pełny kontrakt: input, output, RAM, czas — możesz wywiesić to na czymkolwiek.
-->

---

# `ControllerProvider` — abstrakcja, która wszystko otworzyła

```kotlin
interface ControllerProvider {
    fun pollInput(): InputState
}
```

Implementacje:
- `KeyboardControllerProvider` — Z, X, Enter, Space, arrows
- `JoyConControllerProvider` — Switch JoyCon over Bluetooth
- `RestApiControllerProvider` — `POST /step` z curl
- `McpControllerProvider` — Claude Code wpisuje przyciski przez stdio
- `AgentControllerProvider` — mój własny agent w Koog

**Jeden interface. Pięć źródeł inputu. Każde dodane w jeden wieczór.**

<!--
To jest najważniejszy slajd o tym, dlaczego mając własny emulator wygrywasz. ControllerProvider to interface z jedną metodą. CPU 6502 nie wie skąd przychodzi input. Może być z klawiatury, JoyCona, REST API, MCP, mojego własnego agenta. Każda implementacja to wieczór pracy. To dosłownie hexagonal architecture applied do emulatora. Bez własnego emulatora bym nie mógł tego zrobić — Mesen, FCEUX, jakikolwiek inny mainstream emulator NES nie ma takiego API.
-->

---

<!-- _class: divider -->

# Akt II

## Tools matter more than prompts

---

<!-- _class: quote -->

> *„We spent more time optimizing tools than the prompt."*

— Anthropic, Building Effective Agents (Dec 2024)

> *„If a human engineer can't say which tool to use, an AI agent can't either."*

— Anthropic, Writing Effective Tools (Sept 2025)

<!--
To są dwa najważniejsze cytaty całego talku. Anthropic publicznie powiedziało: na SWE-bench więcej zarobili optymalizując tool descriptions niż prompty. I zasada: jeśli człowiek nie wie którego narzędzia użyć, agent też nie. Granularity i clarity narzędzi to wszystko.
-->

---

# Macro vs Micro split

```
┌──────────────────────────────────────┐
│ MACRO  →  LLM                        │
│   • Decisions                        │
│   • Planning                         │
│   • Non-deterministic by design      │
└─────────────┬────────────────────────┘
              │ Tool API contract
              ▼
┌──────────────────────────────────────┐
│ MICRO  →  Scripted code              │
│   • Execution                        │
│   • Walking, parsing, validation     │
│   • Deterministic, JUnit-able        │
└──────────────────────────────────────┘
```

**2025-2026 consensus:** fewer, higher-leverage, well-described tools + long tail przez code execution.

<!--
Uniwersalna zasada w 2025-2026. Macro decyzje robi LLM, mikro execution robi scripted code. Pomiędzy nimi tool API contract. Anthropic, OpenAI, Cursor, Devin — wszyscy konwergują na tym samym: mniej narzędzi, lepszych, plus long tail przez code execution.
-->

---

# Voyager (NVIDIA, 2023) — skill library

- LLM pisze JS funkcje, indexed by **embedding of NL description**
- Top-5 retrieval → injected w prompt → exec feedback + self-verification
- **Wyniki vs ReAct/Reflexion/AutoGPT:**
  - 3.3× more unique items
  - **15.3× faster wood, 8.5× stone, 6.4× iron**
  - Tylko Voyager dotarł do diamond level
- Ablation: bez self-verification −73%, bez curriculum −93%

<small>[arXiv 2305.16291]</small>

<!--
Voyager z 2023. Skill library. LLM pisze JavaScript funkcje, indexed by embedding opisu. Top-5 retrieval, exec feedback, self-verification. Wyniki: 15-krotnie szybciej do drewna w Minecrafcie. Tylko Voyager dotarł do diamentu. To jest moment, w którym społeczność zrozumiała, że skill library to game-changer.
-->

---

# Hershey: Claude Plays Pokémon — TYLKO 3 narzędzia

```
update_knowledge_base   ← self-managed memory
use_emulator            ← button press
navigator               ← „idź do (x,y)"
```

> *„I've actually stripped out complexity over time, not added it."*
> — David Hershey, Anthropic

- Navigator abstrahuje pathfinding zamiast 13 button-tools
- Knowledge base persists across summarization

<small>[Latent Space, MLOps Community podcast]</small>

<!--
Anthropic's własny Claude Plays Pokémon. David Hershey. Tylko trzy narzędzia. I jego cytat, kluczowy: stripped out complexity over time, NOT added. Nie rozbudowuje, upraszcza. Navigator abstrahuje pathfinding — agent nigdy nie planuje pojedynczych button presses. Knowledge base persists przez summarization. To jest minimum viable agent dla gier.
-->

---

# FF case: 13 raw tools — V1 mojego agenta

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
**$20–50 / attempted run.**

<!--
V1 mojego agenta. 13 raw tools — step, tap, sequence, press, release, getState, getScreen i tak dalej. Executor zapętlał się analizując RAM. Iteration 10 — exception. Zero akcji w grze. $20-50 za jedną próbę dotarcia do Garlanda. Klasyczny anti-pattern z Hersheyowej listy.
-->

---

# Tool design heuristics — tier list

| Tier | Practice |
|------|----------|
| **S** | Right abstraction beats more tools |
| **S** | Tool descriptions are prompts — eval them |
| **A** | Code-as-action when actions compose (CodeAct: +20%) |
| **A** | Progressive disclosure (Anthropic Skills, MCP) |
| **B** | Deterministic backbone + LLM at decision points |

**Anti-patterns:**
- ⚠️ >100 tools w kontekście (OpenAI in-distribution boundary)
- ⚠️ Tool descriptions <40 słów / vague verbs
- ⚠️ Mega-tool z 20+ optional kwargs
- ⚠️ Tool name overlap bez namespacing

<!--
Tier list praktyk. S: właściwa abstrakcja, descriptions jako prompts. A: code-as-action gdy akcje się komponują, progressive disclosure. B: deterministyczny backbone z LLM w decyzjach. Cztery klasyczne anti-patterns: ponad 100 tools w kontekście, descriptions krótsze niż 40 słów, mega-tool z 20 optional kwargs, name overlap bez namespacing.
-->

---

# Anthropic Code Execution z MCP (Nov 2025)

**Problem:** loading raw MCP schemas → **150K tokenów kontekstu**.

**Rozwiązanie:** wrap MCP servers as code APIs, model writes code.

| | Before | After |
|---|--------|-------|
| Context | 150K tok | **2K tok** |
| Speed | baseline | **+60%** |
| Reduction | — | **98.7%** |

<small>[anthropic.com/engineering/code-execution-with-mcp]</small>

<!--
Listopad 2025. Anthropic publikuje przełomowy post. Loading raw MCP schemas wybijał 150 tysięcy tokenów kontekstu. Wrappują MCP servers jako code APIs i każą modelowi pisać code, który je woła. Wynik: 98.7% redukcji kontekstu. 60% szybciej. To jest evolution Hersheyowej zasady: zamiast tools jako JSON, tools jako biblioteka.
-->

---

<!-- _class: divider -->

# Akt III

## Architecture: workflow → agent → multi-agent

---

# 5 patterns Anthropica + kiedy

```
Prompt Chaining     → predictable sequential tasks
Routing             → classify → dispatch to specialists
Parallelization     → independent subtasks
Orchestrator-Workers → plan + delegate (research, ETL)
Evaluator-Optimizer → iterative refinement (writing, code)
```

> Multi-agent dopiero gdy **value of task > token cost.**

<!--
Pięć wzorców. Każdy ma swój use case. Chaining dla predictable sequential. Routing dla klasyfikacji. Parallelization dla niezależnych subtasks. Orchestrator-Workers — to nasz Advisor pattern, dobre dla research i ETL. Evaluator-Optimizer dla writing i code. I uwaga: multi-agent dopiero kiedy wartość taska przewyższa token cost.
-->

---

# Anthropic multi-agent research system (Jun 2025)

**Architecture:** Lead agent (Opus) decomposes → subagents (Sonnet) parallel → synthesis.

**Wynik:**

| | Single Opus | Multi-agent |
|---|---|---|
| Performance | baseline | **+90.2%** |
| Tokens | 4× chat | **15× chat** |

> *„Token usage alone explains 80% of performance variance."*

> *„Coding is harder to parallelise than research — subtasks are tightly coupled."*

<small>[anthropic.com/engineering/multi-agent-research-system]</small>

<!--
Czerwiec 2025. Anthropic ujawnia architekturę swojego research systemu. Lead Opus, subagenci Sonnet. Ninedziesiąt procent lepiej niż single Opus. Ale piętnastokrotnie więcej tokenów niż chat. Token usage tłumaczy 80% wariancji performance. Drugi cytat ważny: coding jest trudniejszy do parallelizacji niż research. To bezpośrednio prowadzi do następnego slajdu.
-->

---

# Cognition: „Don't Build Multi-Agents" (Jun 2025)

**Two principles:**

1. **Share context** — full traces, not just messages
2. **Decisions, not messages, are what conflict**

**Flappy Bird parable:**
- Subagent A → robi Mario-style background
- Subagent B → robi photoreal bird sprite
- Każdy element OK. **Całość — niespójna.**

> Default: **single-threaded linear architecture.**

<small>[cognition.ai/blog/dont-build-multi-agents]</small>

<!--
Tydzień po Anthropicu Cognition publikuje przeciwny post. Walden Yan. Dwie zasady: share context, share full traces, NOT just messages. I głębsza: decisions, not messages, are what conflict. Flappy Bird parable — sub-agent A robi Mario tło, B robi photoreal bird. Każdy element jest OK, ale całość się nie składa. Bo nie podzielili się decyzjami, tylko wynikami. Default Cognition: single-threaded linear.
-->

---

# Resolution: it depends

| Task | Recommendation |
|------|----------------|
| Research, parallel-friendly, read-heavy | **Multi-agent** (Anthropic +90.2%) |
| Coding, tightly-coupled writes | **Single-threaded harness** (Cognition) |
| Conversational, low-value | **Single LLM augmented** |
| Predictable flow | **Workflow, not agent** |

**Cognition Apr 2026 follow-up:** narrower class works — agents *contribute intelligence*, writes stay single-threaded.

<!--
Resolution: zależy od taska. Research, parallel-friendly — multi-agent. Coding, tightly-coupled — single-threaded. Conversational, low-value — single LLM. Predictable flow — workflow, nie agent. Cognition w kwietniu 2026 wraca z follow-upem: węższa klasa multi-agent działa, gdzie sub-agenci dostarczają intelligence, a writes pozostają single-threaded.
-->

---

# Anthropic three-agent harness (Mar 2026)

```
┌──────────┐      ┌───────────┐      ┌──────────┐
│ Planner  │─────▶│ Generator │─────▶│Evaluator │
└──────────┘      └───────────┘      └──────────┘
                       │                    │
                       ◀────── feedback ────┘
```

- Multi-hour autonomous coding
- **Separating worker from judge** mitigates self-evaluation bias
- Failure mode: agents try to *„one-shot the app"* → burning whole context on half-implementation

<small>[anthropic.com/engineering/harness-design-long-running-apps]</small>

<!--
Marzec 2026. Anthropic publikuje three-agent harness dla long-running coding. Planner / Generator / Evaluator. Kluczowa zasada: agent który robi pracę nie powinien sam siebie oceniać. To redukuje self-evaluation bias. Identyfikują też klasyczny failure mode: agents próbują one-shot the app, wypalają context na half-implementacji.
-->

---

# FF case: Advisor + Executor

```kotlin
// Advisor — Opus, read-only, single-shot
class AdvisorAgent {
    suspend fun plan(phase, observation): String  // numbered plan
}

// Executor — Sonnet/Haiku, full tools, single tool/turn
class ExecutorAgent {
    suspend fun invoke(plan, observation): SkillResult
}

// Outer loop u nas
while (not done) {
    if (phase changed || idleTurns >= 20) {
        currentPlan = advisor.plan(phase, observation)
    }
    result = executor.invoke(currentPlan, phase)
}
```

**Read-only contract** = invariant. Advisor nigdy nie mutuje stanu.

<!--
Mój case study. Advisor — Opus, read-only, single-shot. Executor — Sonnet lub Haiku, full tools, jeden tool per turn. Outer loop u mnie. Advisor triggered na phase change albo bezruch. Read-only contract dla advisora to invariant — to jest exactly to, co Cognition i Anthropic mówią: separation of writers vs decision-makers.
-->

---

<!-- _class: divider -->

# Akt IV

## Memory: short term vs long term

---

# Context Rot — long context to nie magic

**Chroma Research 2024-2025:** tested 18 SOTA models (GPT-4.1, Claude 4, Gemini 2.5, Qwen3).

- Performance degrades **non-uniformly** as input grows
- Distractors, semantic similarity, haystack structure all matter
- **NIAH passing ≠ long context working**

> *„NIAH underestimates what most long-context tasks require in practice."*

<small>[research.trychroma.com/context-rot]</small>

<!--
Chroma Research. Bardzo ważny post. Przetestowali 18 SOTA modeli. Performance degraduje się non-uniformly z wielkością inputu. Distractors, podobieństwo do needle, struktura haystacka — wszystko ma znaczenie. Najważniejszy take-away: passing needle in haystack to NIE jest dowód, że long context działa. Wprowadzają termin „context rot".
-->

---

# Anthropic 3 primitives — context engineering (Sept 2025)

| Primitive | API | Co robi |
|-----------|-----|---------|
| **Compaction** | `compact_20260112` | Summarize at threshold |
| **Tool clearing** | `clear_tool_uses_20250919` | Drop old outputs, keep call record |
| **Memory tool** | `memory_20250818` | Filesystem-based persistent notes |

**Cookbook benchmark:** peak context **335K → 169K (compact) → 173K (clearing)**.

> *„Find the smallest possible set of high-signal tokens that maximize likelihood of desired outcome."*

<!--
Wrzesień 2025. Anthropic publikuje effective context engineering. Trzy primitives jako API: compaction, clearing, memory tool. W ich cookbooku peak context dropuje z 335K do 169-173K. Cytat to jest definicja context engineeringu: znaleźć najmniejszy zbiór high-signal tokens.
-->

---

# Memory architectures: MemGPT → Mem0 → Zep

**MemGPT** (Packer 2023, OS-inspired)
- Core (RAM) / archival (disk) / recall (history)
- DMR: **93.4% vs 35.3%** summarization baseline

**Mem0** (2025, hybrid vector+graph+KV)
- LOCOMO: **91% latency cut, 14× token cut** at near-parity accuracy

**Zep** (2025, temporal knowledge graph)
- LongMemEval: **63.8% vs Mem0's 49.0%**

> Mental model dla JVM: virtual memory dla LLMs. Same paging, same tradeoffs.

<small>[arXiv 2310.08560, 2504.19413, 2501.13956]</small>

<!--
Trzy memory frameworki, które warto znać. MemGPT z 2023, OS-inspired hierarchy. 93% vs 35% baseline na DMR benchmarku. Mem0 z 2025: hybrid vector plus graf plus KV. 91% latency cut przy 14-krotnie mniej tokenach. Zep — temporal knowledge graph. 64% vs 49% Mem0 na LongMemEval. Mental model dla JVM ludzi: to jest virtual memory dla LLM. Te same paging tradeoffs co znamy z systemów operacyjnych.
-->

---

# Sleep-time compute (Letta + UC Berkeley, 2025)

**Idea:** pre-compute context BEFORE queries arrive.

- **5× less test-time compute** for same accuracy (Stateful GSM-Symbolic, AIME)
- **2.5× cheaper amortized** across queries on same context

> *„By doing the thinking offline, before user arrives, we cut test-time compute by 5×."*

> Idle compute is free — use it.

<small>[arXiv 2504.13171]</small>

<!--
Sleep-time compute z Letty i Berkeley. Genialny insight. Pre-compute na kontekście, ZANIM przyjdzie zapytanie. Pięciokrotnie mniej test-time compute przy tej samej accuracy. Dwukrotnie pół taniej amortized w wielu queries na tym samym kontekście. Cytat: thinking offline daje 5x redukcję test-time. To jest argument ekonomiczny: idle compute jest free, użyj go.
-->

---

# Anthropic Memory Tool + Dreaming (2025-2026)

**Memory tool** (Sept 2025): filesystem-based persistent. Per-user, exportable.

**Dreaming** (Apr 2026): scheduled background process consolidates past sessions:
- Surfaces recurring mistakes
- Captures team preferences
- Identifies shared workflows

**Wisedocs case study:**
- 97% reduction in first-pass errors
- 30% speed-up

<small>[anthropic.com/engineering/managed-agents]</small>

<!--
Anthropic ma to teraz jako API. Memory tool — filesystem persistent, per-user. Dreaming — to jest sleep-time compute dorobione produktowo. Background process konsoliduje past sessions, surface'uje recurring mistakes, ustawienia teamu, shared workflows. Real case: Wisedocs dostał 97% redukcji first-pass errors i 30% speed-up.
-->

---

# FF case: 5 JSON files, atomic writes

```
~/.knes/
├── ff1-overworld-terrain.json
├── ff1-landmarks.json          ← NPC król, sklepikarz
├── ff1-blockages.json          ← append-only log porażek
├── ff1-overworld-warps.json
└── ff1-interior-memory.json
```

- **Atomic writes**: `*.tmp + fsync + rename` (PR #114)
- 3× JVM padło mid-write — kampania utracona
- Memory rośnie monotonicznie cross-session
- **Phase 2 advisor** czyta `LandmarkContext` → decisions w 1-2 turach

<!--
Mój case study na memory. Pięć plików JSON. Atomic writes — bo trzy razy w jeden dzień JVM padał mid-write i tracilem całą kampanię. Memory rośnie monotonicznie, nigdy nie kasuję. Phase 2 advisor dostaje LandmarkContext bezpośrednio w prompcie i podejmuje decyzje w 1-2 turach zamiast 10-20.
-->

---

<!-- _class: divider -->

# Akt V

## Planning: short vs long horizon

---

# ReAct — działa, ale diverguje

**Yao 2022:** interleave reasoning + actions. AlfWorld **+34%** vs imitation/RL.

**Failure modes (TDS analysis 2025):**
- Hallucinated tool names: **90.8% wasted retries**
- **18% rate of „thinking instead of acting"** when history > 2

> *„ReAct without external termination signal **diverges** on long-horizon partially-observable tasks."*
> — Reflexion paper, 2023

<small>[arXiv 2210.03629; arXiv 2303.11366]</small>

<!--
ReAct z 2022. Działa świetnie na krótkich taskach. AlfWorld +34% nad baselines. Ale ma realne failure modes. Halucynowane tool names palą trzy retries każdy = 91% wasted retries. 18% steps to „thinking instead of acting" gdy history > 2. To są liczby z 2025. Reflexion paper to opisuje formalnie: ReAct bez external termination diverguje na long-horizon tasks.
-->

---

# Reflexion — verbal RL (Shinn 2023)

**Idea:** agent reflects on failures, stores reflection w episodic memory buffer.

| Benchmark | Reflexion | Baseline |
|-----------|-----------|----------|
| AlfWorld | **130/134 (97%)** | +22% over baseline |
| HumanEval pass@1 | **91%** | GPT-4 = 80% |
| HotPotQA | +20% | — |

> Self-criticism + episodic memory = compounding learning.

<small>[arXiv 2303.11366]</small>

<!--
Reflexion. Verbal reinforcement learning. Agent reflektuje nad porażkami, zapisuje do episodic memory bufora. AlfWorld 130 z 134 tasków. HumanEval 91% pass@1, podczas gdy goły GPT-4 daje 80%. Self-criticism plus memory daje compounding learning. To jest podstawowy prymityw, którego brakuje w naive ReAct.
-->

---

# Tree of Thoughts + LATS

**ToT** (Yao 2023): search tree of thoughts with backtracking.

- Game of 24: GPT-4 + CoT solves **4%**. ToT solves **74%**. **18× improvement.**

**LATS** (Zhou 2023): MCTS + LLM value function + Reflexion.

- HumanEval w/ GPT-4: **94.4%**
- WebShop w/ GPT-3.5: **75.9 avg**
- HotPotQA EM: up to **0.61**

<small>[arXiv 2305.10601, 2310.04406]</small>

<!--
Tree of Thoughts. Search tree z backtracking. Na Game of 24 — GPT-4 z chain of thought rozwiązuje 4%, ToT rozwiązuje 74%. Osiemnastokrotnie. Dla problemów combinatorial. LATS to ToT plus MCTS plus Reflexion. HumanEval 94%. WebShop 76. To są tools dla tasków z reversible decisions, nie dla agent-w-świecie który nie może un-pressować przycisku.
-->

---

# FF case: skill library + Advisor pattern

**Advisor produkuje plan na poziomie skill names:**

```
1. pressStartUntilOverworld
2. walkOverworldTo(146, 152)        ← Coneria Castle entry
3. exploreInteriorFrontier          ← do King's throne
4. exitInterior
5. walkOverworldTo(...)             ← Chaos Shrine
```

**Executor:** wybiera 1 skill per turn (`singleRunStrategy`).

**Skill** = scripted Kotlin code, **ZERO tokenów LLM w środku.**

> Critical: granularity narzędzi decyduje czy advisor pattern działa.

<!--
Mój case na planning. Advisor wykonuje plan na poziomie skill names — wysokopoziomowych. Executor wybiera jeden skill per turn dzięki singleRunStrategy. Każdy skill to scripted Kotlin code z zero tokens LLM w środku. Critical insight: granularity narzędzi decyduje czy advisor pattern w ogóle działa. Za niska granularity i advisor planuje button presses zamiast celów.
-->

---

<!-- _class: divider -->

# Akt VI

## Production: war stories i lessons

---

# $47k loop — alerts ≠ enforcement

**Anti-pattern:** budget alerts zamiast enforcement.

**Fix:**
```kotlin
// Pre-call interceptor
fun intercept(call: AgentCall): AgentCall {
    if (session.tokensUsed >= session.budget) {
        throw BudgetExceededException()  // BEFORE the call
    }
    return call
}
```

**JVM mapping:** Resilience4j circuit breaker dla tokenów.
- Per-session, per-agent, per-tenant caps
- Hard cap retries (jittered exponential backoff)
- Idempotency keys

<!--
$47k loop. Anti-pattern: budget alerts zamiast enforcement. Fix: pre-call interceptor, który rzuca BudgetExceeded ZANIM zrobi API call. JVM mapping: Resilience4j circuit breaker dla tokenów. Per-session, per-agent, per-tenant. Hard cap retries z jittered exponential backoff. Idempotency keys żeby retries nie multiplikowały effects.
-->

---

# Replit + terraform — read-only by default

**Anti-pattern:** agent ma direct write access do produkcji.

**Fix:**
- Read-only by default
- Destructive verbs require **human approval gate**
- Forkable dev environments
- **Deletion protection** at infra layer

**JVM mapping:** Spring Security `@PreAuthorize` na tool invocations.

```kotlin
@Tool
@PreAuthorize("hasRole('AGENT_DESTRUCTIVE')")
suspend fun deleteResource(id: String): Result
```

<!--
Replit i terraform stories. Anti-pattern: agent ma direct write access do produkcji. Fix to read-only by default, destructive verbs wymagają human approval gate, forkable dev environments, deletion protection at infra layer. JVM ekwiwalent: Spring Security @PreAuthorize na tool invocations. Każde destructive narzędzie wymaga explicit role.
-->

---

# Air Canada — legal liability

**Feb 2024:** chatbot wymyślił bereavement-fare policy.

**BC Civil Resolution Tribunal:**

> *„Companies remain liable for information provided by an AI chatbot."*

**Lesson:** każda customer-facing odpowiedź agenta = potencjalny kontrakt.
- Grounding contracts
- Retrieval-only generation dla policy answers
- Human escalation paths

**Klarna 2025:** zastąpili 700 agentów AI → repeat-contacts wzrosły → CEO admitted „cut too aggressively" → rehiring.

<!--
Air Canada. Luty 2024. Chatbot wymyślił bereavement-fare policy. Tribunal w British Columbia: companies remain liable. Każda customer-facing odpowiedź to potencjalny kontrakt. Grounding contracts. Retrieval-only generation dla policy. Human escalation. Klarna paralela: zastąpili 700 agentów AI, repeat contacts wzrosły, CEO przyznał że cięli za agresywnie, znów zatrudniają ludzi. Lesson: agents jako filter plus escalation, NIE replacement.
-->

---

# Cost optimization — 2025-2026

**Prompt caching** ([ProjectDiscovery prod](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching)):
- 59% → 70% input savings, 74% cache hit rate
- Anthropic limits: **4 breakpoints**, 20-block lookback, 5-min default TTL

**Batch API:** 50% off, ~30 min SLA. Stacks z caching → ~5% standard cost.

**Model routing:** Cursor Auto picks cheapest viable per step.

```
Boot/NameEntry  → Sonnet
Overworld/Battle → Haiku
Watchdog escalation → Opus
```

<!--
Cost optimization. Prompt caching. ProjectDiscovery w produkcji: 59 do 70% input savings, 74% cache hit rate. Anthropic limits: 4 breakpoints, 20-block lookback. Batch API 50% off, 30 min SLA. Stackuje z cachingiem do około 5% standardowego kosztu. Model routing: Cursor Auto wybiera najtańszy działający per step. W moim agencie: Sonnet do uncertain phases, Haiku do oczywistego, Opus tylko do watchdog escalation.
-->

---

# Sandboxing

**Anthropic Claude Code** (2025): OS-level isolation (bubblewrap / seatbelt).
- **84% reduction in permission prompts** internally

**Docker Sandboxes** (Nov 2025): microVMs (Firecracker / Kata) per session.
- Standard containers ≠ sandboxes (shared kernel)
- microVM = osobny kernel = container-escape ≠ host pwn

**JVM context:** SecurityManager deprecated. Agent world re-discovers capability-based security.

<!--
Sandboxing. Anthropic Claude Code używa OS-level isolation — bubblewrap na Linuxie, seatbelt na Macu. Wewnątrz: 84% redukcji permission prompts. Docker Sandboxes z listopada 2025: microVM per agent session, Firecracker albo Kata. Standardowe kontenery to NIE sandbox — dzielony kernel. JVM context: SecurityManager deprecated. Agentowy świat reodkrywa capability-based security przez microVM.
-->

---

# Observability + evals

**Anthropic Demystifying Evals:**
- Start z **20 hand-graded examples** before any framework
- Track jako quality metrics: **step count, tokens, wall-clock, $ cost**

> *„An 8-step $0.12 solve is strictly better than 40-step $1.80."*

**LangChain State of Agent Engineering 2025:**
- 57.3% orgs run agents in prod (67% z 10k+)
- Top pain: **quality, cost, observability**

<small>[langchain.com/state-of-agent-engineering]</small>

<!--
Observability i evals. Anthropic Demystifying Evals — zacznij od 20 hand-graded examples zanim sięgniesz po framework. Trackuj step count, tokens, wall-clock i cost jako pierwsze klasy quality metrics. Cytat: 8-stepowy solve za 12 centów jest strictly lepszy od 40-step solve za 1.80. LangChain raport 2025: 57% organizacji ma agents w prod, 67% w dużych firmach. Top pain points: quality, cost, observability — w tej kolejności.
-->

---

<!-- _class: divider -->

# Akt VII

## 10 lessons + JVM mapping

---

# 10 lessons + JVM equivalents

| # | Lesson | JVM equivalent |
|---|--------|----------------|
| 1 | Determinism is your moat | Resilience4j, Bean Validation |
| 2 | Decisions > messages (Cognition) | Event sourcing |
| 3 | Cost is a correctness property | SLO/SLA budgets per request |
| 4 | Tools matter more than prompts | API design, OpenAPI |
| 5 | Context is finite — engineer it | Heap mgmt, JIT loading |
| 6 | Multi-agent only when parallelizable | Don't microservice everything |
| 7 | Read-only contracts (Advisor) | Spring `@PreAuthorize` |
| 8 | Persistent memory > re-discovery | DB > computed cache |
| 9 | Sandbox + budget enforcement | Bulkhead, circuit breaker |
| 10 | Eval first, ship second | TDD; can't optimize what you don't measure |

<!--
Dziesięć lessonów. Każdy ma JVM equivalent. Bo agentowe programowanie nie jest nową dziedziną — to jest discipline, którą znamy z distributed systems, microservices, security. Tylko aplikujemy do niedeterministycznego callera. Ten slajd zostaje na zdjęciu.
-->

---

<!-- _class: title -->

# Garland nadal stoi w Chaos Shrine

## Ale architektura jest gotowa

**Advisor + Executor + Skills + Persistent Memory + 5 JSON files**

`github.com/ArturSkowronski/kNES`

> Każda decyzja w tym kodzie ma swój odpowiednik w jednym z papierów / postów dziś pokazanych.

**Pytania.**

<!--
Garland nadal stoi w Chaos Shrine. Mój agent doszedł do throne roomu Króla, zna swoje warpy, zna swoje porażki. Ale architektura jest gotowa. Każda decyzja w tym kodzie — czy to Advisor pattern, czy persistent memory, czy single-threaded write contract — ma swój odpowiednik w jednym z papierów albo postów, które dziś pokazałem. Anthropic upraszcza, Cognition ostrzega, Voyager pokazuje skill libraries, Mem0 mierzy. Architektura ponad model. Repo open-source. Pytania.
-->

---

<!-- _class: divider -->

# Q&A

---

# Najczęstsze pytania (backup)

**Q: Multi-agent yes czy no?**
Zależy od parallelism. Research: Anthropic +90.2%. Coding: Cognition mówi single-threaded.

**Q: Czy long context zabija RAG?**
Nie. Chroma context-rot: NIAH passing ≠ working. Hybrid wins.

**Q: Najlepszy stack 2026?**
There isn't. Anthropic upraszcza, Cursor RL-ed Composer, Devin używa playbooks. Match to task.

**Q: Czemu Koog?**
JVM-native, typed `@Tool`, `createAgentTool` natywnie. JetBrains używa go w IDEA.

**Q: LLM vs RL?**
PokeRL bije każdy LLM-bot na speed. Different use case: zero-shot, dialog, no training.

---

# Bibliography (key 15)

**Anthropic:**
- [Building Effective Agents](https://www.anthropic.com/research/building-effective-agents)
- [Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system)
- [Writing effective tools](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [Code execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp)
- [Three-agent harness](https://www.anthropic.com/engineering/harness-design-long-running-apps)

**Critique:**
- [Cognition: Don't Build Multi-Agents](https://cognition.ai/blog/dont-build-multi-agents)
- [Chroma: Context Rot](https://research.trychroma.com/context-rot)

**Papers:**
- [Voyager](https://arxiv.org/abs/2305.16291) · [Reflexion](https://arxiv.org/abs/2303.11366) · [ToT](https://arxiv.org/abs/2305.10601)
- [MemGPT](https://arxiv.org/abs/2310.08560) · [Mem0](https://arxiv.org/html/2504.19413v1) · [Sleep-time](https://arxiv.org/abs/2504.13171)
- [MAST: Why Multi-Agent Fails](https://arxiv.org/abs/2503.13657)

**Production:**
- [$47k loop](https://dev.to/waxell/the-47000-agent-loop-why-token-budget-alerts-aren-t-budget-enforcement-389i) · [Replit DB](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/) · [Air Canada](https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-february/bc-tribunal-confirms-companies-remain-liable-information-provided-ai-chatbot/)

---

# Dziękuję!

**Artur Skowroński**

`github.com/ArturSkowronski/kNES`

Slides + spec docs + handoff notes w repo.
Każda decyzja udokumentowana razem z porażką, która do niej doprowadziła.
