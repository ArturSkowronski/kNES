# Geecon 2026 — Jak zbudować agenta, który nie zbankrutuje Cię w nocy

**Talk:** *„Architektura systemów agentowych w 2026 — lessons z literatury, produkcji i Final Fantasy"*
**Format:** 45 min talk + 10 min Q&A
**Audiencja:** JVM developers / praktycy
**Through-line:** practical agent engineering — narzędzia, architektury, pamięć, planowanie, produkcyjne porażki — z **kNES + FF1 jako sandbox** (Kotlin + Koog + Anthropic).
**Repo:** github.com/ArturSkowronski/kNES

---

## Hook — „Twój agent kosztował Cię $47 000 zanim wstałeś" (0:00 – 2:30) | 2.5 min

- **Listopad 2025:** market-research pipeline z 4 LangChain A2A agentami. Analyzer ↔ Verifier ping-pong bez terminacji. **11 dni. $47 000.** Mieli alerty — leciały na Slacka, którego nikt nie czytał. ([dev.to/$47k loop](https://dev.to/waxell/the-47000-agent-loop-why-token-budget-alerts-aren-t-budget-enforcement-389i))
- **Lipiec 2025:** Replit's agent kasuje produkcyjną bazę 1190 firm podczas explicit code freeze. ([The Register](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/))
- **2025:** Claude Code uruchamia `terraform destroy` na 2.5 roku danych ~100k studentów. ([Tom's Hardware](https://www.tomshardware.com/tech-industry/artificial-intelligence/claude-code-deletes-developers-production-setup-including-its-database-and-snapshots-2-5-years-of-records-were-nuked-in-an-instant))

> „Większość problemów z agentami w 2026 to nie LLM. To **harness**. Pokażę Wam co o tym wiemy z literatury, co robi Anthropic, OpenAI, Cursor, Devin, Cognition — i jak to wygląda, kiedy zbudujesz to sam grając w Final Fantasy."

---

## Akt I — Co to jest agent i kiedy go użyć (2:30 – 8:00) | 5.5 min

### 1.1 Anthropic's taxonomy z „Building Effective Agents" (Dec 2024) (2 min)
- **Augmented LLM** (LLM + tools + retrieval + memory) = building block.
- **5 workflow patterns:** Prompt Chaining · Routing · Parallelization · Orchestrator-Workers · Evaluator-Optimizer.
- **Agents** = LLM dynamicznie steruje własnym procesem. Tylko gdy ścieżki nie da się zakodować.
- Cytat: *„Find the simplest solution possible, and only increase complexity when needed."*

### 1.2 Workflow vs Agent — kiedy co (1.5 min)
| Workflow | Agent |
|----------|-------|
| Predefined paths | Self-directed loop |
| Predictable | Open-ended |
| Cheap | Expensive (4× tokens chat) |
| Most production needs | Long-horizon, ambiguous |

> *„Agent is just a model in a loop with tools — that's the whole architecture."* — Anthropic

### 1.3 Karpathy mental model (1 min)
- LLM = CPU
- Context window = RAM
- Agent runtime = OS
- Context engineering = pamięć i scheduling
- *„Context engineering is the delicate art of filling context window with just the right information for the next step."* (Karpathy)

### 1.4 Case study intro: kNES + FF1 (1 min)
- kNES = emulator NES w Kotlinie. KotlinConf 2025 talk.
- FF1 (1987) = perfect agent sandbox: long-horizon, partial-observable, deterministic state, RAM dostępna.
- Cel: dotrzeć do Garlanda (boss Chaos Shrine).
- Stack: Kotlin + Koog (JetBrains) + Anthropic Claude.

### 1.5 Pierwszy prototyp: Claude Code + REST API (1.5 min) ⭐
- **Zanim** zbudowałem własnego agenta w Koog — pierwszy prototyp był na **Claude Code przez MCP**.
- Backstory: chciałem grać **JoyConami zamiast klawiatury**. Potem REST API. Potem MCP. Potem Claude Code wziął pada.
- **Każda nowa warstwa = jeden wieczór pracy.** Claude Code load'ował ROM, tworzył party (Fighter/Thief/Black Belt/Red Mage), walczył z 5 IMPami na overworldzie.
- Game changer: **mając własny emulator, masz pełny kontrakt** — input, output, RAM, czas.
- Architektura: `ControllerProvider` jako abstrakcja z jedną metodą `pollInput()`. Implementacje: keyboard, JoyCon over Bluetooth, REST API (POST z curl), MCP (stdio), **mój własny agent w Koog**.
- Bez własnego emulatora — Mesen, FCEUX nie mają takiego API. **To dlaczego cały ten talk jest możliwy.**
- Source: [JVM Weekly vol. 172 — *Claude Plays Final Fantasy just before KotlinConf 2026*](https://www.jvm-weekly.com/p/claude-plays-final-fantasy-just-before)
- Punchline: *„Każda warstwa zajęła mi jeden wieczór dzięki małemu interface'owi."*

> Insight przed Aktem II: ten talk **nie jest o Final Fantasy.** Jest o tym, co się stało, jak naturalnie chciałem zrobić **swojego agenta** zamiast korzystać z czyjegoś.

---

## Akt II — Tools matter more than prompts (8:00 – 16:00) | 8 min

### 2.1 Agent-Computer Interface (1.5 min)
- Anthropic SWE-bench SoTA przyszło z **przepisania tool descriptions, nie modelu**.
- *„We spent more time optimizing tools than the prompt."* — Anthropic
- *„If a human engineer can't say which tool to use, an AI agent can't either."* — Anthropic Writing Tools (Sept 2025)

### 2.2 Macro vs Micro split — uniwersalna zasada (1.5 min)
- **Macro** (decyzje, planowanie) = LLM. Non-deterministic by design.
- **Micro** (execution, walking, parsing) = scripted code. Deterministic, testable, JUnit-able.
- **Bridge** = tool design. Tools are the API contract.
- 2025-2026 consensus (Anthropic, OpenAI, Cursor, Devin): **fewer, higher-leverage, well-described tools** + long tail przez code execution + progressive disclosure.

### 2.3 Voyager — skill library (NVIDIA 2023) (1.5 min)
- LLM pisze JS funkcje, indexed by embedding of NL description, top-5 retrieval.
- 3.3× more unique items, **15.3× faster wood, 8.5× stone, 6.4× iron, 1st do diamond** vs ReAct/Reflexion/AutoGPT.
- Ablation: bez self-verification → -73%, bez curriculum → -93%.
- Skill library zaczyna realnie zarabiać po ~80 iteracjach.

### 2.4 Anthropic's Claude Plays Pokémon — 3 narzędzia (1.5 min)
- Hershey: tylko `update_knowledge_base` + `use_emulator` + `navigator`.
- *„I've actually stripped out complexity over time, not added it."* — Hershey
- Navigator abstrahuje „idź do (x,y)" zamiast 13 button-tools.
- Knowledge base = self-managed memory, persists across summarization.

### 2.5 Anti-pattern: 13 raw button tools (FF case study, 1 min)
- V1 mojego agenta: `step`, `tap`, `sequence`, `press`, `release`, `getState`, ... = 13 tools.
- Executor zapętlał się analizując RAM, nigdy nie naciskał przycisku.
- $20–50 / attempted run.
- Fix: 7 high-level skills, raw narzędzia ukryte. Cost spadł rzędem wielkości.

### 2.6 Tool design heuristics (1 min)
| Tier | Practice | Source |
|------|----------|--------|
| S | Right abstraction beats more tools | Voyager, CPP |
| S | Tool descriptions are prompts — eval them | Anthropic SWE-bench |
| A | Code-as-action when actions compose (CodeAct +20%) | ICML 2024 |
| A | Progressive disclosure (Anthropic Skills, MCP) | Anthropic 2025 |
| B | Deterministic backbone + LLM at decision points | Temporal pattern |

**Anti-patterns:** >100 tools w kontekście; tool descriptions <40 słów; jeden mega-tool z 20 optional kwargs; tool name overlap bez namespacing.

---

## Akt III — Architectures (16:00 – 23:00) | 7 min

### 3.1 Orchestrator-Workers = Advisor pattern (2 min)
- Anthropic „Building Effective Agents" (Dec 2024) → Anthropic „Multi-agent research system" (Jun 2025).
- Lead agent (Opus) decomposes → subagents (Sonnet) parallel execution → synthesis.
- **Headline:** multi-agent +90.2% vs single-agent na research evals.
- **Caveat:** 4× tokens (single agent) → **15× tokens (multi-agent)**. Token usage explains 80% performance variance.
- Hard rule: *„Multi-agent only viable when value of task > token cost."*

### 3.2 Cognition's „Don't Build Multi-Agents" (Walden Yan, Jun 2025) (1.5 min)
- Two principles:
  1. *„Share context, not just messages"* (full traces!)
  2. *„Decisions, not messages, are what conflict"*
- **Flappy Bird parable:** subagent A robi Mario background, subagent B robi photoreal bird. Każdy element OK, całość crashes.
- Default: single-threaded linear architecture.
- **April 2026 follow-up:** narrower class works — agents contribute intelligence, writes stay single-threaded.

### 3.3 Anthropic's three-agent harness (Mar 2026) (1.5 min)
- **Planner / Generator / Evaluator** dla multi-hour autonomous coding.
- Separating agent doing work from agent judging it = mitigates self-evaluation bias.
- Failure mode: agents try to *„one-shot the app"* — burning whole context on half-implementation.

### 3.4 Resolution: it depends (1 min)
- **Research / parallel-friendly:** multi-agent worth it.
- **Coding / tightly coupled:** single-threaded harness.
- **Conversational / cheap tasks:** single LLM augmented.
- *„Start with simple prompts, optimize with eval, add multi-step only when simpler solutions fall short."* (Anthropic)

### 3.5 Case study: Advisor + Executor w FF (1 min)
- Advisor (Opus, read-only, single-shot) — produces numbered plan.
- Executor (Sonnet/Haiku, full tools, `singleRunStrategy`) — picks 1 skill.
- Trigger advisora: phase boundary, `idleTurns >= 20`, `askAdvisor` z executora.
- **Read-only contract** = invariant. Advisor nie może mutować stanu emulatora.

---

## Akt IV — Memory: short term vs long term (23:00 – 30:00) | 7 min

### 4.1 Context rot — long context to nie magic bullet (1.5 min)
- Chroma Research 2024-2025: tested 18 SOTA models (GPT-4.1, Claude 4, Gemini 2.5, Qwen3).
- **NIAH-passing ≠ long context working.** Distractors, semantic similarity to needle, haystack structure all matter.
- *„NIAH underestimates what most long-context tasks require in practice."* — Chroma

### 4.2 Anthropic's 3 primitives — context engineering (Sept 2025) (1.5 min)
- **Compaction** (`compact_20260112`) — summarize at threshold, preserve high-fidelity decisions.
- **Tool-result clearing** (`clear_tool_uses_20250919`) — surgically drop old tool outputs, keep call record.
- **Memory tool** (`memory_20250818`) — filesystem-based persistent notes across sessions.
- Cookbook: peak context 335K → 169K (compaction) → 173K (clearing).

### 4.3 MemGPT, Mem0, Letta — memory frameworks (1.5 min)
- **MemGPT** (Packer 2023): OS-inspired hierarchical memory. Core (RAM) / archival (disk). 93.4% DMR vs 35.3% summarization baseline.
- **Mem0** (2025): vector + graph + KV hybrid. **91% latency cut, 14× token cut** at near-parity accuracy (LOCOMO).
- **Zep** (2025): temporal knowledge graph. 63.8% LongMemEval vs Mem0's 49.0%.
- **Letta** = commercial MemGPT, full stateful platform.
- **Mental model dla JVM:** virtual memory dla LLMs. Same paging, same tradeoffs.

### 4.4 Sleep-time compute (Letta + UC Berkeley, 2025) (1 min)
- Pre-compute on context BEFORE queries arrive. Cache deductions.
- **5× less test-time compute** for same accuracy on Stateful GSM-Symbolic / AIME.
- **2.5× cheaper amortized** across queries on same context.
- *„By doing the thinking offline, before user arrives, we cut test-time compute by 5×."*

### 4.5 Anthropic Memory Tool + Dreaming (1 min)
- Sept 2025: filesystem-based persistent memory. Per-user, exportable.
- **Dreaming** (Apr 2026): scheduled background process consolidates past sessions, surfaces recurring mistakes, team preferences.
- Wisedocs case: **97% reduction in first-pass errors, 30% speed-up.**

### 4.6 Case study: 5 JSON files w FF (0.5 min)
```
~/.knes/ff1-{terrain,landmarks,blockages,warps,interior-memory}.json
```
- Atomic writes (3× JVM crash mid-write traciły kampanię).
- Memory rośnie monotonicznie cross-session.
- Phase 2 advisor czyta `LandmarkContext` w prompt → decisions w 1-2 turach zamiast 10-20.

---

## Akt V — Planning: short vs long horizon (30:00 – 35:00) | 5 min

### 5.1 ReAct + jego failure modes (1.5 min)
- Yao 2022: interleave reasoning + actions. AlfWorld +34% vs imitation/RL.
- **Failure modes** (per TDS analysis 2025):
  - Hallucinated tool names burn 3 retries each = **90.8% wasted retries**.
  - **18% rate of „thinking instead of acting"** when history > 2.
- Reflexion paper: ReAct without external termination signal **diverges** on long-horizon tasks.

### 5.2 Reflexion — verbal RL (Shinn 2023) (1 min)
- Agent reflects on failures, stores reflection in episodic memory buffer.
- AlfWorld: **130/134 tasks (97%)**, +22% over baseline.
- HumanEval pass@1: **91% vs GPT-4 baseline 80%.**

### 5.3 Tree of Thoughts, LATS, Plan-and-Solve (1 min)
- ToT (Yao 2023): Game of 24 — **GPT-4 + CoT solves 4%; ToT solves 74%.** 18× improvement on combinatorial reasoning.
- LATS: MCTS + LLM value function + Reflexion. **HumanEval 94.4% (GPT-4).**
- Plan-and-Solve (Wang 2023): plan first, execute. Beats Zero-shot CoT, comparable to 8-shot.

### 5.4 Hierarchical planning (0.5 min)
- LDB: hierarchical debugging by basic block — **+9.8%, up to 98.2% HumanEval w/ Reflexion seed**.
- JARVIS-1: multimodal memory + hierarchical planning, **5× more reliable on ObtainDiamondPickaxe**.

### 5.5 Case study: Skill library + Advisor pattern w FF (1 min)
- Advisor planuje na poziomie skill names: `walkOverworldTo(146,152)`, `exploreInteriorFrontier`, `exitInterior`.
- Executor wybiera 1 skill per turn (`singleRunStrategy`).
- Skill = scripted Kotlin, ZERO tokens LLM w środku.
- **Critical:** granularity narzędzi decyduje czy advisor pattern działa.

---

## Akt VI — Production: war stories i lessons (35:00 – 42:00) | 7 min

### 6.1 The $47k agent loop (1 min)
- 4 LangChain A2A agents, ping-pong, no termination.
- Budget *alerts* hit Slack — nikt nie czytał.
- **Lesson:** alerts ≠ enforcement. Pre-call interceptor that throws BudgetExceeded *before* API call.
- JVM mapping: **Resilience4j circuit breaker dla tokenów.**

### 6.2 Replit + terraform destroy stories (1.5 min)
- Replit (Jul 2025): kasuje prod DB w czasie code freeze, fabrykuje rollback test results.
- Claude Code (DataTalks.Club): `terraform destroy` na 2.5 roku danych studentów. Saved by hidden AWS snapshot.
- **Lesson:** Read-only by default. Destructive verbs require human approval gate.
- JVM mapping: **Spring Security `@PreAuthorize` na tool invocations.**

### 6.3 Air Canada + Klarna — legal & business (1 min)
- Air Canada (Feb 2024): chatbot wymyślił bereavement-fare policy. **BC Tribunal ruled company liable.**
- Klarna: zastąpili 700 agentów AI → repeat-contacts wzrosły → CEO admitted „cut too aggressively" → rehiring.
- **Lesson:** Agents as filter + escalation > agents as replacement.

### 6.4 Cost optimization (1.5 min)
- **Prompt caching:** ProjectDiscovery 59→70% input cost reduction. 74% cache hit rate. Multiple breakpoints (every 18 blocks).
- Anthropic limits: **4 cache breakpoints**, **20-block lookback window**, 5-min default TTL (1.25× write), 1-h TTL (2× write), cache hits = 0.1× input price.
- **Batch API:** 50% off, ~30 min SLA. Stacks z prompt caching → can be ~5% standard cost.
- **Model routing:** Cursor Auto picks cheapest model that works per step.

### 6.5 Sandboxing (1 min)
- Anthropic Claude Code: OS-level isolation (bubblewrap / seatbelt). **84% prompt-reduction internally.**
- Docker Sandboxes (Nov 2025): microVMs (Firecracker/Kata) per agent session. Containers ≠ sandboxes (shared kernel).
- **Lesson:** Sandbox is defense in depth. SecurityManager zostaje deprecated, agentowy świat reodkrywa capability-based security.

### 6.6 Observability + evals (1 min)
- Anthropic „Demystifying evals": 20 hand-graded examples before any framework.
- Track step count, tokens, wall-clock, $ cost as **first-class quality metrics**.
- *„An 8-step $0.12 solve is strictly better than 40-step $1.80."*
- LangChain State of Agent Engineering 2025: **57.3% orgs run agents in prod (67% of 10k+ orgs)**. Top pain: quality, cost, observability.

---

## Akt VII — 10 lessons learned (42:00 – 44:00) | 2 min

Slajd-zdjęcie z mapowaniem na JVM patterns:

| # | Lesson | JVM equivalent |
|---|--------|----------------|
| 1 | Determinism is your moat | Resilience4j, Bean Validation |
| 2 | Decisions > messages (Cognition) | Event sourcing, propagate state not events |
| 3 | Cost is correctness | SLO/SLA budgets per request |
| 4 | Tools matter more than prompts | API design, OpenAPI specs |
| 5 | Context is finite — engineer it | JVM heap mgmt; just-in-time loading |
| 6 | Multi-agent only when parallelizable | Don't build microservices for everything |
| 7 | Read-only contracts (Advisor pattern) | Spring `@PreAuthorize`, immutability |
| 8 | Persistent memory > re-discovery | DB > computed cache |
| 9 | Sandbox + budget enforcement | bulkhead, circuit breaker |
| 10 | Eval first, ship second | TDD; you can't optimize what you don't measure |

---

## Closing (44:00 – 45:00) | 1 min

> Garland nadal stoi w Chaos Shrine. Mój agent doszedł do throne roomu Króla Coneria, zna swoje warpy, zna swoje porażki.
>
> Każdy decision w tym kodzie ma swój odpowiednik w jednym z papierów / postów, które dziś pokazałem. Anthropic upraszcza, Cognition ostrzega przed multi-agent, Voyager pokazuje skill libraries, Mem0 mierzy memory tradeoffs.
>
> **Architektura > model.** Repo: github.com/ArturSkowronski/kNES. Spec docs udokumentowane razem z porażkami. Pytania.

---

## Q&A pre-prep

- **„Multi-agent czy nie?"** — Anthropic: tak dla research (+90.2%, 15× cost). Cognition: nie dla coding (decisions conflict). Resolution: parallelism dictates.
- **„Czy long context zabija RAG?"** — Chroma context-rot: nie. NIAH passing ≠ working. Hybrid wins.
- **„Najlepszy stack agentowy 2026?"** — There isn't one. Anthropic upraszcza (3 patterns), Cursor RL-ed Composer, Devin used playbooks. Match to task.
- **„Czemu Koog?"** — JVM-native, typed `@Tool`, `createAgentTool` natywnie. JetBrains dogfoodes go w IDEA.
- **„LLM vs RL?"** — PokeRL beats every LLM bot on speed. Different use case: zero-shot, dialog, no training data.

---

## Slide assets checklist

- [ ] Hook: $47k Slack alert screenshot, Replit deletion timeline, terraform destroy aftermath
- [ ] Anthropic Building Effective Agents — 5 patterns diagram
- [ ] Karpathy quote (LLM=CPU, context=RAM)
- [ ] Voyager skill library architecture (Wang 2023 Fig 1)
- [ ] Hershey "stripped out complexity" quote
- [ ] FF1 V1 trace.jsonl (zapętlony executor)
- [ ] Tool design tier-list
- [ ] Orchestrator-Workers diagram (Anthropic vs FF mapping)
- [ ] Cognition's Flappy Bird parable
- [ ] Chroma Context Rot graph
- [ ] Anthropic 3 context primitives table
- [ ] MemGPT diagram (core / archival / recall)
- [ ] Mem0 / Zep benchmark numbers
- [ ] Sleep-time compute architecture
- [ ] FF1 LandmarkContext.render output
- [ ] Reflexion AlfWorld 97% vs ReAct
- [ ] ToT Game of 24 (74% vs 4%)
- [ ] $47k post-mortem timeline
- [ ] Replit timeline, terraform destroy
- [ ] Air Canada legal precedent
- [ ] ProjectDiscovery 70% cache savings chart
- [ ] Anthropic 3-agent harness (Mar 2026)
- [ ] 10 lessons + JVM mapping (final slide)

## Demo skrypt

```bash
# W tle przez cały talk
ANTHROPIC_API_KEY=$KEY ./gradlew :knes-agent:runExplorer &

# Pod koniec Aktu IV (memory)
jq '.landmarks | length' ~/.knes/ff1-landmarks.json
jq -r '.landmarks[] | "\(.kind) @ mapId=\(.mapId)"' ~/.knes/ff1-landmarks.json

# Pod koniec Aktu V (planning)
jq -r 'select(.role == "advisor") | .input' \
   ~/.knes/runs/$(ls -t ~/.knes/runs | head -1)/trace.jsonl | head -50
```

## Bibliography (top 25)

**Anthropic core:**
- [Building Effective Agents](https://www.anthropic.com/research/building-effective-agents) — Dec 2024
- [Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) — Sept 2025
- [Multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system) — Jun 2025
- [Writing effective tools](https://www.anthropic.com/engineering/writing-tools-for-agents) — Sept 2025
- [Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) — Nov 2025
- [Three-agent harness](https://www.anthropic.com/engineering/harness-design-long-running-apps) — Mar 2026
- [Code execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp) — Nov 2025
- [Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills) — Oct 2025
- [Managed Agents + Dreaming](https://www.anthropic.com/engineering/managed-agents) — Apr 2026
- [Demystifying evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

**Critique / counter-points:**
- [Cognition: Don't Build Multi-Agents](https://cognition.ai/blog/dont-build-multi-agents) — Jun 2025
- [Cognition: Multi-Agents What's Working](https://cognition.ai/blog/multi-agents-working) — Apr 2026
- [Chroma: Context Rot](https://research.trychroma.com/context-rot)

**Memory / planning papers:**
- [MemGPT](https://arxiv.org/abs/2310.08560) · [A-MEM](https://arxiv.org/abs/2502.12110) · [Mem0](https://arxiv.org/html/2504.19413v1) · [Zep](https://arxiv.org/pdf/2501.13956)
- [Sleep-time compute](https://arxiv.org/abs/2504.13171)
- [ReAct](https://arxiv.org/abs/2210.03629) · [Reflexion](https://arxiv.org/abs/2303.11366) · [ToT](https://arxiv.org/abs/2305.10601) · [LATS](https://arxiv.org/abs/2310.04406) · [Plan-and-Solve](https://arxiv.org/abs/2305.04091)
- [Voyager](https://arxiv.org/abs/2305.16291) · [JARVIS-1](https://arxiv.org/abs/2311.05997) · [CodeAct](https://arxiv.org/abs/2402.01030)
- [Why Multi-Agent LLM Systems Fail (MAST)](https://arxiv.org/abs/2503.13657)
- [Reasoning Trap (tool hallucination)](https://arxiv.org/abs/2510.22977)

**Production reports:**
- [$47k agent loop post-mortem](https://dev.to/waxell/the-47000-agent-loop-why-token-budget-alerts-aren-t-budget-enforcement-389i)
- [Replit deleted prod DB](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/)
- [Claude Code terraform destroy](https://www.tomshardware.com/tech-industry/artificial-intelligence/claude-code-deletes-developers-production-setup-including-its-database-and-snapshots-2-5-years-of-records-were-nuked-in-an-instant)
- [Air Canada chatbot tribunal](https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-february/bc-tribunal-confirms-companies-remain-liable-information-provided-ai-chatbot/)
- [LangChain State of Agent Engineering 2025](https://www.langchain.com/state-of-agent-engineering)
- [ProjectDiscovery: 59-70% prompt cache savings](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching)
- [Cursor Composer architecture](https://cursor.com/blog/composer)
