# LLM-Plays-Games Research — Informing FF1 Agent V2

**Date:** 2026-05-01
**Context:** Brainstorm input for V2 of `knes-agent` (post PR #92).
**V1 problems to inform:** executor loops at Koog's 10-iteration cap without committing to actions; ~3 min per outer turn; no memory across outer turns; agent re-discovers everything each turn; cost ~$20-50 for a full attempted Garland run; Claude Code over MCP gets further with the same tool surface.

---

## TL;DR

The single most actionable finding is that **Anthropic's own Claude-Plays-Pokémon harness uses only three tools** — button press, a self-managed knowledge base, and a navigator — plus an "accordion" summarization that compresses every ~30 actions into a digest while preserving the knowledge base verbatim. That harness has been **simplified over time, not extended** ([ZenML LLMOps DB write-up of David Hershey's MLOps podcast](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic)). Our V1 has more tools and no comparable memory or summarization layer — that explains most of the looping and re-discovery. Adding a knowledge-base tool plus accordion summarization is the highest-leverage V2 change.

Second: **prompt caching with Anthropic gives 5-10× cost reduction on multi-turn agent loops** ([Anthropic docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), [ProjectDiscovery write-up: 59-70% savings](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching)). V1 currently builds a fresh `AIAgent` and a fresh `AnthropicLLMClient` per outer turn — this *defeats* caching. Wiring caching properly (sliding-window breakpoints over turn history) plus a Voyager-style **skill library of reusable Kotlin/Koog tools** instead of asking the LLM to compose primitives every time, is the structural fix that makes the whole loop affordable.

Third: **ReAct loops without external termination signals empirically diverge** on long-horizon, partially-observable tasks ([Reflexion paper, Shinn 2023](https://arxiv.org/abs/2303.11366); Anthropic's ZenML write-up notes Claude reaches Lt. Surge in ~35,000 actions vs ~26 hours for a human). V2 should not rely on the model to self-terminate the inner loop — it should use Koog's `singleRunStrategy()` (one tool call per LLM invocation, terminated by the runtime) and lift the outer reasoning back to our `AgentSession`, which already owns RAM-driven phase/watchdog detection.

---

## 1. Claude Plays Pokémon (Anthropic, 2025)

The canonical "LLM plays a JRPG" reference. Built by **David Hershey** at Anthropic as a side project, [now a public Twitch stream](https://twitch.tv/claudeplayspokemon).

### Architecture (per the Hershey MLOps podcast, summarized in the [ZenML LLMOps DB entry](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic) and the [Michael Liu deep-dive](https://michaelyliu6.github.io/posts/claude-plays-pokemon/))

- **Model:** Claude 3.7 Sonnet originally; Opus 4.5 in the November 2025 iteration ([Anthropic announcement archive](https://x.com/AnthropicAI/status/1894419042150027701)).
- **Context window:** 200k tokens, but the harness keeps usage under that via summarization.
- **Three tools, only:**
  1. `update_knowledge_base` — operations `add`, `edit`, `delete` over a structured dictionary with sections `current_status`, `game_progress`, `current_objectives`, `inventory`. **The model fully owns this memory.**
  2. `use_emulator` — button sequences (`['a', 'b', 'start', 'select', 'wait', 'up', 'right']`); each return is a screenshot **plus** a structured RAM dump (coordinates, money, badges, full team roster with HP/PP, walkable tiles).
  3. `navigator` — given a target coordinate, computes the button sequence for pathfinding. **This is the key abstraction:** the LLM is never asked to plan low-level walking.
- **Loop:** prompt assembly → tool execution → conversation history management → state preservation. Repeat.
- **Memory mechanism (accordion summarization):**
  - After every ~30 actions, the harness:
    1. Asks Claude to write a detailed progress summary.
    2. Clears the conversation history.
    3. Reinserts the summary as the first assistant message.
  - **A second LLM** reviews the knowledge base for inconsistencies and feeds suggestions back ([ZenML, summary of HN discussion](https://news.ycombinator.com/item?id=43173825)).
  - Knowledge base persists across summarization events — that's the long-term memory.
- **System prompt** is "mostly tips and tricks about how to use the tools" and *explicitly* tells Claude to trust only observed game state, not its training knowledge of Pokémon ([Liu write-up](https://michaelyliu6.github.io/posts/claude-plays-pokemon/)).

### Failure modes observed in the wild ([HN comments thread](https://news.ycombinator.com/item?id=43173825), [Liu write-up](https://michaelyliu6.github.io/posts/claude-plays-pokemon/))

- **Spatial reasoning collapse.** Stuck in Mt. Moon for ~78 hours one run, ~24 hours another. "Keeps forgetting where it has been." Tries to walk through walls. Confuses red NPC hat for exit carpet.
- **Visual misidentification.** Confuses player character with NPCs; mistakes random houses for Pokémon Centers.
- **Premature goal completion.** Declares quests done before they actually are.
- **Battle defaults to flee**, doesn't switch Pokémon strategically.
- **Visual memory is "quite poor"** (HN quote) because the accordion compaction discards screenshots.

### Scale required

- **Lt. Surge's badge (3rd badge)** required ~35,000 emulator actions = ~140 hours of compute ([Liu write-up]).
- A human completes Pokémon Red in ~26 hours of *gameplay*.
- Anthropic's own engagement with the project frames this as a benchmark for "long-horizon decision making and learning" ([TechCrunch](https://techcrunch.com/2025/02/25/anthropics-claude-ai-is-playing-pokemon-on-twitch-slowly/)).

### Lessons that transfer to FF1 V2

1. **Three tools are enough. Don't add tools — abstract them.** Our V1 has 13 tools; we should consolidate, *not* expand.
2. **A self-managed knowledge base is the thing that makes long horizons work.** This is the single most-cited difference vs. a vanilla ReAct loop.
3. **Pathfinding is scripted, not reasoned.** The `navigator` tool encapsulates "go from A to B," letting the LLM operate at decision granularity.
4. **Hershey "stripped out complexity over time"** ([ZenML]). Strong signal that simpler harnesses outperform feature-rich ones at this task class.

---

## 2. Other LLM-plays-games projects

### Voyager (NVIDIA, GPT-4, Minecraft, 2023)

[Voyager paper, arXiv 2305.16291](https://arxiv.org/abs/2305.16291); [GitHub: MineDojo/Voyager](https://github.com/MineDojo/Voyager); [project site](https://voyager.minedojo.org/).

Three components:

1. **Automatic curriculum** — proposes increasingly hard tasks based on current capabilities.
2. **Skill library** — stores executable code (JavaScript Mineflayer) indexed by embedding of the skill description. **Top-5 retrieval** on new tasks.
3. **Iterative prompting with self-verification** — GPT-4 acts as a critic that reviews whether a written skill achieves the proposed task; failures feed back as critique.

Performance: 3.3× more unique items, 2.3× longer travel, **15.3× faster tech-tree progression** vs prior SOTA.

**Key idea for FF1 V2:** the skill library is the thing. Once a behavior works (e.g. "navigate from Coneria castle exit to bridge tile"), persist it as a callable Kotlin function. Subsequent runs retrieve it instead of re-planning.

### PokéLLMon (2024)

[Paper, arXiv 2402.01118](https://arxiv.org/html/2402.01118v2). LLM as decision policy in Pokémon Showdown battles. Reached **human parity** by leveraging the LLM's prior knowledge of move effectiveness and using structured battle state representation. Lesson: where the LLM has training-data prior (battle mechanics), structured state + minimal scaffolding is enough. FF1 has less prior — name dialog, menu cursors, etc. require more scaffolding.

### PokéChamp (2025)

[Paper, arXiv 2503.04094](https://arxiv.org/html/2503.04094v1). Expert-level Pokémon agent using **minimax** (game tree search) with the LLM as a node evaluator/policy. Beats RL baselines on Showdown ladder. Lesson: for combinatorial decisions (which move on which turn) hybridizing search + LLM beats pure LLM rollout.

### PokeRL (Hugging Face / community, 2025)

[drubinstein.github.io/pokerl](https://drubinstein.github.io/pokerl/); [arXiv 2604.10812](https://arxiv.org/html/2604.10812v1); [HN discussion, Feb 2025](https://news.ycombinator.com/item?id=43269330). Beat Pokémon Red with **<10M parameters** using pure RL on RAM observations through PyBoy. Starts by pressing random buttons; no pretraining. Demonstrates that for fully-deterministic games with reachable RAM, classical RL outperforms LLM agents on speed and cost — *and* on reliability.

### Pokémon Showdown ladder (taylorhansen, hsahovic, leolellisr)

Several public RL agents reach gen4 Showdown rank 8 (Elo 1693) using PPO and self-play ([leolellisr/poke_RL](https://github.com/leolellisr/poke_RL); [taylorhansen/pokemonshowdown-ai](https://github.com/taylorhansen/pokemonshowdown-ai)). Their action spaces are tightly constrained (move/switch only). Lesson: action-space constraint dramatically narrows what the agent has to learn.

### AutoGPT / BabyAGI / CrewAI / LangGraph

General-purpose agent frameworks. Across community reports they fail at long-horizon games for the same reason our V1 does: **no first-class memory abstraction, no self-criticism, no skill compounding** — they just ReAct until the budget dies. LangGraph adds explicit state machines (closer to Koog's strategy graph). Voyager's lesson — that you need *all three* of curriculum + skill library + verification — is consistent across the framework graveyard.

---

## 3. Tool-using agent algorithms

### ReAct (Yao et al. 2022)

[Paper, arXiv 2210.03629](https://arxiv.org/abs/2210.03629); [project page](https://react-lm.github.io/). Interleaves "Thought / Action / Observation". Strong on QA-style tasks (HotPotQA), weaker on long sequential decision-making. Two failure modes the paper itself documents:

- **Reasoning stuck on hallucinated facts** — Thought sequences confidently restate wrong premises.
- **Action repetition** — same Action tried multiple times when Observation doesn't move state.

The Anthropic write-ups corroborate: pure ReAct on Pokémon "loops on visual misidentification" without an external memory or verifier.

### Reflexion (Shinn et al. 2023)

[Paper, arXiv 2303.11366](https://arxiv.org/abs/2303.11366); [GitHub](https://github.com/noahshinn/reflexion). Adds an **Evaluator** (gives reward/critique) and a **Self-Reflection** model (generates verbal lessons stored in episodic memory). On AlfWorld (long-horizon multi-step) and HotPotQA, beats vanilla ReAct by large margins. Cost: more LLM calls per outer turn, but each outer turn produces durable memory the next turn can use. **Direct match to V1's "no memory across outer turns" problem.**

### Plan-and-Solve (Wang et al. 2023)

[Paper, arXiv 2305.04091](https://arxiv.org/abs/2305.04091). First produce a multi-step plan, then execute step by step. Similar in spirit to our Advisor + Executor split. Paper specifically targets "missing-step errors" and "calculation errors" in chain-of-thought. Less novel than Reflexion but cheap.

### Tree of Thoughts (Yao et al. 2023)

Branch-and-evaluate over partial reasoning trees. Useful for problems with reversible decisions (puzzles). FF1 has mostly irreversible decisions (you can't un-fight a battle), so ToT-style search over future actions is high-cost low-value here. **Not recommended for V2.**

### Hand-rolled vs framework loops

Strong consensus across recent agent literature ([ReAct paper §6 ablations](https://arxiv.org/abs/2210.03629); Hershey on simplification): **frameworks help with prototyping, but production agents are usually hand-rolled with a framework providing utilities.** Our V1 used Koog's `reActStrategy` as the inner loop primitive; V2 should use Koog's lower-level `singleRunStrategy` (one LLM call per turn) and put the loop in our own `AgentSession` where we already own RAM-driven termination.

---

## 4. Koog 0.5.1 alternatives to `reActStrategy`

Confirmed via `mcp__context7__query-docs` against `/jetbrains/koog`:

### `singleRunStrategy()`

The exact primitive we need. Source: [`docs/docs/nodes-and-components.md`](https://github.com/jetbrains/koog/blob/develop/docs/docs/nodes-and-components.md).

```kotlin
public fun singleRunStrategy(): AIAgentGraphStrategy<String, String> = strategy("single_run") {
    val nodeCallLLM by nodeLLMRequest("sendInput")
    val nodeExecuteTool by nodeExecuteTool("nodeExecuteTool")
    val nodeSendToolResult by nodeLLMSendToolResult("nodeSendToolResult")

    edge(nodeStart forwardTo nodeCallLLM)
    edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
    edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
    edge(nodeExecuteTool forwardTo nodeSendToolResult)
    edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
}
```

This still loops as long as the LLM keeps calling tools. We want a **stricter** variant that forces termination after exactly one tool call — easy to write as a custom strategy.

### Custom subgraph strategies

[`docs/docs/custom-subgraphs.md`](https://github.com/jetbrains/koog/blob/develop/docs/docs/custom-subgraphs.md) shows how to compose subgraphs. The `inputProcessing → reasoning → toolRun → responseGeneration` template is the textbook "Plan-and-Solve" pattern; can encode Advisor/Executor inside a single Koog agent rather than two separate `AIAgent` instances.

### History compression

`nodeLLMCompressHistory<T>(strategy = HistoryCompressionStrategy.WholeHistory, preserveMemory = true)` — exactly Hershey's accordion pattern. Source: [`docs/docs/history-compression.md`](https://github.com/jetbrains/koog/blob/develop/docs/docs/history-compression.md).

```kotlin
private suspend fun AIAgentContext.historyIsTooLong(): Boolean =
    llm.readSession { prompt.messages.size > 100 }
// Then: edge(executeTool forwardTo compressHistory onCondition { historyIsTooLong() })
```

### Long-term memory

[`agents-features-longterm-memory`](https://github.com/jetbrains/koog/blob/develop/agents/agents-features/agents-features-longterm-memory/Module.md) — Koog has a built-in `LongTermMemory` feature with RAG retrieval and conversation ingestion. Concepts (`Concept(name, description, FactType.SINGLE/MULTIPLE)`) map naturally to Hershey's knowledge-base sections.

```kotlin
val gameProgress = Concept("game-progress", "Current FF1 progress milestones", FactType.MULTIPLE)
val partyComposition = Concept("party", "Current party class composition", FactType.SINGLE)
```

**Implication for V2:** we don't need to hand-roll a knowledge base. Koog's LongTermMemory feature plus `nodeLoadFromMemory` / `nodeSaveToMemory` already covers it.

### `maxIterations` / `maxAgentIterations`

Already used in V1 (set to 10). Documented in [`AIAgentConfig`](https://github.com/jetbrains/koog) bytecode (we confirmed by jar inspection during V1). The `internal` modifier on `AIAgentMaxNumberOfIterationsReachedException` is a known Koog 0.5.1 inconvenience; matching by simple-name (as V1 does) is the workaround.

---

## 5. FF1 / classical-RPG bots and routes

### TASVideos / speedrun community

[TasVideos: NES Final Fantasy 1 game resources](https://tasvideos.org/GameResources/NES/FinalFantasy1) — exhaustive RAM map and RNG documentation. Highlights:

- `$00F5` / `$00F6` — encounter counter; reset on power cycle. Matches our `encounterCounter` in `ff1.json`.
- `$688A` — preserved-across-battles index that determines the next battle's enemy count and surprise calculation. **Not in our profile yet.** Could be useful for the agent to reason about combat outcomes.
- The community-known **Route to Garland**: at the bridge guard scene the agent simply needs to walk south one tile from the throne room exit.

### Speedrun.com any% guides

[speedrun.com/final_fantasy_nes/guides](https://www.speedrun.com/final_fantasy_nes/guides): the [ShaneZell single-segment guide](https://www.speedrun.com/final_fantasy_nes/guides/vk3vf) is the canonical text for early-game routing. The Garland fight is essentially scripted: 4× FIGHT on Garland, no menus, expected ~3 turns.

### Pure-RAM bots

PokeRL (above) shows the pattern: **read RAM → state vector → policy → button**. No screenshots needed for purely deterministic games. FF1 is fully deterministic given a seed; in principle a 100% scripted bot could beat Garland from boot. The interesting question is *which decisions to delegate to the LLM*. Speedrun community delegates everything to memorized inputs; we want the LLM to handle anything not memorized.

### Macro vs micro split (transferable principle)

Across game-bot literature:

- **Micro** (frame-perfect input, menu sequences, walking known paths) — *always* scripted. LLM should never handle this.
- **Macro** (which boss to attack, which equipment to buy, when to grind) — LLM-suitable.

V1 puts the LLM in the micro layer. **That's the core mistake.** V2 must move the LLM up to macro and delegate micro to scripted Kotlin actions (`BattleFightAll`, `WalkUntilEncounter`, plus new ones we'll need: `PressStartUntilOverworld`, `CompleteNewGameFlow`, `WalkToBridge`).

---

## 6. Cost optimization (Anthropic-specific)

### Prompt caching

[Anthropic prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching). Headlines:

- **Up to 90% input cost reduction**, **up to 85% latency reduction** for long stable prefixes.
- Cache writes cost 25% more than the base input price.
- Cache reads cost 10% of the base input price.
- 5-minute cache TTL (extendable to 1 hour with `ephemeral` cache control + opt-in).

### Multi-turn agent loop math

From [the same docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) and [Joe Njenga's Medium post](https://medium.com/ai-software-engineer/anthropic-just-fixed-the-biggest-hidden-cost-in-ai-agents-using-automatic-prompt-caching-9d47c95903c5): **a 50-turn agent loop with a 10k system prompt** sends 500k tokens of identical instructions across the run. Caching that prefix turns it into ~10× cost reduction *on input*.

[ProjectDiscovery: 59-70% LLM cost reduction in production](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching). Their pattern: **4 cache breakpoints** — turn history, round boundaries, shared system prompts, tool descriptions. Reduces a 25-turn investigation to ~20% of uncached input cost.

### V1 currently defeats caching

V1's `ExecutorAgent.newAgent()` builds **a fresh `AnthropicLLMClient` per call**. The client is the layer that would set cache breakpoints (Koog 0.5.1 doesn't expose `cache_control` directly — see Koog issue tracker — but the underlying SDK does). Each fresh client = no cache reuse = full input price every turn. **Fixing this is a 10× cost win on its own.**

### Model routing

Across the literature ([Anthropic best-practices, multiple agent posts]):

- **Haiku 4.5** — sufficient for "press button, observe RAM" loops where the right action is obvious.
- **Sonnet 4.5** — needed for tool selection, plan execution, multi-step micro reasoning.
- **Opus 4 / 4.1** — only worth it for genuinely uncertain macro decisions.

V1 uses Sonnet executor + Opus advisor uniformly. V2 should consider routing: Haiku as the default executor when phase = `Battle` (highly constrained), Sonnet for `TitleOrMenu` and `Overworld`, Opus only for the advisor and only on phase boundaries.

### Batch tools

Anthropic's tool-use API supports parallel tool calls. Koog 0.5.1's `executeMultipleTools` ([from the functional strategy example in `docs/docs/agents/functional-agents.md`](https://github.com/jetbrains/koog)) wires this up. For FF1 it's marginal — most tool calls are sequential by game causality — but `getState + getScreen` could be parallelized.

---

## 7. Decision matrix for V2

| V1 problem | Candidate fix from research | Source(s) | Effort | Expected impact |
|---|---|---|---|---|
| Executor loops, hits 10-iter cap each outer turn | Replace `reActStrategy` with `singleRunStrategy` (1 tool call per LLM invocation), put outer loop in `AgentSession` | [Koog `singleRunStrategy`](https://github.com/jetbrains/koog/blob/develop/docs/docs/nodes-and-components.md), [ReAct ablations §6](https://arxiv.org/abs/2210.03629) | M | High |
| No memory across outer turns; agent re-discovers state | Add Koog `LongTermMemory` feature with FF1-specific `Concept`s; or hand-rolled knowledge-base tool mirroring Hershey's `update_knowledge_base` | [Koog longterm-memory](https://github.com/jetbrains/koog/blob/develop/agents/agents-features/agents-features-longterm-memory/Module.md), [CPP architecture, ZenML](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic) | M | High |
| LLM does menu/dialog micro itself, burns iterations | Voyager-style **skill library** of named scripted actions: `PressStartUntilOverworld`, `CreateDefaultParty`, `WalkToBridge`, plus existing `BattleFightAll`, `WalkUntilEncounter` | [Voyager paper §3](https://arxiv.org/abs/2305.16291), [CPP `navigator` tool](https://michaelyliu6.github.io/posts/claude-plays-pokemon/) | L-M | High |
| Cost ~$20-50 per Garland run; fresh client per call defeats caching | Single long-lived `AnthropicLLMClient`, sliding cache breakpoints over turn history | [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), [ProjectDiscovery 59-70% savings](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching) | M | High (10× cost) |
| `Overworld(0,0)` misclassified at title (V1 partly fixed via bootFlag); coarse phase coverage | Expand `RamObserver` with NewGame/NameEntry/Dialog phases using `screenState`, `menuCursor`, `bootFlag`, `partyCreated` heuristics; add unit tests per phase | [TasVideos FF1 RAM map](https://tasvideos.org/GameResources/NES/FinalFantasy1), our `ff1.json` | L | Medium |
| Outer loop has no self-criticism, just retries with fresh agent | Add Reflexion-style self-reflection: when phase didn't change after N executor turns, ask Opus advisor to write a one-paragraph "what went wrong" note that gets injected into the next executor turn | [Reflexion paper](https://arxiv.org/abs/2303.11366), [Hershey: secondary LLM for KB review](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic) | M | Medium |
| Tool surface bloat (13 tools registered with Koog) | Hide low-level (`step`, `tap`, `sequence`, `press`, `release`) behind composite skills; expose a smaller "agent surface" of 4-6 high-level tools per phase | [CPP 3-tool architecture](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic), Hershey simplification heuristic | M | Medium |
| Advisor/Executor are independent `AIAgent` instances, no shared state | Refactor as a single Koog strategy graph with `subgraph` for advisor → executor → reflection, sharing prompt history | [Koog custom-subgraphs](https://github.com/jetbrains/koog/blob/develop/docs/docs/custom-subgraphs.md), [Plan-and-Solve §3](https://arxiv.org/abs/2305.04091) | M-H | Medium |
| Battle decisions ad-hoc | Use existing `BattleFightAll` action plus a deterministic battle script for Garland (4× FIGHT, no menus) | [Speedrun.com FF1 guide](https://www.speedrun.com/final_fantasy_nes/guides/vk3vf) | S | Medium for V2-C scope (reach battle); High for V3 (defeat) |

---

## 8. Recommendations for V2

Ranked by expected impact-to-effort ratio. Goal of V2 (per user-confirmed scope C): drive boot → start of Garland battle, no win required.

### 1. Replace `reActStrategy` with `singleRunStrategy` + outer-loop ownership *(highest impact, medium effort)*

Koog's `singleRunStrategy` makes one tool call per LLM invocation. Wrap it in our `AgentSession` outer loop, which already owns RAM-driven phase detection and the watchdog. Termination becomes deterministic: outer turn ends when (a) tool result returns, (b) phase changed, or (c) agent emitted plain text without a tool call. Eliminates the iteration-cap-as-default failure mode entirely. ([Koog](https://github.com/jetbrains/koog/blob/develop/docs/docs/nodes-and-components.md))

### 2. Build a Voyager-style skill library of FF1 macro actions *(highest impact, medium effort)*

New skills, each as a Kotlin function exposed as a single `@Tool`:

- `pressStartUntilOverworld()` — hammers START until `bootFlag == 0x4D`.
- `createDefaultParty()` — scripted character creation (Fighter/Thief/BlackBelt/RedMage or whatever the user prefers).
- `walkToBridge()` — known overworld coordinate path from Coneria castle exit to bridge tile, watching `worldX`/`worldY`.
- Reuse existing `BattleFightAll`, `WalkUntilEncounter`.

The agent's macro decisions become "which skill to invoke." Mirrors Hershey's `navigator` and Voyager's skill library. ([Voyager](https://arxiv.org/abs/2305.16291), [CPP Liu](https://michaelyliu6.github.io/posts/claude-plays-pokemon/))

### 3. Wire prompt caching properly *(high impact, medium effort)*

Long-lived `AnthropicLLMClient`, cache breakpoints around: system prompt + tool descriptions (rarely change), knowledge base (changes per turn but we cache up to it), recent turn history (sliding). Should give 5-10× input cost reduction, dropping a Garland-attempt cost from $20-50 to single-digit dollars. ([Anthropic docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching))

### 4. Add accordion summarization + Koog `LongTermMemory` *(medium impact, medium effort)*

Trigger summarization at >40 messages in history. Persist `Concept`s for `currentObjective`, `gameProgress`, `partyComposition`, `recentFailures`. The "what to remember" comes from a deliberate spec, not the model's whim. Mirrors Hershey's KB. ([Koog longterm-memory](https://github.com/jetbrains/koog/blob/develop/agents/agents-features/agents-features-longterm-memory/Module.md), [ZenML CPP](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic))

### 5. Phase-aware model routing *(medium impact, low effort)*

- TitleOrMenu, Boot, NewGame, NameEntry → Sonnet (uncertain, screen-dependent)
- Overworld navigation → Haiku (skill invocations only, easy)
- Battle → Haiku (with deterministic Garland script overlay)
- Watchdog/escalation/reflection → Opus

Tune by measurement, not guessing. Adds ~30 LOC of routing logic. ([Anthropic best-practices](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), [PokéLLMon](https://arxiv.org/html/2402.01118v2))

---

## Appendix: topics with limited or no useful sources

- **Koog issue tracker discussions on iteration caps / looping** — the public Koog repo issues page didn't surface targeted discussion threads for these specific problems within the time budget; recommendations rely on Context7 docs and our V1 jar inspection.
- **Direct comparison of Plan-and-Solve vs ReAct on game-playing tasks** — no head-to-head paper found; transferred from generic reasoning benchmarks.
- **Detailed prompts from Claude Plays Pokémon** — Anthropic has not published the system prompt or the knowledge-base schema in full; reconstructions above are from secondary sources (HN comments, Liu's analysis, ZenML's podcast write-up) and may differ in detail from the production harness.
