package knes.agent.llm

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
 * Default: `gemini-3-pro` (latest as of 2026-05). Override via `GEMINI_MODEL` env;
 * older runs that used `gemini-2.5-pro` still work via the override.
 */
class GeminiPro31Client(
    private val apiKey: String,
    modelOverride: String? = null,
) : AutoCloseable {
    // gemini-3-pro thinking mode often takes 20–60s. Default ktor timeout is far
    // too tight. Match v1 GeminiVisionConsult's 120s budget (we bump higher because v2
    // Advisor prompts include campaign history and can be longer).
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    val model = modelOverride
        ?: System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() }
        ?: "gemini-3.1-pro-preview"

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
        // Two-tier retry: same model with exponential backoff (5s, 15s, 30s)
        // for transient 503/UNAVAILABLE; on persistent failure fall back to
        // gemini-3.1-flash-lite once. Gemini Pro is regularly overloaded
        // during peak hours — a hard crash on the first 503 kills 100+ turns
        // of state. Better to take a slow/degraded turn than to die.
        val backoffsMs = longArrayOf(0L, 5_000L, 15_000L, 30_000L)
        var lastResp: String? = null
        for ((i, wait) in backoffsMs.withIndex()) {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            val attemptUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val resp = try {
                http.post(attemptUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.bodyAsText()
            } catch (e: Throwable) {
                knes.agent.runtime.Log.llm("attempt ${i+1}/${backoffsMs.size} ($model) threw: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                lastResp = "{\"error\":{\"transport\":\"${e.javaClass.simpleName}\"}}"
                continue
            }
            lastResp = resp
            val text = extractText(resp)
            if (text != null) return text
            val status = extractErrorStatus(resp)
            if (status == "UNAVAILABLE" || status == "RESOURCE_EXHAUSTED" || status == "INTERNAL") {
                knes.agent.runtime.Log.llm("attempt ${i+1}/${backoffsMs.size} ($model) returned $status — retrying after ${backoffsMs.getOrNull(i+1) ?: 0}ms")
                continue
            }
            // Non-retryable error — break and let caller see it.
            break
        }
        // All same-model retries exhausted. Try Flash-Lite once if we
        // weren't already on it.
        if (!model.contains("flash", ignoreCase = true)) {
            val fallbackModel = "gemini-3.1-flash-lite"
            knes.agent.runtime.Log.warn("Gemini $model still failing; falling back to $fallbackModel for this call")
            val fbUrl = "https://generativelanguage.googleapis.com/v1beta/models/$fallbackModel:generateContent?key=$apiKey"
            val fbResp = try {
                http.post(fbUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.bodyAsText()
            } catch (e: Throwable) {
                throw RuntimeException("Gemini fallback ($fallbackModel) transport error: ${e.javaClass.simpleName}: ${e.message}")
            }
            val fbText = extractText(fbResp)
            if (fbText != null) return fbText
            throw RuntimeException("Gemini fallback ($fallbackModel) unparseable: ${fbResp.take(500)}")
        }
        throw RuntimeException("Gemini response unparseable after retries: ${lastResp?.take(500)}")
    }

    private fun extractText(resp: String): String? {
        val parsed = try { json.parseToJsonElement(resp).jsonObject } catch (_: Throwable) { return null }
        val candidates = parsed["candidates"]?.jsonArray ?: return null
        return candidates.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
    }

    private fun extractErrorStatus(resp: String): String? {
        val parsed = try { json.parseToJsonElement(resp).jsonObject } catch (_: Throwable) { return null }
        return parsed["error"]?.jsonObject?.get("status")?.jsonPrimitive?.content
    }

    override fun close() { http.close() }
}
