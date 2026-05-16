# v2 Namespace Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote all `knes.agent.v2.*` code to `knes.agent.*`, renaming V2-prefixed classes, so the `v2/` subdirectory no longer exists.

**Architecture:** Pure mechanical rename — no logic changes. Every file in `v2/` gets a new path with `v2/` removed; package declarations and imports are updated by `sed`; three classes shed their `V2` prefix. Verification is compilation.

**Tech Stack:** Kotlin, Gradle, macOS BSD `sed`

All commands run from the project root: `/Users/askowronski/Priv/kNES`

---

## File Map

**Source files moving** (24 total):

| From | To | Class rename |
|------|-----|-------------|
| `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt` | `…/knes/agent/Main.kt` | — |
| `…/v2/V2Config.kt` | `…/knes/agent/Config.kt` | `V2Config` → `Config` |
| `…/v2/agents/AdvisorAgent.kt` | `…/knes/agent/agents/AdvisorAgent.kt` | — |
| `…/v2/agents/CartographerAgent.kt` | `…/knes/agent/agents/CartographerAgent.kt` | — |
| `…/v2/agents/ExecutorAgent.kt` | `…/knes/agent/agents/ExecutorAgent.kt` | — |
| `…/v2/agents/ReviewerAgent.kt` | `…/knes/agent/agents/ReviewerAgent.kt` | — |
| `…/v2/llm/AnthropicHttp.kt` | `…/knes/agent/llm/AnthropicHttp.kt` | — |
| `…/v2/llm/AnthropicSession.kt` | `…/knes/agent/llm/AnthropicSession.kt` | — |
| `…/v2/llm/GeminiPro31Client.kt` | `…/knes/agent/llm/GeminiPro31Client.kt` | — |
| `…/v2/llm/HaikuClient.kt` | `…/knes/agent/llm/HaikuClient.kt` | — |
| `…/v2/llm/SonnetClient.kt` | `…/knes/agent/llm/SonnetClient.kt` | — |
| `…/v2/runtime/Campaign.kt` | `…/knes/agent/runtime/Campaign.kt` | — |
| `…/v2/runtime/Log.kt` | `…/knes/agent/runtime/Log.kt` | — |
| `…/v2/runtime/MilestonePredicates.kt` | `…/knes/agent/runtime/MilestonePredicates.kt` | — |
| `…/v2/runtime/Phase.kt` | `…/knes/agent/runtime/Phase.kt` | — |
| `…/v2/runtime/Plan.kt` | `…/knes/agent/runtime/Plan.kt` | — |
| `…/v2/runtime/Resumer.kt` | `…/knes/agent/runtime/Resumer.kt` | — |
| `…/v2/runtime/SnapshotDumper.kt` | `…/knes/agent/runtime/SnapshotDumper.kt` | — |
| `…/v2/runtime/TurnLog.kt` | `…/knes/agent/runtime/TurnLog.kt` | — |
| `…/v2/runtime/V2Memory.kt` | `…/knes/agent/runtime/Memory.kt` | `V2Memory` → `Memory` |
| `…/v2/runtime/V2RunDirectory.kt` | `…/knes/agent/runtime/RunDirectory.kt` | `V2RunDirectory` → `RunDirectory` |
| `…/v2/runtime/Watchdog.kt` | `…/knes/agent/runtime/Watchdog.kt` | — |
| `…/v2/tools/MenuWalker.kt` | `…/knes/agent/tools/MenuWalker.kt` | — |
| `…/v2/tools/ToolSurface.kt` | `…/knes/agent/tools/ToolSurface.kt` | — |

**Test files moving** (5 total):

| From | To | Class rename |
|------|-----|-------------|
| `knes-agent/src/test/kotlin/knes/agent/v2/llm/HaikuClientParseTest.kt` | `…/knes/agent/llm/HaikuClientParseTest.kt` | — |
| `…/v2/runtime/PhaseTest.kt` | `…/knes/agent/runtime/PhaseTest.kt` | — |
| `…/v2/runtime/V2MemoryTest.kt` | `…/knes/agent/runtime/MemoryTest.kt` | `V2MemoryTest` → `MemoryTest` |
| `…/v2/runtime/WatchdogTest.kt` | `…/knes/agent/runtime/WatchdogTest.kt` | — |
| `…/v2/tools/MenuWalkerTest.kt` | `…/knes/agent/tools/MenuWalkerTest.kt` | — |

**Unchanged packages** (do not touch): `skills/`, `perception/`, `pathfinding/`, `runtime/LandmarkContext.kt`, `runtime/StrategyContext.kt`, `runtime/ToolCallLog.kt`

---

## Task 1: Create new package directories

**Files:** New directories only — no Kotlin files yet.

- [ ] **Step 1: Create source directories**

```bash
mkdir -p knes-agent/src/main/kotlin/knes/agent/agents
mkdir -p knes-agent/src/main/kotlin/knes/agent/llm
mkdir -p knes-agent/src/main/kotlin/knes/agent/tools
```

(`runtime/` already exists — skip it.)

- [ ] **Step 2: Create test directories**

```bash
mkdir -p knes-agent/src/test/kotlin/knes/agent/agents
mkdir -p knes-agent/src/test/kotlin/knes/agent/llm
mkdir -p knes-agent/src/test/kotlin/knes/agent/tools
```

(`runtime/` already exists in test tree — skip it.)

---

## Task 2: Move `llm/` layer

**Files:**
- Move: `knes-agent/src/main/kotlin/knes/agent/v2/llm/` (5 files)
- Move: `knes-agent/src/test/kotlin/knes/agent/v2/llm/HaikuClientParseTest.kt`

- [ ] **Step 1: Move source files**

```bash
git mv knes-agent/src/main/kotlin/knes/agent/v2/llm/AnthropicHttp.kt \
       knes-agent/src/main/kotlin/knes/agent/llm/AnthropicHttp.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/llm/AnthropicSession.kt \
       knes-agent/src/main/kotlin/knes/agent/llm/AnthropicSession.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/llm/GeminiPro31Client.kt \
       knes-agent/src/main/kotlin/knes/agent/llm/GeminiPro31Client.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/llm/HaikuClient.kt \
       knes-agent/src/main/kotlin/knes/agent/llm/HaikuClient.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/llm/SonnetClient.kt \
       knes-agent/src/main/kotlin/knes/agent/llm/SonnetClient.kt
```

- [ ] **Step 2: Move test file**

```bash
git mv knes-agent/src/test/kotlin/knes/agent/v2/llm/HaikuClientParseTest.kt \
       knes-agent/src/test/kotlin/knes/agent/llm/HaikuClientParseTest.kt
```

---

## Task 3: Move `tools/` layer

**Files:**
- Move: `knes-agent/src/main/kotlin/knes/agent/v2/tools/` (2 files)
- Move: `knes-agent/src/test/kotlin/knes/agent/v2/tools/MenuWalkerTest.kt`

- [ ] **Step 1: Move source files**

```bash
git mv knes-agent/src/main/kotlin/knes/agent/v2/tools/MenuWalker.kt \
       knes-agent/src/main/kotlin/knes/agent/tools/MenuWalker.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/tools/ToolSurface.kt \
       knes-agent/src/main/kotlin/knes/agent/tools/ToolSurface.kt
```

- [ ] **Step 2: Move test file**

```bash
git mv knes-agent/src/test/kotlin/knes/agent/v2/tools/MenuWalkerTest.kt \
       knes-agent/src/test/kotlin/knes/agent/tools/MenuWalkerTest.kt
```

---

## Task 4: Move `runtime/` layer

**Files:**
- Move: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/` (11 files, 2 renamed)
- Move: `knes-agent/src/test/kotlin/knes/agent/v2/runtime/` (3 files, 1 renamed)

Note: The target `runtime/` directory already exists and contains v1 runtime files (`LandmarkContext.kt`, `StrategyContext.kt`, `ToolCallLog.kt`). These are **not touched**.

- [ ] **Step 1: Move source files (no rename)**

```bash
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/Campaign.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/Campaign.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/Log.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/Log.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/MilestonePredicates.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/MilestonePredicates.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/Phase.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/Phase.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/Plan.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/Plan.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/Resumer.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/Resumer.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/SnapshotDumper.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/SnapshotDumper.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/TurnLog.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/TurnLog.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/Watchdog.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/Watchdog.kt
```

- [ ] **Step 2: Move + rename V2Memory and V2RunDirectory**

```bash
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/V2Memory.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/Memory.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/runtime/V2RunDirectory.kt \
       knes-agent/src/main/kotlin/knes/agent/runtime/RunDirectory.kt
```

- [ ] **Step 3: Move test files (no rename)**

```bash
git mv knes-agent/src/test/kotlin/knes/agent/v2/runtime/PhaseTest.kt \
       knes-agent/src/test/kotlin/knes/agent/runtime/PhaseTest.kt
git mv knes-agent/src/test/kotlin/knes/agent/v2/runtime/WatchdogTest.kt \
       knes-agent/src/test/kotlin/knes/agent/runtime/WatchdogTest.kt
```

- [ ] **Step 4: Move + rename V2MemoryTest**

```bash
git mv knes-agent/src/test/kotlin/knes/agent/v2/runtime/V2MemoryTest.kt \
       knes-agent/src/test/kotlin/knes/agent/runtime/MemoryTest.kt
```

---

## Task 5: Move `agents/` layer

**Files:**
- Move: `knes-agent/src/main/kotlin/knes/agent/v2/agents/` (4 files)

- [ ] **Step 1: Move source files**

```bash
git mv knes-agent/src/main/kotlin/knes/agent/v2/agents/AdvisorAgent.kt \
       knes-agent/src/main/kotlin/knes/agent/agents/AdvisorAgent.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/agents/CartographerAgent.kt \
       knes-agent/src/main/kotlin/knes/agent/agents/CartographerAgent.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/agents/ExecutorAgent.kt \
       knes-agent/src/main/kotlin/knes/agent/agents/ExecutorAgent.kt
git mv knes-agent/src/main/kotlin/knes/agent/v2/agents/ReviewerAgent.kt \
       knes-agent/src/main/kotlin/knes/agent/agents/ReviewerAgent.kt
```

---

## Task 6: Move top-level v2 files

**Files:**
- Move: `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt`
- Move + rename: `knes-agent/src/main/kotlin/knes/agent/v2/V2Config.kt` → `Config.kt`

- [ ] **Step 1: Move Main.kt**

```bash
git mv knes-agent/src/main/kotlin/knes/agent/v2/Main.kt \
       knes-agent/src/main/kotlin/knes/agent/Main.kt
```

- [ ] **Step 2: Move and rename V2Config**

```bash
git mv knes-agent/src/main/kotlin/knes/agent/v2/V2Config.kt \
       knes-agent/src/main/kotlin/knes/agent/Config.kt
```

---

## Task 7: Rewrite package declarations, imports, and class names

All 29 moved files (24 source + 5 test) still contain `knes.agent.v2` in their package declarations and imports. This task fixes them in two passes.

**Files:** All moved .kt files (now in their new locations, outside `v2/`).

- [ ] **Step 1: Strip `v2.` from all package declarations and imports**

This single sed command handles all sub-packages and the top-level package:

```bash
find knes-agent/src -not -path "*/v2/*" -name "*.kt" \
  | xargs grep -l "knes\.agent\.v2" \
  | xargs sed -i '' 's/knes\.agent\.v2/knes.agent/g'
```

What it does:
- `package knes.agent.v2` → `package knes.agent`
- `package knes.agent.v2.agents` → `package knes.agent.agents`
- `package knes.agent.v2.llm` → `package knes.agent.llm`
- `package knes.agent.v2.runtime` → `package knes.agent.runtime`
- `package knes.agent.v2.tools` → `package knes.agent.tools`
- `import knes.agent.v2.llm.HaikuClient` → `import knes.agent.llm.HaikuClient`
- (all other `knes.agent.v2.*` import sites)

- [ ] **Step 2: Verify no moved file still references v2**

```bash
find knes-agent/src -not -path "*/v2/*" -name "*.kt" \
  | xargs grep -l "knes\.agent\.v2" 2>/dev/null
```

Expected: no output (empty). If any files appear, open them and fix remaining `knes.agent.v2` references manually.

- [ ] **Step 3: Apply class renames across all moved files**

```bash
find knes-agent/src -not -path "*/v2/*" -name "*.kt" \
  | xargs sed -i '' \
      -e 's/V2Memory/Memory/g' \
      -e 's/V2RunDirectory/RunDirectory/g' \
      -e 's/V2Config/Config/g'
```

What it changes:
- `class V2Memory` → `class Memory` (in `Memory.kt`)
- `class V2RunDirectory` → `class RunDirectory` (in `RunDirectory.kt`)
- `data class V2Config` → `data class Config` (in `Config.kt`)
- All usages of `V2Memory(`, `V2RunDirectory(`, `V2Config(` in `Main.kt`, agents, and tests

- [ ] **Step 4: Verify class renames are complete**

```bash
find knes-agent/src -not -path "*/v2/*" -name "*.kt" \
  | xargs grep -n "V2Memory\|V2RunDirectory\|V2Config" 2>/dev/null
```

Expected: no output. If any files appear, fix manually.

---

## Task 8: Update `build.gradle`

**Files:**
- Modify: `knes-agent/build.gradle`

The `runV2` task's `mainClass = 'knes.agent.v2.MainKt'` now duplicates the `application { mainClass = 'knes.agent.MainKt' }` default (which is already correct). Delete `runV2`.

- [ ] **Step 1: Delete the `runV2` task block from build.gradle**

Open `knes-agent/build.gradle` and delete the entire block:

```groovy
tasks.register('runV2', JavaExec) {
    group = 'application'
    description = 'Run knes-agent v2 (PDF architecture)'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'knes.agent.v2.MainKt'
    standardInput = System.in
    workingDir = rootProject.projectDir
    if (project.hasProperty('appArgs')) {
        // ... (delete all lines through the closing brace)
    }
}
```

After deletion, `./gradlew run` becomes the equivalent of the old `./gradlew runV2`.

- [ ] **Step 2: Verify no remaining v2 references in build.gradle**

```bash
grep "v2" knes-agent/build.gradle
```

Expected: only the description string `'Run knes-agent v2 (PDF architecture)'` is gone; no `knes.agent.v2` references remain. If `knes.agent.v2` appears anywhere, fix it.

---

## Task 9: Delete the empty `v2/` directory tree

- [ ] **Step 1: Confirm v2/ is empty**

```bash
find knes-agent/src -path "*/v2/*" -name "*.kt"
```

Expected: no output. If any `.kt` files remain, go back and move them.

- [ ] **Step 2: Delete the v2/ directories**

```bash
rm -rf knes-agent/src/main/kotlin/knes/agent/v2
rm -rf knes-agent/src/test/kotlin/knes/agent/v2
```

- [ ] **Step 3: Verify deletion**

```bash
ls knes-agent/src/main/kotlin/knes/agent/
```

Expected output contains: `agents  Config.kt  llm  Main.kt  pathfinding  perception  runtime  skills  tools`
— no `v2` directory.

---

## Task 10: Compile verification

- [ ] **Step 1: Compile main sources**

```bash
./gradlew :knes-agent:compileKotlin
```

Expected: `BUILD SUCCESSFUL` with zero errors. If compilation fails, read the error, find the file with the broken reference, and fix the import or package declaration.

- [ ] **Step 2: Compile test sources**

```bash
./gradlew :knes-agent:compileTestKotlin
```

Expected: `BUILD SUCCESSFUL` with zero errors.

- [ ] **Step 3: Run unit tests (non-live)**

```bash
./gradlew :knes-agent:test --tests "knes.agent.runtime.*" \
                            --tests "knes.agent.llm.*" \
                            --tests "knes.agent.tools.*" \
                            --tests "knes.agent.pathfinding.*" \
                            --tests "knes.agent.perception.*"
```

Expected: all pass (or the same tests that were failing before this rename — no regressions introduced by the rename).

---

## Task 11: Commit

- [ ] **Step 1: Stage all changes**

```bash
git add -A knes-agent/src/
git add knes-agent/build.gradle
```

- [ ] **Step 2: Verify staged diff is purely mechanical**

```bash
git diff --staged --stat
```

Expected: ~29 renames, 1 build.gradle modification. No new logic files, no deletions of non-v2 files.

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
chore: promote v2 to knes.agent, drop V2 class prefixes

knes.agent.v2.* → knes.agent.*; V2Memory → Memory, V2RunDirectory →
RunDirectory, V2Config → Config; runV2 Gradle task removed (use run).
EOF
)"
```
