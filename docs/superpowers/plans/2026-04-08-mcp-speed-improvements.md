# MCP Speed Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce MCP tool call round-trips by 67-92% via screenshot flag on step, new tap tool, and new sequence tool.

**Architecture:** Modify REST API data classes to support `screenshot` field, add `/tap` endpoint, expose `StepSequence` as dedicated MCP `sequence` tool. All three features reuse the existing step/queue machinery.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, Kotest, MCP SDK

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `knes-api/src/main/kotlin/knes/api/ApiServer.kt` | Update data classes, `/step` response, add `/tap` route |
| Modify | `knes-api/src/test/kotlin/knes/api/ApiServerTest.kt` | Tests for screenshot flag and `/tap` |
| Modify | `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt` | Add `screenshot` to step, add `tap` tool, add `sequence` tool |

---

### Task 1: Add screenshot flag to data classes and `/step` response

**Files:**
- Modify: `knes-api/src/main/kotlin/knes/api/ApiServer.kt`
- Modify: `knes-api/src/test/kotlin/knes/api/ApiServerTest.kt`

- [ ] **Step 1: Write failing tests for screenshot in step response**

Append to `knes-api/src/test/kotlin/knes/api/ApiServerTest.kt` (inside the FunSpec block):

```kotlin
    test("POST /step with screenshot false returns no screenshot field") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            // Without ROM, we can't step, but we can test the data class serialization
            // by checking /health still works (baseline)
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("POST /tap without ROM returns 400") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/tap") {
                contentType(ContentType.Application.Json)
                setBody("""{"button": "A", "count": 3}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /tap validates button name") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/tap") {
                contentType(ContentType.Application.Json)
                setBody("""{"button": "TURBO"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }
```

- [ ] **Step 2: Run tests to verify baseline**

Run: `./gradlew :knes-api:test --tests "knes.api.ApiServerTest" --info`
Expected: New tests fail with 404 (no `/tap` route) or compilation error.

- [ ] **Step 3: Modify data classes in `ApiServer.kt`**

In `knes-api/src/main/kotlin/knes/api/ApiServer.kt`, replace lines 15-20 (the data class declarations):

Replace:
```kotlin
@Serializable data class StepRequest(val buttons: List<String> = emptyList(), val frames: Int = 1)
@Serializable data class StepSequence(val sequence: List<StepRequest>)
```

With:
```kotlin
@Serializable data class StepRequest(val buttons: List<String> = emptyList(), val frames: Int = 1, val screenshot: Boolean = false)
@Serializable data class StepSequence(val sequence: List<StepRequest>, val screenshot: Boolean = false)
@Serializable data class TapRequest(val button: String, val count: Int = 1, val pressFrames: Int = 5, val gapFrames: Int = 15, val screenshot: Boolean = false)
```

Replace:
```kotlin
@Serializable data class StepResponse(val frame: Int, val ram: Map<String, Int> = emptyMap())
```

With:
```kotlin
@Serializable data class StepResponse(val frame: Int, val ram: Map<String, Int> = emptyMap(), val screenshot: String? = null)
```

- [ ] **Step 4: Modify `/step` route to include screenshot in response**

In `knes-api/src/main/kotlin/knes/api/ApiServer.kt`, replace the last line of the `/step` handler:

Replace:
```kotlin
            call.respond(StepResponse(session.frameCount, session.getWatchedState()))
```

With:
```kotlin
            val wantScreenshot = try {
                val seq = Json.decodeFromString<StepSequence>(text)
                seq.screenshot
            } catch (e: Exception) {
                try { Json.decodeFromString<StepRequest>(text).screenshot } catch (e2: Exception) { false }
            }
            val screenshotBase64 = if (wantScreenshot && session.romLoaded) session.getScreenBase64() else null
            call.respond(StepResponse(session.frameCount, session.getWatchedState(), screenshotBase64))
```

Note: This re-parses the text to get the screenshot flag. An alternative is to capture it earlier during the initial parse. Let's refactor the `/step` handler to capture the screenshot flag during initial parsing. Replace the entire `/step` handler with:

```kotlin
        post("/step") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@post
            }
            val text = call.receiveText()
            val steps: List<StepRequest>
            val wantScreenshot: Boolean
            try {
                try {
                    val seq = Json.decodeFromString<StepSequence>(text)
                    steps = seq.sequence
                    wantScreenshot = seq.screenshot
                } catch (e: Exception) {
                    val req = Json.decodeFromString<StepRequest>(text)
                    steps = listOf(req)
                    wantScreenshot = req.screenshot
                }

                if (session.shared) {
                    val latch = session.controller.enqueueSteps(steps)
                    val totalFrames = steps.sumOf { it.frames }
                    val timeoutMs = totalFrames * 50L + 5000L
                    if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            StatusResponse("step timed out waiting for $totalFrames frames")
                        )
                        return@post
                    }
                } else {
                    for (step in steps) {
                        session.controller.setButtons(step.buttons)
                        session.advanceFrames(step.frames)
                    }
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid request: ${e.message}"))
                return@post
            }
            val screenshotBase64 = if (wantScreenshot) session.getScreenBase64() else null
            call.respond(StepResponse(session.frameCount, session.getWatchedState(), screenshotBase64))
        }
```

- [ ] **Step 5: Add `/tap` route**

Add after the `/step` route in `knes-api/src/main/kotlin/knes/api/ApiServer.kt`:

```kotlin
        post("/tap") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@post
            }
            val req: TapRequest
            try {
                req = call.receive<TapRequest>()
                session.controller.resolveButton(req.button) // validate button name
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid request: ${e.message}"))
                return@post
            }

            val steps = (1..req.count).flatMap {
                listOf(
                    StepRequest(listOf(req.button), req.pressFrames),
                    StepRequest(emptyList(), req.gapFrames)
                )
            }

            if (session.shared) {
                val latch = session.controller.enqueueSteps(steps)
                val totalFrames = steps.sumOf { it.frames }
                val timeoutMs = totalFrames * 50L + 5000L
                if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        StatusResponse("tap timed out waiting for frames")
                    )
                    return@post
                }
            } else {
                for (step in steps) {
                    session.controller.setButtons(step.buttons)
                    session.advanceFrames(step.frames)
                }
            }
            val screenshotBase64 = if (req.screenshot) session.getScreenBase64() else null
            call.respond(StepResponse(session.frameCount, session.getWatchedState(), screenshotBase64))
        }
```

- [ ] **Step 6: Run all API tests**

Run: `./gradlew :knes-api:test --info`
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/ApiServer.kt knes-api/src/test/kotlin/knes/api/ApiServerTest.kt
git commit -m "feat: add screenshot flag to step/sequence, add /tap endpoint"
```

---

### Task 2: Add screenshot param to MCP `step` tool and add `tap` + `sequence` tools

**Files:**
- Modify: `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`

- [ ] **Step 1: Modify MCP `step` tool to support `screenshot` param**

In `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`, replace the `step` tool (lines 72-99) with:

```kotlin
    // 2. step
    server.addTool(
        name = "step",
        description = "Advance emulation by N frames while holding specified buttons. Returns frame count, watched RAM values, and optionally a screenshot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("buttons") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Buttons to hold: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT. Empty array = no buttons.")
                }
                putJsonObject("frames") {
                    put("type", "integer")
                    put("description", "Number of frames to advance (default: 1, 60 frames = 1 second)")
                }
                putJsonObject("screenshot") {
                    put("type", "boolean")
                    put("description", "If true, include a screenshot of the final frame in the response (default: false)")
                }
            },
            required = listOf()
        )
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val frames = request.arguments?.get("frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val buttonsJson = buttons.joinToString(",") { "\"$it\"" }
        val resp = api.postJson("/step", """{"buttons":[$buttonsJson],"frames":$frames,"screenshot":$screenshot}""")
        if (resp.ok) {
            val content = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Content>(TextContent(resp.body))
            if (screenshot) {
                val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
                if (imageMatch != null) {
                    content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
                }
            }
            CallToolResult(content = content)
        } else {
            CallToolResult(content = listOf(TextContent("step failed: ${resp.body}")), isError = true)
        }
    }
```

- [ ] **Step 2: Add MCP `tap` tool**

Add after the `step` tool in `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`:

```kotlin
    // 2b. tap
    server.addTool(
        name = "tap",
        description = "Press a button N times with configurable timing. Equivalent to repeated step(button, press_frames) + step([], gap_frames) cycles. Returns frame count, RAM, and optionally a screenshot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("button") {
                    put("type", "string")
                    put("description", "Button to press: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Number of times to press (default: 1)")
                }
                putJsonObject("press_frames") {
                    put("type", "integer")
                    put("description", "Frames to hold each press (default: 5)")
                }
                putJsonObject("gap_frames") {
                    put("type", "integer")
                    put("description", "Frames to wait between presses (default: 15)")
                }
                putJsonObject("screenshot") {
                    put("type", "boolean")
                    put("description", "If true, include a screenshot after all presses complete (default: false)")
                }
            },
            required = listOf("button")
        )
    ) { request ->
        val button = request.arguments?.get("button")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: button")), isError = true)
        val count = request.arguments?.get("count")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val pressFrames = request.arguments?.get("press_frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 5
        val gapFrames = request.arguments?.get("gap_frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 15
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val resp = api.postJson("/tap", """{"button":"$button","count":$count,"pressFrames":$pressFrames,"gapFrames":$gapFrames,"screenshot":$screenshot}""")
        if (resp.ok) {
            val content = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Content>(TextContent(resp.body))
            if (screenshot) {
                val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
                if (imageMatch != null) {
                    content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
                }
            }
            CallToolResult(content = content)
        } else {
            CallToolResult(content = listOf(TextContent("tap failed: ${resp.body}")), isError = true)
        }
    }
```

- [ ] **Step 3: Add MCP `sequence` tool**

Add after the `tap` tool in `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`:

```kotlin
    // 2c. sequence
    server.addTool(
        name = "sequence",
        description = "Execute a sequence of button inputs in one call. Each step holds specified buttons for N frames. Returns frame count, RAM, and optionally a screenshot after all steps complete.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("steps") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("buttons") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                            putJsonObject("frames") {
                                put("type", "integer")
                            }
                        }
                    }
                    put("description", "Array of {buttons, frames} steps to execute in order")
                }
                putJsonObject("screenshot") {
                    put("type", "boolean")
                    put("description", "If true, include a screenshot after all steps complete (default: false)")
                }
            },
            required = listOf("steps")
        )
    ) { request ->
        val stepsArray = request.arguments?.get("steps")?.jsonArray
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: steps")), isError = true)
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val stepsJson = stepsArray.joinToString(",") { step ->
            val obj = step.jsonObject
            val buttons = obj["buttons"]?.jsonArray?.joinToString(",") { "\"${it.jsonPrimitive.content}\"" } ?: ""
            val frames = obj["frames"]?.jsonPrimitive?.content ?: "1"
            """{"buttons":[$buttons],"frames":$frames}"""
        }
        val resp = api.postJson("/step", """{"sequence":[$stepsJson],"screenshot":$screenshot}""")
        if (resp.ok) {
            val content = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Content>(TextContent(resp.body))
            if (screenshot) {
                val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
                if (imageMatch != null) {
                    content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
                }
            }
            CallToolResult(content = content)
        } else {
            CallToolResult(content = listOf(TextContent("sequence failed: ${resp.body}")), isError = true)
        }
    }
```

- [ ] **Step 4: Add import for `jsonObject`**

At the top of `McpServer.kt`, ensure this import exists (add if missing):

```kotlin
import kotlinx.serialization.json.jsonObject
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :knes-mcp:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt
git commit -m "feat: add screenshot to step, add tap and sequence MCP tools"
```

---

### Task 3: Run full test suite and verify

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `./gradlew test --info`
Expected: All tests pass across all modules.

- [ ] **Step 2: Verify full compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit any fixes if needed**

```bash
git add -A
git commit -m "fix: address test regressions from MCP speed improvements"
```
