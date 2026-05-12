package knes.agent.v2.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal vision-capable Anthropic Messages API wrapper for v2 agents.
 *
 * Mirrors GeminiPro31Client's HTTP style. Reused by HaikuClient (scene
 * description from screenshot) and SonnetClient (tool decision, text-only).
 */
class AnthropicHttp(private val apiKey: String) : AutoCloseable {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(
        model: String,
        systemPrompt: String,
        userText: String,
        imageB64: String? = null,
        maxTokens: Int = 800,
    ): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (imageB64 != null) {
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", "image/png")
                                    put("data", imageB64)
                                }
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", userText)
                        }
                    }
                }
            }
        }.toString()

        val resp = http.post(API_URL) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("Anthropic http ${resp.status.value}: ${resp.bodyAsText().take(400)}")
        }
        val text = resp.bodyAsText()
        val content = json.parseToJsonElement(text).jsonObject["content"]?.jsonArray
            ?: throw RuntimeException("Anthropic response missing content: ${text.take(400)}")
        return content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: throw RuntimeException("Anthropic response no text block: ${text.take(400)}")
    }

    override fun close() { http.close() }

    companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
    }
}

private inline fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) { add(buildJsonObject(block)) }
