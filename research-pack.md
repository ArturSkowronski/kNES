# Research Pack — Geecon 2026 Agent Talk

**Cel:** uzupełnia istniejące `outline.md` + `slides.md`. Trzy nowe linie researchu (maj 2026):
1. **Świeży materiał** — wszystko, co wyszło między 1 marca a 7 maja 2026
2. **JVM ecosystem** — Koog 0.8, Spring AI 1.1, LangChain4j 1.14, Embabel, Quarkus
3. **Speaker craft** — analogie, demos, storytelling, closing punchlines, antipatterns

---

## TL;DR — top 12 rzeczy do wpasowania w obecny talk

1. **Anthropic Managed Agents (8 kwietnia 2026)** — *„serverless agent runtime"*. Session + Harness + Sandbox jako product. To jest dosłownie scaling architecture, którą przedstawiam — sprzedana jako produkt. ⭐ Nowy slajd po Akcie III.
2. **Dreaming + Outcomes (6 maja 2026)** — Harvey: **6× wyższa completion rate** dla legal agentów. Idealnie pasuje do Aktu IV (memory).
3. **arXiv 2604.02460** — *„Single-Agent LLMs Outperform Multi-Agent Systems on Multi-Hop Reasoning Under Equal Thinking Token Budgets"* — **+81% multi-agent gain (parallelizable) vs −70% degradacja (sequential)**. Dokładnie podpowiada Akt III (Anthropic vs Cognition) z liczbami.
4. **MCP downloads: 2M (Nov 2024) → 97M (Mar 2026)** — tylko twarda metryka adopcji standardu w 2025-2026.
5. **PocketOS — agent kasuje prod DB w 9 sekund** (kwiecień 2026, SAP case study) — najświeższa katastrofa, idealna do hooka.
6. **Koog 0.8.0 (11 kwietnia 2026)** — Spring AI ChatMemoryRepository support. *„Nie zastępujesz Spring AI — kładziesz Koog na wierzchu."* Killer message dla Spring shops.
7. **Spring AI prompt caching = jeden enum:** `AnthropicCacheStrategy.SYSTEM_AND_TOOLS` → **68% taniej, 85% mniejsze latency** na 100K-token document (11.5s → 2.4s). Idealny do Aktu VI.
8. **Embabel + Rod Johnson (Spring's creator)** — JVM-native agent framework z GOAP planner. *„make it impossible to justify Python."* Credibility transfer.
9. **Karpathy „Software 1.0/2.0/3.0"** + LLM=CPU framework — najsilniejsza analogia dla JVM crowd. Już mamy w Akcie I, ale można rozbudować jako closing callback.
10. **Karpathy 100h prep / Conway 100:1 ratio** — jeśli ktoś zapyta „ile czasu nad tym siedziałeś" w Q&A, odpowiedź ma być uczciwa.
11. **Polish closing punchline:** *„Agent to nie magia. To pętla, narzędzia i kontekst. Reszta to inżynieria — czyli to, co my, ludzie z JVM, robimy najlepiej od trzydziestu lat."*
12. **Zamiast „Thank you / Questions" slajdu** — bold sentence + repo URL + QR. Nigdy goły „dziękuję".

---

## Sekcja 1: Świeży materiał (1 marca – 7 maja 2026)

### Anthropic — kluczowe wydarzenia

| Data | Co | Liczba do slajdu |
|------|-----|------------------|
| **8 kwietnia** | [Managed Agents launch](https://www.anthropic.com/engineering/managed-agents) | $0.08/session-hour. p50 TTFT −60%, p95 −90%. Notion, Asana, Sentry early adopters. |
| **16 kwietnia** | [Claude Opus 4.7](https://www.anthropic.com/news/claude-opus-4-7) | Wbudowany **task budgets** — model dostaje rough estimate ile tokenów ma wyjść na cały loop. High-res image (2576px). |
| **23–24 kwietnia** | [GPT-5.5](https://openai.com/index/introducing-gpt-5-5/) | OpenAI **pierwszy raz pozycjonuje flagship pod agentic** workflows. |
| **27–29 kwietnia** | Claude Agent SDK 2.1.122/123 | `/resume` 67% szybsze na 40MB+ sessions. Opus alias `"opus"` → `"default"`. |
| **6–7 maja** | [Code w/ Claude](https://thenewstack.io/anthropic-managed-agents-dreaming-outcomes/) | API volume **17× rok-do-roku**. Rate limits doubled. |
| **6 maja** | **Dreaming** beta | Harvey: **6× completion rate** dla legal agents. Manual `/dream`. |

### Counter-positions (świeże)

- **Cognition: „Multi-Agents: What's Actually Working"** (Apr 2026) — [cognition.ai/blog/multi-agents-working](https://cognition.ai/blog/multi-agents-working). Rozwija „Don't Build Multi-Agents":
  > *„Map-reduce-and-manage: manager dzieli pracę, children execute, manager syntezuje. Writes single-threaded. Additional agents dodają **intelligence**, nie **actions**."*
  
  > *„Open problems are all communication problems."*

- **arXiv 2604.02460** — *Single-Agent LLMs Outperform Multi-Agent Systems on Multi-Hop Reasoning Under Equal Thinking Token Budgets*:
  - **+81% multi-agent gain** na parallelizable tasks (Finance-Agent benchmark)
  - **−70% degradacja** na sequential tasks (PlanCraft benchmark)
  - Token consumption multi-agent ~15× single-agent

- **Anthropic three-agent harness** (marzec 2026, [harness-design-long-running-apps](https://www.anthropic.com/engineering/harness-design-long-running-apps)):
  - **Planner → Generator → Evaluator**
  - Generator negocjuje *„sprint contract"* z Evaluatorem PRZED napisaniem kodu
  - Evaluator nie czyta statycznego kodu — używa **Playwright** żeby interagować z live aplikacją

### OpenAI ekosystem

- **GPT-5.5 / GPT-5.5 Pro** (24 kwietnia) — agentic-first messaging
- **GPT-5.5 Instant** (5 maja) — nowy default w ChatGPT
- **OpenAI Agents SDK update** (15 kwietnia) — własny harness + sandbox w SDK
- **ChatGPT Workspace Agents** (22 kwietnia) — continuously running cloud agents
- **Realtime API GA + gpt-realtime** (5 maja) — voice agents production-ready, supports **remote MCP servers**, image inputs, **SIP phone calling**

### Cursor

- **Cursor 3.0 + Composer 2** (2 kwietnia) — Agents Window, `/best-of-n` (sam task na multiple modeli, izolowane worktrees)
- **Cursor SDK** (29 kwietnia) — same runtime/harness/models as Cursor; **context usage breakdown w UI** jako product feature

### OSS frameworks

- **LangChain 1.0 + LangGraph 1.0** (kwiecień 2026) — pierwsze major releases. LangGraph używają Uber, LinkedIn, Klarna.
- **CrewAI 1.14.x** (25 kwietnia) — e2b sandboxes, lifecycle events for checkpoints, Bedrock V4
- **AutoGen → maintenance mode** — Microsoft przenosi focus na Agent Framework
- **MCP 2026 Roadmap** — stateless transport landuje czerwiec 2026. **Downloads: 2M (Nov 2024) → 97M (Mar 2026).**

### Production failures Q1-Q2 2026

- **PocketOS** (kwiecień 2026, [SAP community case study](https://community.sap.com/t5/artificial-intelligence-blogs-posts/case-study-how-an-ai-coding-agent-deleted-a-production-database-in-9/ba-p/14388304)) — **AI agent skasował live production DB i wszystkie backupy w 9 sekund**, single API call. Najświeższa konkretna katastrofa.
- **„Why AI Agents Fail in Production"** ([neuralwired](https://neuralwired.com/2026/04/28/why-ai-agents-fail-production/), kwiecień 2026):
  - **Tylko 11% organizacji ma agentów w prod** (86% planuje)
  - Gartner: **>40% agentic projektów anulowanych do 2027**
  - Top 3 integration failures: Dumb RAG, Brittle Connectors, Polling Tax (no event-driven arch)

### 3 emerging trends z dowodem (nie hype)

#### Trend 1: Single-agent kontratakuje
arXiv 2604.02460 + Cognition multi-agents-working = empiryczna asymetria, nie ideologia. **Slajd:** zaczynaj od single-agent + reviewer; multi-agent tylko gdy task jest provably parallelizable.

#### Trend 2: Harness > Model
Anthropic w marcu 2026 [publikuje](https://venturebeat.com/technology/mystery-solved-anthropic-reveals-changes-to-claudes-harnesses-and-operating-instructions-likely-caused-degradation), że performance degradation Claude Code pochodziła ze **zmian w harness/operating instructions, nie modelu**. **Slajd:** *„Twój model nie zawiódł, twój harness zawiódł."*

#### Trend 3: Context engineering jako load-bearing skill
- Google Cloud Next '26 keynote eksplicit używa terminu *context engineering*
- Cursor SDK eksponuje context usage breakdown jako product feature
- Anthropic **2026 Agentic Coding Trends Report**: *„Context quality, not volume, is new limiting factor."*

---

## Sekcja 2: JVM ecosystem (deep)

### Tabela porównawcza — Koog vs Spring AI vs LangChain4j

| Dimension | **Koog 0.8.0 (Apr 2026)** | **Spring AI 1.1.5 / 2.0-M5** | **LangChain4j 1.14.1** |
|---|---|---|---|
| Vendor | JetBrains | Pivotal/Broadcom | Community + Microsoft + Red Hat |
| Primary lang | Kotlin (Java API od 0.7.1) | Java | Java |
| Stack position | **Orchestration / planner** | LLM client + RAG advisors | LLM client + agentic flows |
| Agent model | Graph strategies + GOAP Planner | Manual via ChatClient + advisors; ships Anthropic 5 patterns | `AiServices` + `@Tool`; persistable state od 1.13 |
| Tool calling | `ToolRegistry`, `createAgentTool` | `@Tool`, `ToolCallback`, MCP starter | `@Tool`, `McpToolProvider` |
| MCP | Native client | `spring-ai-starter-mcp-client` (STDIO/SSE/Streamable HTTP) | Full client + resource subscriptions |
| Memory | **First-class `AgentMemory`** scoped + priority; Postgres/JDBC | `MessageChatMemoryAdvisor`, vector store advisor | `ChatMemory`, window memory |
| **Checkpointing** | **Yes — full graph rollback** ⭐ | Brak natywnego | Basic od 1.13 |
| Prompt caching | Anthropic + Bedrock `CacheControl` od 0.7.3 | **Best-in-class** — 5 strategy enums, auto breakpoints | Manual via low-level API |
| Multi-platform | **JVM, Android, iOS, JS, WASM** ⭐ | JVM only | JVM (Spring + Quarkus) |
| Observability | OpenTelemetry, Langfuse, W&B Weave, DataDog (0.8) | Micrometer, observability advisors | Listeners + tracing |
| Best fit | Kotlin/multi-platform/long-running stateful | Spring Boot shops | Quarkus shops, max provider flexibility |

### Spring AI killer demo (10 LOC) — Anthropic prompt caching

```kotlin
val client = ChatClient.builder(anthropicChatModel)
    .defaultOptions(
        AnthropicChatOptions.builder()
            .cacheStrategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)  // ← jedna linia
            .build()
    )
    .build()

val response = client.prompt()
    .system(longSystemPrompt)   // ~3500 tokenów
    .user("...")
    .call().content()
```

**Konkretne liczby do slajdu** ([Spring AI blog](https://spring.io/blog/2025/10/27/spring-ai-anthropic-prompt-caching-blog/)):
- **$0.017 vs $0.053** (5 questions, 3500-token system prompt) — **68% taniej**
- **Latency redukcja 85%** dla long prompts
- **100K-token document: 11.5s → 2.4s**

### Koog 0.8 — co jest unikalne

1. **`createAgentTool()`** — sub-agent jako `@Tool`. Hierarchical orchestration zero-effort.
2. **`AgentMemory`** scoped: Agent / Feature / Product / CrossProduct + priority + timestamps. Backendy: Postgres, Exposed-ORM, JDBC. **Spring AI/LangChain4j tego nie mają.**
3. **GOAP Planner** (od 0.6.0) — Goal-Oriented Action Planning, deterministyczny non-LLM planner z A*. Explainable.
4. **Multi-platform Kotlin** — same agent na backend + iOS + Android + WASM. **Python tego nie ma.**
5. **Spring AI integration** ([JetBrains blog kwiecień 2026](https://blog.jetbrains.com/ai/2026/04/introducing-koog-integration-for-spring-ai-smarter-orchestration-for-your-agents/)) — *„nie zastępujesz Spring AI, kładziesz Koog na wierzchu."*

### Embabel — Rod Johnson's pitch

[Embabel: A New Agent Platform For the JVM](https://medium.com/@springrod/embabel-a-new-agent-platform-for-the-jvm-1c83402e0014):
- Spring-Boot/Kotlin-with-Java-interop
- **GOAP planner z A*** — non-LLM, explainable
- Annotation model: `@Agent`, `@Goal`, `@Condition`, `@Action`
- Rod Johnson cytat: *„make it impossible to justify Python for production-grade agent systems not already committed to that ecosystem."*

### Quarkus + AI

- **Quarkus LangChain4j extension**: build-time wiring dla GraalVM native
- **Sub-100 ms cold start, 50–150 MB RSS** (vs Python LangChain ~500MB+ RSS, sekundy cold start)
- **Unique JVM angle:** to jest jedyna rzecz, w której Python literally nie może dorównać
- [Quarkus + LangChain4j blog](https://quarkus.io/blog/quarkus-meets-langchain4j/)

### Anti-pattern → JVM antidote (rozszerzone)

| Anti-pattern | JVM antidote |
|--------------|--------------|
| Hand-rolled retry loops | **Resilience4j** `@Retry` + `@CircuitBreaker` (retry-inside-circuit) |
| Unbounded asyncio fan-out | **Project Loom virtual threads** + `StructuredTaskScope` lub Kotlin coroutines |
| Pickled JSON-blob agent state | **Koog `AgentMemory`** typed + Postgres lub LangChain4j 1.13 persistable state |
| String-only tool args | **Spring AI `BeanOutputConverter`** + JSON Schema 2020-12 |
| Black-box failures | Micrometer + OpenTelemetry + Langfuse / Weave / DataDog (native exporters w trójce) |
| Re-paying full system prompt | **Spring AI `AnthropicCacheStrategy`** — 1 linia, 68% taniej |
| Sub-agents jako HTTP calls | **Koog `createAgentTool()`** lub LangChain4j `@Tool` na sub-agencie |
| Hand-wired graph orchestration | **Embabel GOAP planner** lub Koog 0.6+ Planner agent |
| Slow Python serverless cold start | **Quarkus + GraalVM native image** — sub-100ms |
| Spring Retry + circuit breaker fighting | **Retry inside circuit breaker** — retries count as one call |
| One-shot RAG retrieval | **RAG jako agent tool** — model może re-query (Devoxx Belgium 2025 pattern dla LangChain4j) |

### Production stories / voices to cite

- **JetBrains** internal: Junie, AI Assistant w IntelliJ, Kineto wszystko na Koog
- **Microsoft + Red Hat** współ-backują LangChain4j; Microsoft: *„hundreds of customers in production"*
- **Rod Johnson** (Spring's creator) → Embabel — credibility transfer dla Spring shops
- **Lize Raes** at Devoxx Belgium 2025 — *„Level Up Your LangChain4j Apps for Production"* ([Inside.java recap](https://inside.java/2026/02/01/devoxxbelgium-production-langchain4j/))
- **Dan Vega** (Spring Developer Advocate) — prompt caching cost stories ([danvega.dev](https://www.danvega.dev/blog/spring-ai-prompt-caching))
- **CodeConductor** — public LangChain4j enterprise customer

---

## Sekcja 3: Analogie + demo patterns

### Top 5 analogies — z origin

| # | Analogia | Origin | Why JVM crowd loves it |
|---|----------|--------|------------------------|
| 1 | **LLM = CPU, context = RAM, tokens = bytes, retrieval = filesystem, tool calls = syscalls** | Andrej Karpathy, YC AI Startup School (czerwiec 2024). Roots: MemGPT (Packer 2023). | JIT, GC, heap — natychmiast rozumieją context engineering jako problem leak/eviction. |
| 2 | **MemGPT virtual memory paging** — main context vs external context | Charles Packer et al. arxiv 2310.08560 | Direct mapping na OS pagefile. Pokaż LRU eviction loop. |
| 3 | **„Treat agent output like compiler output"** — nie czytasz binariów, puszczasz przez testy + prod telemetry | Skip Labs blog | Reframuje *„should we trust agents"* jako tooling question, nie metafizyczne. CI/CD pipelines każdy zna. |
| 4 | **Multi-agent ≈ Byzantine generals problem** — coordination problem, nie model problem | Lamport/Pease/Shostak (1982); applied: Anthropic multi-agent post + SagaLLM paper | *„Most multi-agent failures are coordination failures."* JVM crowd = distributed systems crowd. |
| 5 | **Agent state = ACID transactions / Saga pattern** — multi-step LLM plan = long-running transaction without rollback | SagaLLM (Mar 2025) + Aaron Levie | Spring/JPA crowd lives ACID. *„Twoja agent loop to long-running transaction without compensation"* — natychmiast strach. |

**Bonusy do Q&A:**
- **„60,000 tokens, 50 million pages"** (Aaron Levie) — concrete impedance mismatch
- **„Agentic search is just glob + grep"** (Boris Cherny, Pragmatic Engineer) — boring-tech-wins
- **„Software 1.0 (code) → 2.0 (weights) → 3.0 (English)"** (Karpathy) — closing pattern

### Demos które „landed"

1. **Pelican on a bicycle SVG benchmark** (Simon Willison, AI Engineer World's Fair 2025) — same prompt across 18 months of model releases shown side-by-side. **Comparison IS demo.** [github.com/simonw/pelican-bicycle](https://github.com/simonw/pelican-bicycle)
2. **Greg Brockman TED 2023** — live ChatGPT plug-in chain: recipe → image → tweet → Instacart. **Show the seams between tools.**
3. **Claude Plays Pokémon** (Hershey) — pre-record long runs, narrate replays. **Agents są za wolne na live demos** — trzeba narrować nagrane.
4. **Voyager skill library** — pokaż JS code, który agent napisał sam dla siebie, potem next skill który wywołał poprzedni. **Show artifacts, not thinking.**
5. **Boris Cherny parallel Claude Code workflow** — multiple terminals/desktops na separate worktrees. **Show real workflow, not sanitized.**

### **Live coding rule for 45 min:** NIE live-koduj agenta wywołującego real LLM
Conference Wi-Fi cię zabije. Trzy opcje: (a) pre-record + narrate, (b) cache response, (c) deterministic mock. Universal advice z [opensource.com](https://opensource.com/article/17/9/7-best-practices-giving-conference-talk).

---

## Sekcja 4: Storytelling templates

### Template A: „We tried the obvious thing and it exploded"
- Setup: thought infinite context was the answer
- Struggle: 50 sub-agents na proste query → distract each other, scour web for nonexistent sources, costs 10×
- Insight: bottleneck wasn't model, was *coordination*
- Punchline: *„We thought we had a model problem. Turned out distributed-systems problem all along."*
- Source: Anthropic multi-agent post

### Template B: „The boring solution won"
- Setup: every agent startup selling vector search and graph RAG
- Struggle: tried embeddings, hybrid retrieval, re-rankers, BM25
- Insight: glob and grep, driven by model, beat all
- Punchline: *„Our 'agentic search' is `find` and `grep`. Don't tell our investors."*
- Source: Boris Cherny, Pragmatic Engineer

### Template C: „I almost lost the demo"
- Setup: live demo, 800 people watching
- Struggle: Wi-Fi dies, agent times out, model returns null
- Insight: had screencast as backup, played it, talked over it, nobody noticed
- Punchline: *„Treat your demo like prod: assume it fails, build the rollback first."*

### Template D: „The 47-incident retrospective"
- Setup: cataloging 47 production LLM incidents across 6 systems
- Struggle: expected exotic failure modes — jailbreaks, hallucinations, token explosions
- Insight: 5 root causes repeating: bad context, no resumability, race conditions on shared state
- Punchline: *„Your agent doesn't have an AI bug. It has a 1990s database bug wearing a hoodie."*

### Template E: „The contrarian flip" ⭐ *najlepiej dopasowane do twojego talku*
- Setup: everyone building multi-agent systems (LangGraph, CrewAI, AutoGen logos)
- Struggle: Cognition publishes *„Don't Build Multi-Agents"*. Anthropic publishes *„How we built multi-agents"*. **Same week, June 2025.**
- Insight: Both right — for different problems. Coding → single. Research → orchestrator/worker.
- Punchline: *„The question isn't 'multi-agent or not'. It's 'is your task parallelizable without shared state?'. That's a question we've been answering on the JVM since `Fork/Join` in 2011."*

---

## Sekcja 5: Closing punchlines (do skopiowania)

1. **Karpathy callback:** *„Software 1.0 was code. Software 2.0 was weights. Software 3.0 is English. Your job didn't disappear — it moved up the stack. Again."*
   - Pattern: rhyming triplet + reassurance

2. **Boring-wins twist:** *„Don't build a Byzantine multi-agent swarm. Build one good agent, give it grep, give it tests, give it a budget. Then go home for dinner."*
   - Pattern: anti-hype + specificity + human note

3. **60K vs 50M:** *„You have 60,000 tokens. The world has 50 million pages. Context engineering isn't a feature. It's the entire job."*
   - Pattern: scale contrast + role redefinition

4. **Replacement reframe:** *„AI won't replace developers. Developers using AI will replace developers who don't. So pick a side and ship something on Monday."*
   - Pattern: classic dichotomy + CTA

5. **Polish-flavored ending** ⭐ *(rekomendacja)*: *„Agent to nie magia. To pętla, narzędzia i kontekst. Reszta to inżynieria — czyli to, co my, ludzie z JVM, robimy najlepiej od trzydziestu lat."*
   - Pattern: demystify + identity claim + tribal pride
   - Polish landing: dry humor + JVM tribal pride zamiast US-style energy

### **Avoid `Thank you / Questions?` slide.**
Hard stop. Replace with: bold sentence + contact + repo URL + opcjonalnie QR. ([clear-say.com](https://www.clear-say.com/presentation-call-to-action/), [deckez.com](https://deckez.com/blogs/tips-and-guides/thank-you-slide-vs-cta-slide-which-works-better/))

---

## Sekcja 6: Q&A handling — bridging difficult questions

### „Will AI replace developers?"
**Bridge:** reject the binary. *„Wrong question. Right one: which problems become tractable when implementation cost approaches zero? Architectural judgment, system thinking, knowing what NOT to build — more valuable, not less. Calculator didn't kill mathematicians; freed them to do harder math."*

### „Czy multi-agent to bullshit?"
**Bridge:** yes-and-no z named sources.
> *„Cognition mówi 'don't build multi-agents'. Anthropic mówi 'how we built multi-agents'. Ten sam tydzień, czerwiec 2025. Obaj mają rację — dla różnych problemów. Single-agent dla coding (consistency wins). Orchestrator-worker dla research (parallel reads, no writes). Jeśli twoje sub-agenty muszą się synchronizować — to nie agent problem, to consensus problem, i już wiesz że to bardzo trudne."*

### „Czemu nie używać LangChain?"
**Bridge:** don't bash, contextualize.
> *„LangChain solved real problems w 2023, kiedy nic nie istniało. Krytyka jest głównie o over-abstraction w v0.0.x — Harrison Chase sam przyznał że oryginał był 'too opinionated' i przepisał. Today: użyj LangSmith dla observability (Hamel Husain rekomenduje). Dla agent loops w JVM-land — Spring AI, Embabel, Koog dają native types i istniejący tooling. Real question nie 'LangChain or not' — to 'do you control the abstraction or does it control you?'"*

### Universal Q&A tactic ([Toastmasters](https://www.toastmasters.org/magazine/magazine-issues/2021/mar/handling-the-qanda-session-with-confidence))
**Bridge:** *„Great question. The deeper issue is X."* → route to your prepared content. **Don't dodge — pivot to substance you've rehearsed.**

---

## Sekcja 7: 5 anti-patterns w agent talks (NIE rób)

1. **„What is an agent?" 8-slajdów opener** — audiencja w 2026 już wie. Użyj Simon Willison one-linera *„LLM that runs tools in a loop to achieve a goal"* i jedź. ([simonw.substack.com](https://simonw.substack.com/p/i-think-agent-may-finally-have-a))
2. **Live agent demo na conference Wi-Fi** wywołujący real model. Pre-record. Period.
3. **Architecture-diagram dump** z 14 boxes/arrows. Buduj diagram **przez 4-5 slajdów**, jeden component per slide.
4. **Framework-feature tour** *(„a teraz LangChain ma memory! i tools! i graphs!")*. Audiencja resentuje vendor demos. Show your code.
5. **No war story / no failure shown.** Production stories without scars feel sanitized. Pokaż jeden moment — model returned 0 tokens, agent went into infinite loop, costs spiked $4k overnight. Room leans in.

**Bonus anti-patterns:**
- Reading slides verbatim
- Quoting AI labs as authorities without showing your own code
- Spending >3 min na prompt engineering (to 2023 topic now)

---

## Sekcja 8: Polish-language nuances

- **„Agent" jako loanword** — nie tłumacz. Polskie tech audiences używają „agent". *„System agentowy"* dla broader concept, *„agent"* dla unit.
- **Avoid corporate Polonglish** *(„dostarczać value", „ownership końcowy")* — Geecon crowd allergic. Plain Polish technical vocabulary.
- **Humor calibration** — Polish JVM crowd appreciates **dry, self-deprecating humor** (cf. Sadogursky Geecon 2025 keynote). **Avoid US-style energy.** Max 1 Polish joke per ~10 minutes.
- **Polish references do dropnięcia:**
  - **Przemek Smyrdek** (DevsKiller / Opanuj AI)
  - **Paweł Pilarczyk** (*„Zrozumieć AI"* podcast)
  - **Psyho — Przemysław Dębiak** — Polish programmer who beat OpenAI w 2024 AHC tournament. Mentioning Psyho gets a knowing nod from Polish dev crowds.

- **Geecon 2025 Sadogursky talk** — *„Back to the Future of Software: How to Survive AI with Intent Integrity Chain"*. Same room may have heard it. Either reference or differentiate. ([speaking.jbaru.ch](https://speaking.jbaru.ch/talks/2025-05-16-geecon-back-future/))

---

## Sekcja 9: Slide design (concrete recs)

### Tooling
- **Slidev** — strongest pick dla JVM crowd. Shiki syntax highlighting, Magic Move (evolve code across slides), Monaco for live code, Vue components. ([sli.dev/guide/why](https://sli.dev/guide/why))
- **Marp** — fine if you want pure Markdown + minimal config. To masz teraz.

### Content rules
- **Code on slides:** 8-12 lines max, monospace ≥24pt, syntax-highlighted, **one** highlighted region per slide
- **Diagrams:** Excalidraw (hand-drawn), D2 lub Mermaid (state machines). Buduj step-by-step.
- **Quotes:** big text, attribution below w smaller type, white on dark for contrast. **Single quote per slide.**
- **One-idea-per-slide rule** — 45 min ≈ 60-80 slajdów dla technical content
- **Font pairing:** JetBrains Mono dla code, Inter lub Source Sans Pro dla body. Avoid Comic Sans, Calibri.

### Anti-patterns
- Walls of bullet text
- Screenshots of terminal output unenlarged
- Vendor logo collages
- Gradients behind code

### Preparation benchmark
- **Damian Conway: ~100h prep per 1h stage time** ([damian.conway.org](http://damian.conway.org/Courses/AdvPresPersuasion.html))
- **Simon Willison: minimum 10:1 ratio**
- For 45-min Geecon keynote: **30-50h focused prep + 5-8 full timed run-throughs**, ideally one in front of hostile colleague

---

## Sekcja 10: Recommended structure dla 45 min (z research)

| Min | Sekcja | Content |
|-----|--------|---------|
| **0–3** | Hook | War story (PocketOS / $47k loop / Replit) lub Karpathy CPU/RAM frame. **Skip „what is an agent."** |
| **3–15** | Core analogy | LLM = CPU, context = RAM, MemGPT paging. Show one production failure. |
| **15–30** | Two named-source counter-positions | Cognition vs Anthropic. Resolve z JVM-flavored insight (Fork/Join, Saga, ACID). |
| **30–40** | Concrete demo (pre-recorded) | Embabel/Koog/Spring AI — JVM crowd sees themselves. |
| **40–43** | Sharp closing punchline | No „Thank you" slide. |
| **43–45** | Q&A bridge | Prepared answers above. |

---

## Master bibliography (consolidated, 60+ sources)

### Anthropic core (must-read)
- [Building Effective Agents (Dec 2024)](https://www.anthropic.com/research/building-effective-agents)
- [Effective context engineering (Sept 2025)](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Multi-agent research system (Jun 2025)](https://www.anthropic.com/engineering/multi-agent-research-system)
- [Writing effective tools (Sept 2025)](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [Code execution with MCP (Nov 2025)](https://www.anthropic.com/engineering/code-execution-with-mcp)
- [Three-agent harness (Mar 2026)](https://www.anthropic.com/engineering/harness-design-long-running-apps)
- [Managed Agents (Apr 2026)](https://www.anthropic.com/engineering/managed-agents)
- [Claude Opus 4.7 (Apr 2026)](https://www.anthropic.com/news/claude-opus-4-7)
- [Code w/ Claude live blog (Simon Willison)](https://simonwillison.net/2026/May/6/code-w-claude-2026/)
- [Demystifying evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

### Counter-positions
- [Cognition: Don't Build Multi-Agents (Jun 2025)](https://cognition.ai/blog/dont-build-multi-agents)
- [Cognition: Multi-Agents What's Working (Apr 2026)](https://cognition.ai/blog/multi-agents-working)
- [Chroma: Context Rot](https://research.trychroma.com/context-rot)
- [Octomind: Why we no longer use LangChain](https://www.octomind.dev/blog/why-we-no-longer-use-langchain-for-building-our-ai-agents)

### Memory + planning papers
- [MemGPT (arXiv 2310.08560)](https://arxiv.org/abs/2310.08560)
- [A-MEM (arXiv 2502.12110)](https://arxiv.org/abs/2502.12110)
- [Mem0 (arXiv 2504.19413)](https://arxiv.org/html/2504.19413v1)
- [Zep (arXiv 2501.13956)](https://arxiv.org/pdf/2501.13956)
- [Sleep-time compute (arXiv 2504.13171)](https://arxiv.org/abs/2504.13171)
- [ReAct (arXiv 2210.03629)](https://arxiv.org/abs/2210.03629)
- [Reflexion (arXiv 2303.11366)](https://arxiv.org/abs/2303.11366)
- [Tree of Thoughts (arXiv 2305.10601)](https://arxiv.org/abs/2305.10601)
- [LATS (arXiv 2310.04406)](https://arxiv.org/abs/2310.04406)
- [Voyager (arXiv 2305.16291)](https://arxiv.org/abs/2305.16291)
- [JARVIS-1 (arXiv 2311.05997)](https://arxiv.org/abs/2311.05997)
- [CodeAct (arXiv 2402.01030)](https://arxiv.org/abs/2402.01030)
- [Why Multi-Agent LLM Systems Fail (arXiv 2503.13657)](https://arxiv.org/abs/2503.13657)
- [Reasoning Trap (arXiv 2510.22977)](https://arxiv.org/abs/2510.22977)
- [Single-Agent vs Multi-Agent token budgets (arXiv 2604.02460)](https://arxiv.org/abs/2604.02460)
- [SagaLLM (arXiv 2503.11951)](https://arxiv.org/pdf/2503.11951)

### Production reports / failures
- [$47k Agent Loop](https://dev.to/waxell/the-47000-agent-loop-why-token-budget-alerts-aren-t-budget-enforcement-389i)
- [Replit deleted prod DB (The Register)](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/)
- [Claude Code terraform destroy](https://www.tomshardware.com/tech-industry/artificial-intelligence/claude-code-deletes-developers-production-setup-including-its-database-and-snapshots-2-5-years-of-records-were-nuked-in-an-instant)
- [PocketOS — agent deleted DB in 9 sec (SAP)](https://community.sap.com/t5/artificial-intelligence-blogs-posts/case-study-how-an-ai-coding-agent-deleted-a-production-database-in-9/ba-p/14388304)
- [Air Canada chatbot tribunal](https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-february/bc-tribunal-confirms-companies-remain-liable-information-provided-ai-chatbot/)
- [Why AI Agents Fail in Production](https://neuralwired.com/2026/04/28/why-ai-agents-fail-production/)
- [LangChain State of Agent Engineering 2025](https://www.langchain.com/state-of-agent-engineering)
- [ProjectDiscovery 70% cache savings](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching)

### JVM ecosystem
- [Koog GitHub](https://github.com/JetBrains/koog)
- [Koog Predefined Strategies docs](https://docs.koog.ai/predefined-agent-strategies/)
- [Koog Agent Memory docs](https://docs.koog.ai/agent-memory/)
- [Introducing Koog Integration for Spring AI (JetBrains, Apr 2026)](https://blog.jetbrains.com/ai/2026/04/introducing-koog-integration-for-spring-ai-smarter-orchestration-for-your-agents/)
- [Spring AI Anthropic prompt caching](https://spring.io/blog/2025/10/27/spring-ai-anthropic-prompt-caching-blog/)
- [Spring AI Bedrock prompt caching](https://spring.io/blog/2025/10/30/spring-ai-bedrock-prompt-caching-blog/)
- [Spring AI Effective Agents reference](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [Spring AI Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [LangChain4j GitHub](https://github.com/langchain4j/langchain4j)
- [Quarkus + LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- [Embabel (Rod Johnson)](https://medium.com/@springrod/embabel-a-new-agent-platform-for-the-jvm-1c83402e0014)
- [Embabel GitHub](https://github.com/embabel/embabel-agent)
- [JVM AI Agent Frameworks comparison](https://medium.com/@shravyaboini/jvm-ai-agent-frameworks-choosing-between-koog-langchain4j-and-google-adk-f4d569bc96d1)
- [Java Devs Get Multiple Paths (The New Stack)](https://thenewstack.io/java-developers-get-multiple-paths-to-building-ai-agents/)
- [State of Memory in Java AI Agents (Apr 2026)](https://dev.to/sunilprakash/the-state-of-memory-in-java-ai-agents-april-2026-13c6)

### Recent (Apr-May 2026)
- [GPT-5.5](https://openai.com/index/introducing-gpt-5-5/)
- [Cursor 3.0 + Composer 2](https://cursor.com/blog/2-0)
- [LangChain 1.0 + LangGraph 1.0](https://blog.langchain.com/langchain-langgraph-1dot0/)
- [Devin 2.2](https://cognition.ai/blog/introducing-devin-2-2)
- [MCP 2026 Roadmap](https://blog.modelcontextprotocol.io/posts/2026-mcp-roadmap/)
- [Anthropic Dreaming + Outcomes](https://thenewstack.io/anthropic-managed-agents-dreaming-outcomes/)

### Speaker craft
- [Karpathy YC AI Startup School](https://www.donnamagi.com/articles/karpathy-yc-talk)
- [Latent Space Software 3.0](https://www.latent.space/p/s3)
- [Boris Cherny on Pragmatic Engineer](https://newsletter.pragmaticengineer.com/p/building-claude-code-with-boris-cherny)
- [David Hershey on Latent Space (CPP)](https://www.latent.space/p/how-claude-plays-pokemon-was-made)
- [Aaron Levie — Every Agent Needs a Box](https://www.latent.space/p/box)
- [Lance Martin Context Engineering YouTube](https://www.youtube.com/watch?v=_IlTcWciEC4)
- [Damian Conway courses](http://damian.conway.org/Courses/AdvPresPersuasion.html)
- [David Whitney — How to write a tech talk](https://davidwhitney.co.uk/blog/2021/06/20/how_to_write_a_conf_talk_that_doesnt_suck/)
- [Tomasz Łakomy — speaking at tech conferences](https://dev.to/tlakomy/what-i-wished-someone-told-me-about-speaking-at-tech-conferences-3opp)
- [Slidev vs Marp vs Reveal.js 2026](https://www.pkgpulse.com/blog/slidev-vs-marp-vs-revealjs-code-first-presentations-2026)
- [Simon Willison on agent definition](https://simonw.substack.com/p/i-think-agent-may-finally-have-a)
- [Skip Labs: Treat Codegen as Compiler](https://skiplabs.io/blog/codegen_as_compiler)

### Polish-specific
- [Geecon 2026](https://2026.geecon.org/)
- [Sadogursky Geecon 2025 talk](https://speaking.jbaru.ch/talks/2025-05-16-geecon-back-future/)
- [Devoxx Poland 2025](https://devoxx.pl/talks-by-tracks/)
- [Confitura 2025](https://2025.confitura.pl/)
- [Opanuj AI podcast](https://opanuj.ai/podcast/)
- [Psyho beats ChatGPT (Euronews)](https://www.euronews.com/next/2025/07/22/humanity-has-won-so-far-meet-the-worlds-best-programmer-who-beat-ai-and-chatgpt)
