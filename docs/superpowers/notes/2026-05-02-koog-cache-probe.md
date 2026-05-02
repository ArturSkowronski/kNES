# Koog 0.5.1 Prompt-Cache Surface Probe

**Date:** 2026-05-02  
**Task:** Task 1.1 - Probe Koog 0.5.1 cache-control surface and document findings

## Inspection Results

### Step 1: Anthropic Client JAR Analysis

**JAR Path:**  
`/Users/askowronski/.gradle/caches/modules-2/files-2.1/ai.koog/prompt-executor-anthropic-client-jvm/0.5.1/156fb9d218b719769f364356cc75c1b9f03e9619/prompt-executor-anthropic-client-jvm-0.5.1.jar`

**Command Run:**
```bash
javap -p -classpath "$JAR" ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
```

**AnthropicClientSettings Class Members:**
- `modelVersionsMap: Map<LLModel, String>`
- `baseUrl: String`
- `apiVersion: String`
- `timeoutConfig: ConnectionTimeoutConfig`

**Finding:** No cache-control fields. Settings are limited to model versions, base URL, API version, and timeout config.

**AnthropicLLMClient.execute() Method Signature:**
```
public java.lang.Object execute(
  ai.koog.prompt.dsl.Prompt,
  ai.koog.prompt.llm.LLModel,
  java.util.List<ai.koog.agents.core.tools.ToolDescriptor>,
  kotlin.coroutines.Continuation<? super java.util.List<? extends ai.koog.prompt.message.Message$Response>>
)
```

**Finding:** No cache-control parameter. Request body is built from `Prompt` alone.

**AnthropicMessageRequest Class Members:**
- `model: String`
- `messages: List<AnthropicMessage>`
- `maxTokens: Int`
- `temperature: Double?`
- `system: List<SystemAnthropicMessage>`
- `tools: List<AnthropicTool>`
- `stream: Boolean`
- `toolChoice: AnthropicToolChoice`
- `additionalProperties: Map<String, JsonElement>` (extension point only)

**Finding:** No dedicated cache-control field. Custom fields could only be added via `additionalProperties` map, but the client's `createAnthropicRequest` method does not expose this mechanism in the public API.

### Step 2: Prompt DSL Analysis

**JAR Path:**  
`/Users/askowronski/.gradle/caches/modules-2/files-2.1/ai.koog/prompt-model-jvm/0.5.1/86b326e88fbea2ebb489e73fb879dad5a92be181/prompt-model-jvm-0.5.1.jar`

**Prompt Class Members:**
- `messages: List<Message>`
- `id: String`
- `params: LLMParams`

**PromptBuilder DSL Methods:**
- `system(String)`
- `system(Function1<TextContentBuilder, Unit>)`
- `user(...)`
- `assistant(...)`
- `message(Message)`
- `messages(List<Message>)`
- `tool(...)`

**Finding:** No cache-related DSL methods (no `cached`, `markCacheBoundary`, `cacheControl`, etc.).

**LLMParams Class Members:**
- `temperature: Double?`
- `maxTokens: Int?`
- `numberOfChoices: Int?`
- `speculation: String?`
- `schema: Schema?`
- `toolChoice: ToolChoice?`
- `user: String?`
- `includeThoughts: Boolean?`
- `thinkingBudget: Int?`
- `additionalProperties: Map<String, JsonElement>` (extension point only)

**Finding:** No cache-control fields. The `additionalProperties` map is serialized, but the prompt DSL builder provides no way to populate it for cache markers.

**PromptDSLKt Functions:**
- `prompt(String, LLMParams, Clock, Function1<PromptBuilder, Unit>): Prompt`
- `emptyPrompt(): Prompt`

**Finding:** No cache-related builder methods.

### Step 3: Comprehensive JAR Search

Searched all ai.koog artifacts in gradle cache for cache-related classes. Results:
- No `CacheControl` classes found
- No `PromptCache` marker classes found
- No cache boundary builder methods in the DSL
- No cache-control header helpers in the Anthropic client

## Decision

**Path:** **B – Fall back to direct `Anthropic-Beta: prompt-caching-2024-07-31` headers via custom HttpClient**

**Reasoning:**

Koog 0.5.1's Anthropic client has no dedicated prompt-caching surface. The following are all absent:
1. No cache-control fields in `AnthropicClientSettings`
2. No cache parameters in `AnthropicLLMClient.execute()`
3. No cache-control builder in the Prompt DSL
4. No cache markers in `LLMParams`
5. No extension methods or builders to inject cache control headers

While `AnthropicMessageRequest` has an `additionalProperties` map that could theoretically carry cache headers, it is not exposed in the public client API. Koog's design assumes direct, unadulterated message passing without LLM-specific extensions.

**Implementation approach (Task 1.4):**
- Create a `PromptCacheConfig` stub that does not interact with Koog
- Manage `Anthropic-Beta: prompt-caching-2024-07-31` headers via a custom `HttpClient` interceptor or wrapper around `AnthropicLLMClient`
- Do not attempt to use Koog's wrappers for cache control; they don't exist

## Tools Used

- `javap` (available at `/Users/askowronski/.sdkman/candidates/java/25-tem/bin/javap`) ✓
- `unzip` for JAR inspection ✓

## Appendix: JAR Coordinates

| Artifact | Version | JAR Hash |
|----------|---------|----------|
| prompt-executor-anthropic-client-jvm | 0.5.1 | 156fb9d218b719769f364356cc75c1b9f03e9619 |
| prompt-model-jvm | 0.5.1 | 86b326e88fbea2ebb489e73fb879dad5a92be181 |

