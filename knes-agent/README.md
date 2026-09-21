# knes-agent

An LLM plays Final Fantasy on the emulator in this repo.

## Running it

```bash
export OPENAI_API_KEY=sk-...
./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=200"
```

That is the whole setup. One key covers every role the agent needs.

## Which models

The agent asks for **roles**, not names, so a provider can be swapped without touching
the agents:

| Role | Used by | Default (OpenAI) |
|---|---|---|
| planning vision | Advisor, Cartographer | `gpt-5` |
| executor vision | Executor | `gpt-5` |
| strong chat | Sonnet-role tool decisions | `gpt-5` |
| fast chat | Haiku-role scene reads, plan audits | `gpt-5` |

The fast role is **not** a cheaper model by default. `gpt-5-mini` called a solid red
image "blue" while this was being wired, and a cheap model misreading the screen has
already cost this project a smoke run — Gemini Flash-Lite put the party "near the Inn"
while it stood on the centre path, then confused Coneria Castle for Coneria Town. Set
`OPENAI_FAST_MODEL` if the cost matters more than the reading.

## Environment

| Variable | Meaning |
|---|---|
| `OPENAI_API_KEY` | enables the OpenAI provider, which is the default when set |
| `OPENAI_MODEL` | strong/vision model (default `gpt-5`) |
| `OPENAI_FAST_MODEL` | fast model (default `gpt-5`) |
| `OPENAI_EXECUTOR_MODEL` | executor vision, if it should differ |
| `OPENAI_REASONING_EFFORT` | `minimal`/`low`/`medium`/`high` (default `low`) |
| `KNES_LLM` | force a provider: `openai` or `anthropic+gemini` |
| `ANTHROPIC_API_KEY`, `GEMINI_API_KEY` | the older pairing; needs both |

Two OpenAI details worth knowing, because both fail quietly otherwise: the budget is
`max_completion_tokens` and it **also covers reasoning tokens**, so a ceiling sized for
the answer returns an empty message rather than a short one — `OpenAiHttp` floors it and
reports an exhausted budget instead of returning `""`. And `reasoning_effort` decides how
much of that budget thinking spends; `low` answers this project's prompts correctly while
spending none of it.

## Flags

| Flag | Default |
|---|---|
| `--rom=<path>` | `roms/ff.nes` |
| `--profile=<id>` | `ff1` |
| `--max-turns=<n>` | 5000 |
| `--fresh` | start a new run |
| `--resume=<dir>` | continue a previous one |
| `--cart` | enable the Cartographer (off by default; landmarks are preseeded) |
| `--remote[=<url>]` | drive the Compose UI's emulator over REST instead of an in-process one |
