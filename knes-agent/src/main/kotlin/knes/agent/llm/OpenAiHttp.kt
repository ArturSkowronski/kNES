package knes.agent.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI chat-completions client, vision included.
 *
 * Two things differ from the Anthropic shape and both bite if ignored:
 *
 * - the token budget is `max_completion_tokens`, and it covers **reasoning tokens too**.
 *   A budget sized for the answer alone comes back empty with `finish_reason=length`, so
 *   [MIN_TOKEN_BUDGET] floors it and an exhausted budget is reported rather than returned
 *   as an empty string.
 * - `reasoning_effort` decides how much of that budget thinking consumes. `low` answers
 *   this project's prompts correctly while spending none of it, which matters across a
 *   200-turn run.
 */
class OpenAiHttp(
    private val apiKey: String,
    override val fastModel: String = System.getenv("OPENAI_FAST_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_FAST_MODEL,
    override val strongModel: String = System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_STRONG_MODEL,
    private val reasoningEffort: String = System.getenv("OPENAI_REASONING_EFFORT")?.takeIf { it.isNotBlank() } ?: "low",
) : ChatLlm {

    override val providerName: String get() = "openai"

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(
        model: String,
        systemPrompt: String,
        userText: String,
        imageB64: String?,
        maxTokens: Int,
    ): String {
        val body = requestBody(model, systemPrompt, userText, imageB64, maxTokens, reasoningEffort)

        // Same two-tier patience as the Gemini client: a rate limit or a 5xx during a
        // long run should cost a slow turn, not the whole campaign.
        val backoffsMs = longArrayOf(0L, 5_000L, 15_000L, 30_000L)
        var lastResponse: String? = null
        for ((attempt, wait) in backoffsMs.withIndex()) {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            val response = try {
                http.post(ENDPOINT) {
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
                }.bodyAsText()
            } catch (e: Throwable) {
                knes.agent.runtime.Log.llm(
                    "openai attempt ${attempt + 1}/${backoffsMs.size} ($model) threw: " +
                        "${e.javaClass.simpleName}: ${e.message?.take(120)}"
                )
                lastResponse = """{"error":{"transport":"${e.javaClass.simpleName}"}}"""
                continue
            }
            lastResponse = response

            extractText(response)?.let { return it }

            val finish = extractFinishReason(response)
            if (finish == "length") {
                throw RuntimeException(
                    "OpenAI $model returned no text: the ${maxOf(maxTokens, MIN_TOKEN_BUDGET)}-token " +
                        "budget was spent on reasoning. Raise maxTokens or lower OPENAI_REASONING_EFFORT."
                )
            }
            if (isRetryable(response)) {
                knes.agent.runtime.Log.llm(
                    "openai attempt ${attempt + 1}/${backoffsMs.size} ($model) retryable — " +
                        "waiting ${backoffsMs.getOrNull(attempt + 1) ?: 0}ms"
                )
                continue
            }
            break
        }
        throw RuntimeException("OpenAI response unparseable after retries: ${lastResponse?.take(500)}")
    }

    private fun extractText(response: String): String? {
        val parsed = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return null
        val content = parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
        return content?.takeIf { it.isNotBlank() }
    }

    private fun extractFinishReason(response: String): String? {
        val parsed = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return null
        return parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("finish_reason")?.jsonPrimitive?.contentOrNull
    }

    private fun isRetryable(response: String): Boolean {
        val parsed = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return false
        val error = parsed["error"]?.jsonObject ?: return false
        val type = error["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val code = error["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return "rate_limit" in type || "rate_limit" in code ||
            "server_error" in type || "overloaded" in code || "transport" in error.keys
    }

    override fun close() { http.close() }

    companion object {
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"

        /**
         * The request as OpenAI wants it, separated from sending so its shape can be
         * asserted without a network call.
         */
        fun requestBody(
            model: String,
            systemPrompt: String,
            userText: String,
            imageB64: String?,
            maxTokens: Int,
            reasoningEffort: String,
        ): kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("model", model)
            // Not `max_tokens`: this budget also covers reasoning tokens.
            put("max_completion_tokens", maxOf(maxTokens, MIN_TOKEN_BUDGET))
            if (model.startsWith("gpt-5") || model.startsWith("o")) {
                put("reasoning_effort", reasoningEffort)
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", userText)
                        }
                        if (imageB64 != null) {
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/png;base64,$imageB64")
                                }
                            }
                        }
                    }
                }
            }
        }

        /** The strong model for decisions that matter. */
        const val DEFAULT_STRONG_MODEL = "gpt-5"

        /**
         * Also `gpt-5`, not `gpt-5-mini`.
         *
         * mini answered "blue" for a solid red image during wiring, and this project has
         * already lost a smoke run to a cheap model misreading the screen — Flash-Lite
         * put the party "near the Inn" while it stood on the centre path. Override with
         * `OPENAI_FAST_MODEL` if the cost matters more than the reading.
         */
        const val DEFAULT_FAST_MODEL = "gpt-5"

        /**
         * Reasoning tokens come out of the same budget as the answer, so a small ceiling
         * yields an empty message rather than a short one.
         */
        const val MIN_TOKEN_BUDGET = 3_000
    }
}

/** The single-prompt vision shape, over the same OpenAI connection. */
class OpenAiVisionClient(
    private val chat: OpenAiHttp,
    modelOverride: String? = null,
) : VisionLlm {
    override val model: String = modelOverride ?: chat.strongModel

    override suspend fun generate(prompt: String, imageB64: String?): String =
        chat.generate(
            model = model,
            systemPrompt = "You are driving an NES emulator. Answer exactly in the format the prompt asks for.",
            userText = prompt,
            imageB64 = imageB64,
            maxTokens = 4_000,
        )

    /** The connection belongs to whoever built [chat]; closing it here would pull it from under them. */
    override fun close() = Unit
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()
