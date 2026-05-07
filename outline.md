# Geecon 2026 — Iteracje agenta gracza FF1, czyli krótka historia agentowego programowania

**Talk:** *„Każdy problem, na który wpadłem, miał swój odpowiednik w literaturze. Nie ja go wymyśliłem."*
**Format:** 45 min talk + 10 min Q&A
**Audiencja:** JVM developers / praktycy
**Through-line:** **problem → rozwiązanie → nowy problem**, sześć iteracji. Case study = kNES + FF1 (Kotlin + Koog + Anthropic). Każde rozwiązanie generalizuje się do znanego wzorca.
**Repo:** github.com/ArturSkowronski/kNES

---

## Hook — „Pierwsza próba: $30 i ani jednego naciśnięcia przycisku" (0:00 – 2:00) | 2 min

- Pokażę dziś **6 iteracji** agenta grającego w Final Fantasy 1.
- Każda iteracja zaczyna się od konkretnego problemu, który mnie blokował.
- Każde rozwiązanie generalizuje się do wzorca, który ktoś już opisał — Anthropic, NVIDIA, Cognition, Berkeley.
- Każde rozwiązanie odsłania **nowy problem**, którego wcześniej nie widziałem.

> Ta pętla — problem, rozwiązanie, nowy problem — to nie jest mój zwyczaj. To jest **definicja agentowego programowania w 2026**.

Setup: kNES + FF1, Kotlin, Koog (JetBrains), Anthropic. Cel: dotrzeć do Garlanda — bossa Chaos Shrine.

---

## Loop 1 — Naive ReAct (2:00 – 7:00) | 5 min

### Problem
Pierwszy agent: Advisor (Opus, single-shot) + Executor (Sonnet, **`reActStrategy`**, max 10 iter, 13 raw narzędzi).

Live trace V1:
```
iter 1: getState() → ram dump
iter 2: "Let me analyze: goldLow=144 + goldMid×256 = 400 GP..."
iter 3: getState() ← AGAIN
iter 4: "Looking at this more carefully..."
...
iter 10: AIAgentMaxNumberOfIterationsReachedException
```

**Executor nie nacisnął przycisku.** $30 / próbę.

### Generalizacja
- **Yao 2022 (ReAct paper §6):** *„Reasoning stuck on hallucinated facts; action repetition when observation doesn't move state."*
- **Reflexion paper (Shinn 2023) §4.1:** *„ReAct without external termination signal **diverges** on long-horizon partially-observable tasks."*
- **Anthropic Claude Plays Pokémon:** Hershey obserwuje to samo — agent stuck in Mt. Moon for 78 hours.
- **TDS analysis 2025:** tool-name hallucination = **90.8% wasted retries**; **18% rate of „thinking instead of acting"** when history > 2.

### Rozwiązanie
- Outer loop wraca do nas: **`singleRunStrategy`** (Koog) — jeden LLM call → koniec tury.
- Watchdog na `idleTurns >= 20`, phase boundary detection przez RAM observer.
- *Anthropic principle:* **„Find the simplest solution. Add complexity only when needed."**

### Nowy problem
Agent przestał divergować, ale nadal nie commituje akcji w grze. Wybiera `step([RIGHT], 16)` zamiast wysokopoziomowych celów. **Co jest narzędziem?**

---

## Loop 2 — Wrong tool granularity (7:00 – 13:30) | 6.5 min

### Problem
Executor z 13 raw narzędziami. Każda decyzja = button-level. Plany są długie i brittle.

### Generalizacja
- **Voyager (Wang, NVIDIA, 2023):** skill library w Minecrafcie. Funkcje JS indexed by embedding. **3.3× more items, 15.3× faster wood, only system to reach diamond.** Ablation: bez self-verification −73%, bez curriculum −93%.
- **Hershey / Claude Plays Pokémon:** TYLKO 3 narzędzia — `update_knowledge_base`, `use_emulator`, `navigator`. Cytat: *„I've actually stripped out complexity over time, not added it."* Navigator abstrahuje pathfinding.
- **Anthropic „Building Effective Agents" (Dec 2024):** *„We spent more time optimizing tools than the prompt."*
- **Anthropic „Writing Effective Tools" (Sept 2025):** *„If a human engineer can't say which tool to use, an AI agent can't either."*
- **CodeAct (Wang ICML 2024):** code-as-action +20% vs JSON tool calls — composition + control flow for free.

### Rozwiązanie
Skill library w FF:
```
pressStartUntilOverworld
walkOverworldTo(x, y)        ← BFS pathfinding
exitInterior                 ← BFS to nearest exit
exploreInteriorFrontier      ← BFS to unvisited tile
battleFightAll               ← 4× FIGHT loop
findPath(x, y)               ← read-only path query
```

Każdy skill = scripted Kotlin. **ZERO tokenów LLM w środku.**

Tool surface 13 → 7 dla Koog. Raw `step/tap/sequence` ukryte.

**Macro/micro split:** LLM = co. Scripted = jak.

Cost spadł z **$20–50 → ~$3** (target). Iteration cap exceptions: zero.

### Nowy problem
Agent dochodzi do peninsuli i tam ginie. Każda iteracja Opusa kosztuje **$1–2 na odkrycie 1–2 warp tiles**. Peninsula ma 6–8 warpów. **Każdy run zaczyna od zera.**

---

## Loop 3 — Memory across sessions (13:30 – 20:30) | 7 min

### Problem
Goldfish-orchestrator. Advisor mądry w tej sesji, niczego nie pamięta na następną. Discovery cost compound.

### Generalizacja
- **Chroma „Context Rot" (2024-2025):** 18 SOTA modeli (GPT-4.1, Claude 4, Gemini 2.5). Performance degrades non-uniformly. **NIAH passing ≠ long context working.** Long context to nie magic bullet.
- **Anthropic „Effective Context Engineering" (Sept 2025):** 3 primitives jako API — **compaction** (`compact_20260112`), **tool-result clearing** (`clear_tool_uses_20250919`), **memory tool** (`memory_20250818`). Cookbook: peak context 335K → 169K (compact) → 173K (clearing).
- **MemGPT (Packer 2023):** OS-inspired hierarchical memory. Core (RAM) / archival (disk) / recall (history). DMR: **93.4% vs 35.3% baseline**.
- **Mem0 (2025):** vector + graph + KV hybrid. LOCOMO: **91% latency cut, 14× token cut** at near-parity accuracy.
- **Zep (2025):** temporal knowledge graph. LongMemEval: **63.8% vs Mem0's 49.0%**.
- **Sleep-time compute (Letta + Berkeley, 2025):** pre-compute on context BEFORE queries. **5× less test-time compute** for same accuracy. **2.5× cheaper amortized.**
- **Anthropic Memory Tool + Dreaming (Sept 2025 / Apr 2026):** Wisedocs case — **97% reduction in first-pass errors, 30% speed-up.**

### Rozwiązanie
Pięć JSON files w `~/.knes/`:
```
ff1-overworld-terrain.json   PLAINS, FOREST, TOWN, CASTLE
ff1-landmarks.json           NPC król, sklepikarz
ff1-blockages.json           append-only LOG PORAŻEK
ff1-overworld-warps.json     znane przejścia
ff1-interior-memory.json     mapId → odwiedzone kafle
```

- **Atomic writes** (`AtomicJsonWriter`, PR #114) — 3× JVM padło mid-write i straciłem kampanię. `write *.tmp + fsync + rename`.
- Memory rośnie monotonicznie cross-session. Advisor nigdy nie zapomina.
- **Phase 2:** advisor dostaje `LandmarkContext.render(landmarkMemory)` w prompcie. Live evidence (HANDOFF.md): advisor output cytuje `(146,152) + 'castle with King/throne room'` verbatim. Decisions w 1–2 turach zamiast 10–20.
- **JVM mental model:** virtual memory dla LLMs. Same paging, same tradeoffs.

### Nowy problem
Advisor jest mądry, ale dalej drogi. Na peninsuli jeszcze raz przepala $1+ żeby zmapować sąsiednie kafle. **Discovery is its own phase — i Opus to zły fit.**

---

## Loop 4 — Separate discovery from execution (20:30 – 26:00) | 5.5 min

### Problem
Drogi advisor mapuje teren. Ale eksploracja terenu to deterministyczny problem. Po co tam Opus?

### Generalizacja
- **Voyager curriculum + skill library separation:** automatic curriculum produkuje hard tasks; skill library reuse'uje rozwiązania. Dwa LOOPY, nie jeden.
- **Anthropic „Code Execution with MCP" (Nov 2025):** loading raw MCP schemas wybijał 150K tokenów kontekstu. Wrappując MCP servers as code APIs i każąc modelowi pisać code → **2K tokenów. 98.7% redukcja. 60% szybciej.**
- **Anthropic „Multi-agent research system" (Jun 2025):** orchestrator-worker. Headline: **+90.2% vs single Opus**. Caveat: **15× tokens.** *„Multi-agent only viable when value of task > token cost."*
- **Macro/micro principle:** discovery to micro. Skoro deterministyczne — niech będzie deterministyczne.

### Rozwiązanie
Dwa procesy, dwa Gradle taski:
```bash
./gradlew :knes-agent:runExplorer   # Phase 1: Haiku, deterministic, mapuje
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
- Cała kampania (10–20 runs): **<$1**. Realnie zmierzone.
- **Salience strategy** (5 priorytetów): warps → known landmarks → viewport tiles → unmapped frontier → cross-run diversification → wander.
- **Blockage feedback loop:** failed `walkOverworldTo` → `Blockage` entry → następny target ten tile filtruje.

Interfejs między Phase 1 i 2: **JSON files na dysku**. Nic więcej.

### Nowy problem
Phase 1 mapuje, Phase 2 planuje. Ale advisor halucynuje koordynaty z training data — *„walk WEST to x=140"* prowadzi prosto w trap. **Ground truth contract.**

---

## Loop 5 — Ground truth, hallucination, vision (26:00 – 31:00) | 5 min

### Problem A — Advisor halucynuje
Iter 1 + iter 2 evidence: plany advisora cytowały koordynaty z FF1 wiki.
- *„Coneria Castle is at (152, 159)"* — to było nieprawda.
- *„Walk WEST to x=140"* — prowadziło party prosto w ukryty interior entry **(145, 152)**, dwa razy z rzędu.

### Problem B — Vision halucynuje
A/B Haiku 4.5 vs Gemini 2.5 Pro na klasyfikacji wnętrz:

| Ekran | Haiku 4.5 | Gemini 2.5 Pro |
|-------|-----------|-----------------|
| mapId=0 void (uszkodzony) | **4 false positives** | `[]` ✓ |
| Castle throne | NPC_KING ✓ + STAIRS_DOWN ✗ | NPC_KING ✓ |

### Generalizacja
- **„The Reasoning Trap" (arXiv 2510.22977, Oct 2025):** reasoning RL-trained models **HAłucynują tools BARDZIEJ**. Mechanistically: late-layer residual streams diverge. Tool selection errors scale with tool count.
- **Anthropic Writing Tools (Sept 2025):** caught Claude appending wrong year to web-search queries. Fixed via description, NOT code.
- **Cognition „Don't Build Multi-Agents":** *„Decisions, not messages, are what conflict."* — implicit assumptions w training data są decyzjami.
- **Hershey CPP system prompt:** *„trust only observed game state, not training-data knowledge of Pokémon."*

### Rozwiązanie
- **GROUND TRUTH ONLY** paragraph w `AdvisorAgent.kt:103-114`:
  ```
  FF1 coordinates from your training data are UNRELIABLE.
  NEVER cite a specific entry-tile coordinate
  unless you can SEE the C/T glyph at that exact tile in the ASCII map.
  ```
- ASCII map renderer (`AsciiMapRenderer`) — **textual viewport** dla advisora. Gemini-PP finding: tile grids match raw screenshots for spatial reasoning at lower cost.
- Vision backend swap przez env var: `KNES_VISION=gemini-pro`.

> **Lesson:** Advisor jest tak dobry, jak jego ground-truth contract.

### Nowy problem
Advisor i executor robią dobre decyzje. Ale gdybym dał advisorowi `loadState()` albo `reset()` — co by się stało? **Production safety.**

---

## Loop 6 — Production safety (31:00 – 37:30) | 6.5 min

### Problem
Hipotetycznie: dałem advisorowi `EmulatorToolset` z pełnym dostępem. On halucynuje, że dla testów warto zrobić `reset()`. Albo `loadState(path)` z null. **Co się dzieje?**

### Generalizacja — real production stories
- **$47k loop (Nov 2025):** 4 LangChain A2A agents, ping-pong, no termination, 11 dni. Mieli alerty na Slacka. *„Token budget alerts aren't budget enforcement. The agent doesn't read Slack."*
- **Replit (Jul 2025):** kasuje prod DB w czasie explicit code freeze. Fabrykuje rollback test results. 1190 firm.
- **Claude Code terraform destroy (2025):** 2.5 roku danych studentów. Saved by ukryty AWS snapshot.
- **Air Canada (Feb 2024):** chatbot wymyślił bereavement-fare policy. **BC Civil Resolution Tribunal: company liable for what its chatbot says.**
- **Klarna 2024-2025:** zastąpili 700 agentów AI → repeat-contacts wzrosły → CEO admit „cut too aggressively" → rehiring.

### Rozwiązanie w FF — read-only contract
```kotlin
// Advisor — read-only, ENFORCED by type
class AdvisorAgent(
    private val toolset: EmulatorToolset,   // FULL toolset wstrzyknięty
    // ...
) {
    private val readOnlyTools = ReadOnlyToolset(toolset)   // ← wrapper
    private val registry = ToolRegistry { tools(readOnlyTools) }
    // Advisor NIGDY nie dostanie pełnego registry
}
```

Read-only contract = invariant. Naruszenie = kompilacja nie przejdzie.

### Generalizacja patternu
- **Cognition zasada:** writes single-threaded. Sub-agenci contribute intelligence, not actions.
- **JVM mapping:** Spring Security `@PreAuthorize`, Bean Validation, Resilience4j circuit breaker.
- **Anthropic Claude Code sandboxing (2025):** OS-level isolation (bubblewrap / seatbelt). 84% reduction in permission prompts.
- **Docker Sandboxes (Nov 2025):** microVMs (Firecracker / Kata) per agent session. Standardowe kontenery to NIE sandboxes (shared kernel = container-escape = host pwn).

### Cost optimization (bonus, generalizuje się dobrze)
- **Prompt caching** (ProjectDiscovery prod): **59% → 70% input savings**, 74% cache hit rate. Anthropic limits: 4 breakpoints, 20-block lookback.
- **Batch API:** 50% off. Stacks z caching → ~5% standard cost.
- **Model routing** (Cursor Auto): cheapest viable per step.

### Nowy problem
Architektura jest gotowa. Ale **agent dalej nie pokonał Garlanda**. Następny krok: ekspedycja do Chaos Shrine. To jest prawdziwa, niezakończona praca.

---

## Loop 7 — Where we are, what we learned (37:30 – 43:00) | 5.5 min

### Stan na dziś (live evidence z HANDOFF.md, 6 maja)
- 9 PRów merged w jeden dzień. Phase 1.5 + Phase 2 verified live.
- Agent dochodzi do throne roomu Króla Coneria (mapId=24).
- LandmarkContext rendered, advisor cytuje koordynaty verbatim.
- Pipeline: `runExplorer` (Haiku, <$1/kampania) + `runAgent` (Advisor + Executor).
- **Honest cliffhanger:** Garland nadal stoi w Chaos Shrine. Architektura gotowa.

### Recap: 6 problemów, 6 wzorców, 6 generalizacji

| Loop | Problem | Wzorzec / Solution | Generalizacja |
|------|---------|-------------------|----------------|
| 1 | ReAct diverguje | Outer loop u nas + `singleRunStrategy` | Reflexion 2023, Anthropic „simplest solution first" |
| 2 | Wrong tool granularity | Skill library, macro/micro split | Voyager (15.3×), Hershey CPP (3 tools) |
| 3 | No cross-session memory | 5 JSON files, atomic writes | MemGPT, Mem0 (91% latency cut), Anthropic Memory Tool |
| 4 | Discovery is too expensive | Explorer phase ≠ execute phase | Multi-agent only when value > cost; macro/micro |
| 5 | Hallucinated coords | GROUND TRUTH ONLY contract | Reasoning Trap, Anthropic Writing Tools |
| 6 | No safety boundaries | Read-only contract, type-enforced | Cognition writes single-threaded, Anthropic sandboxing |

### Rzeczy, które rzucają się na zdjęcie
- **Każdy problem miał swój odpowiednik w literaturze.** Nie ja go wymyśliłem.
- **Każde rozwiązanie miało nieoczekiwany koszt:** memory wymagał atomic writes, splitter wymagał salience strategy, ground truth wymagał ASCII renderer.
- **Architektura > model.** Te same problemy występują w każdej domenie agentowej (coding, research, customer service, gry).
- **Boring wins:** simple loop + great tools + atomic memory + read-only contracts > fancy multi-agent orchestration.

---

## Closing (43:00 – 45:00) | 2 min

> Zacząłem od *„napiszę agenta, który pokona Garlanda"*.
>
> Skończyłem na **6 iteracjach**, w których za każdym razem moja konkretna, brudna, FF-specyficzna porażka okazała się mieć imię w literaturze: ReAct divergence, tool granularity, MemGPT, multi-agent token cost, hallucinated context, read-only contract.
>
> **Garland nadal stoi w Chaos Shrine.** Ale każda decyzja w tym repozytorium ma swój odpowiednik w jednym z papierów albo postów dziś pokazanych. Nie zbudowałem niczego unikalnego — przeszedłem ścieżkę, którą zna ekosystem.
>
> **To nie jest słabość — to jest pointa.**
>
> Repo: `github.com/ArturSkowronski/kNES`. Spec docs udokumentowane razem z porażkami, które do nich doprowadziły. Pytania.

---

## Q&A pre-prep

- **„Czemu Koog a nie LangChain?"** — JVM-native, typed `@Tool`, `createAgentTool` natywnie. JetBrains dogfoods go.
- **„Multi-agent czy nie?"** — Anthropic +90.2% (research), 15× tokens. Cognition single-threaded (coding). Resolution: parallelism dictates.
- **„Czy long context zabija RAG?"** — Chroma context-rot: nie. NIAH passing ≠ working. Hybrid wins.
- **„Najlepszy stack 2026?"** — Match to task. Anthropic upraszcza, Cursor RL-ed Composer, Devin używa playbooks.
- **„LLM vs RL?"** — PokeRL bije każdy LLM-bot na speed (10M params). Different use case.

---

## Slide assets checklist

- [ ] Hook: V1 trace.jsonl ($30 / iter 10 exception)
- [ ] Loop 1: Reflexion paper Yao quote, Anthropic „simplest solution"
- [ ] Loop 2: Voyager skill library architecture diagram, Hershey 3 tools
- [ ] Loop 2: kNES skill list (real `Skill.kt`)
- [ ] Loop 3: Chroma context-rot graph
- [ ] Loop 3: Anthropic 3 primitives table (compact / clear / memory)
- [ ] Loop 3: MemGPT diagram, Mem0/Zep numbers
- [ ] Loop 3: 5 JSON files w `~/.knes/`
- [ ] Loop 4: Phase 1 inner loop code
- [ ] Loop 4: Anthropic MCP code execution (98.7% reduction)
- [ ] Loop 5: GROUND TRUTH ONLY paragraph from `AdvisorAgent.kt:103`
- [ ] Loop 5: Haiku vs Gemini A/B table
- [ ] Loop 6: $47k / Replit / terraform timeline
- [ ] Loop 6: Read-only contract Kotlin code
- [ ] Loop 7: Recap table 6 loops × 6 generalizations
- [ ] Closing: „Garland nadal stoi" + repo URL

## Demo skrypt

```bash
# Przed talkiem
ANTHROPIC_API_KEY=$KEY ./gradlew :knes-agent:runExplorer &  # tle

# Loop 3 (memory)
jq '.landmarks | length' ~/.knes/ff1-landmarks.json
jq -r '.landmarks[] | "\(.kind) @ mapId=\(.mapId)"' ~/.knes/ff1-landmarks.json

# Loop 4 (Phase 1 cost)
jq -r '.haikuCostUsd' ~/.knes/runs/$(ls -t ~/.knes/runs | head -1)/summary.md

# Loop 5 (advisor input)
jq -r 'select(.role == "advisor") | .input' \
   ~/.knes/runs/$(ls -t ~/.knes/runs | head -1)/trace.jsonl | head -50
```

## Bibliography (top 20, w kolejności pojawiania się w talku)

**Loop 1:**
- [ReAct, Yao 2022 (arXiv 2210.03629)](https://arxiv.org/abs/2210.03629)
- [Reflexion, Shinn 2023 (arXiv 2303.11366)](https://arxiv.org/abs/2303.11366)
- [Anthropic — Building Effective Agents (Dec 2024)](https://www.anthropic.com/research/building-effective-agents)

**Loop 2:**
- [Voyager, Wang 2023 (arXiv 2305.16291)](https://arxiv.org/abs/2305.16291)
- [Anthropic — Writing Effective Tools (Sept 2025)](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [Claude Plays Pokémon — Hershey, Latent Space podcast](https://www.latent.space/p/how-claude-plays-pokemon-was-made)
- [CodeAct (arXiv 2402.01030)](https://arxiv.org/abs/2402.01030)

**Loop 3:**
- [Chroma — Context Rot](https://research.trychroma.com/context-rot)
- [Anthropic — Effective Context Engineering (Sept 2025)](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [MemGPT, Packer 2023 (arXiv 2310.08560)](https://arxiv.org/abs/2310.08560)
- [Mem0 (arXiv 2504.19413)](https://arxiv.org/html/2504.19413v1)
- [Sleep-time compute (arXiv 2504.13171)](https://arxiv.org/abs/2504.13171)
- [Anthropic Managed Agents + Dreaming (Apr 2026)](https://www.anthropic.com/engineering/managed-agents)

**Loop 4:**
- [Anthropic — Code Execution with MCP (Nov 2025)](https://www.anthropic.com/engineering/code-execution-with-mcp)
- [Anthropic — Multi-agent research system (Jun 2025)](https://www.anthropic.com/engineering/multi-agent-research-system)

**Loop 5:**
- [The Reasoning Trap (arXiv 2510.22977)](https://arxiv.org/abs/2510.22977)
- [Cognition — Don't Build Multi-Agents (Jun 2025)](https://cognition.ai/blog/dont-build-multi-agents)

**Loop 6:**
- [$47k Agent Loop (Nov 2025)](https://dev.to/waxell/the-47000-agent-loop-why-token-budget-alerts-aren-t-budget-enforcement-389i)
- [Replit prod DB deletion](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/)
- [ProjectDiscovery — 70% cache savings](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching)
