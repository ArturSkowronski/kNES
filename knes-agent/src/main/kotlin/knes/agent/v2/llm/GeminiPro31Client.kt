package knes.agent.v2.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal Gemini client for v2 agents. Sends a text+image prompt, returns model text.
 * Reuses HTTP wiring style from v1 GeminiVisionConsult.
 *
 * NOTE: spec called for "Gemini 3.1 Pro" (from the PDF talk); that ID is not exposed
 * by generativelanguage v1beta as of 2026-05. Falls back to `gemini-2.5-pro` (same
 * model v1 uses successfully) until 3.1 Pro lands. Override via `GEMINI_MODEL` env.
 */
class GeminiPro31Client(private val apiKey: String) : AutoCloseable {
    // gemini-2.5-pro in thinking mode often takes 20–60s. Default ktor timeout is far
    // too tight. Match v1 GeminiVisionConsult's 120s budget (we bump higher because v2
    // Advisor prompts include campaign history and can be longer).
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val model = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: "gemini-2.5-pro"

    suspend fun generate(prompt: String, imageB64: String? = null): String {
        val parts = buildList<JsonObject> {
            add(JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(prompt))))
            if (imageB64 != null) {
                add(JsonObject(mapOf("inline_data" to JsonObject(mapOf(
                    "mime_type" to kotlinx.serialization.json.JsonPrimitive("image/png"),
                    "data" to kotlinx.serialization.json.JsonPrimitive(imageB64),
                )))))
            }
        }
        val body = buildString {
            append("""{"contents":[{"parts":""")
            append(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(JsonObject.serializer()), parts))
            append("}]}")
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val resp = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        val candidates = json.parseToJsonElement(resp).jsonObject["candidates"]?.jsonArray
        return candidates?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw RuntimeException("Gemini response unparseable: ${resp.take(500)}")
    }

    override fun close() { http.close() }
}
