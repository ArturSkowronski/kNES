package knes.agent.explorer

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
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Production HaikuConsult backed by Anthropic Haiku 4.5 (text + image).
 *
 * Two prompts:
 *  - classifyInterior — given a screenshot of an FF1 interior, list any visible
 *    KING / SHOPKEEPER / GENERIC NPCs, plus stairs. Mapped to [LandmarkKind].
 *  - readDialog — given a screenshot with a dialog box, paraphrase the text
 *    and hint at landmark kind (KING/SHOPKEEPER/null).
 *
 * Without a screenshot, both calls degrade gracefully to text-only (no NPC
 * classification, but still cheap; the empty result is fine — terrain/warp/blockage
 * memory still grows from the deterministic step loop).
 *
 * Cost per call extracted from `usage.input_tokens` + `usage.output_tokens` in the
 * Anthropic response.
 */
class AnthropicHaikuConsult(
    private val apiKey: String,
    private val model: String = "claude-haiku-4-5",
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
) : HaikuConsult, AutoCloseable {

    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotBase64: String?, runId: String,
    ): HaikuConsult.InteriorClassification {
        val body = buildClassifyBody(mapId, visitedTileCount, screenshotBase64)
        val raw = postOrNull(body) ?: return HaikuConsult.InteriorClassification(emptyList(), 0.0)
        return parseInteriorResponse(raw, mapId = mapId, runId = runId)
    }

    override suspend fun readDialog(screenshotBase64: String?): HaikuConsult.DialogReading {
        val body = buildDialogBody(screenshotBase64)
        val raw = postOrNull(body) ?: return HaikuConsult.DialogReading("", null, 0.0)
        return parseDialogResponse(raw)
    }

    /** Stub: shop classification is delegated to Gemini in this codebase. */
    override suspend fun classifyShopMenu(screenshotBase64: String?): HaikuConsult.ShopClassification =
        HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)

    override fun close() { client.close() }

    private suspend fun postOrNull(body: String): String? {
        val resp = try {
            client.post(API_URL) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            System.err.println("[haiku-consult] request failed: ${e.message}")
            return null
        }
        if (!resp.status.isSuccess()) {
            System.err.println("[haiku-consult] http ${resp.status.value}: ${resp.bodyAsText().take(200)}")
            return null
        }
        return resp.bodyAsText()
    }

    private fun buildClassifyBody(mapId: Int, visitedTileCount: Int, b64: String?): String {
        val userText = "Map id: $mapId. Visited tiles in this interior: $visitedTileCount. " +
            "Identify any NPCs visible on screen and stairs. Output JSON only:\n" +
            """{"landmarks":[{"kind":"NPC_KING|NPC_SHOPKEEPER|NPC_GENERIC|STAIRS_UP|STAIRS_DOWN","note":"<short>"}]}"""
        return buildBody(systemPrompt = SYSTEM_CLASSIFY, userText = userText, b64 = b64, maxTokens = 300)
    }

    private fun buildDialogBody(b64: String?): String {
        val userText = "Read the dialog text in this FF1 NES screenshot (white text on black box). " +
            "Output JSON only:\n" +
            """{"summary":"<paraphrase ≤80 chars>","landmarkHint":"KING|SHOPKEEPER|null"}"""
        return buildBody(systemPrompt = SYSTEM_DIALOG, userText = userText, b64 = b64, maxTokens = 200)
    }

    private fun buildBody(systemPrompt: String, userText: String, b64: String?, maxTokens: Int): String {
        val obj = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (b64 != null) {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", "image/png")
                                    put("data", b64)
                                })
                            })
                        }
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userText)
                        })
                    })
                })
            })
        }
        return obj.toString()
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"

        // Haiku 4.5 pricing (Anthropic, late 2025): $1 / MTok input, $5 / MTok output.
        // If pricing changes the absolute cost number drifts but the budget cap still triggers.
        private const val INPUT_USD_PER_TOKEN = 1.0e-6
        private const val OUTPUT_USD_PER_TOKEN = 5.0e-6

        private val JSON_OBJECT = Regex("""\{[\s\S]*\}""")
        private val json = Json { ignoreUnknownKeys = true }

        private const val SYSTEM_CLASSIFY =
            "You analyze a screenshot of a Final Fantasy 1 (NES) interior — castle, town, " +
                "or dungeon room. Identify visible NPCs and staircases. " +
                "NPC_KING: a crowned figure on a throne (large royal room). " +
                "NPC_SHOPKEEPER: a counter with item icons or weapons displayed. " +
                "NPC_GENERIC: any other person sprite. " +
                "STAIRS_UP / STAIRS_DOWN: ascending / descending staircase tile. " +
                "Return ONLY JSON, no prose."

        private const val SYSTEM_DIALOG =
            "You read short on-screen dialog text from a Final Fantasy 1 (NES) screenshot. " +
                "Dialog appears as a white-bordered box near the bottom of the screen with white " +
                "text on dark background. Paraphrase concisely. If the speaker is identified by " +
                "context (king on throne / shopkeeper at counter), set landmarkHint to KING / " +
                "SHOPKEEPER. Otherwise set landmarkHint to null. Return ONLY JSON, no prose."

        /** Parse the Anthropic response envelope and the inner LLM-emitted JSON.
         *  Visible for testing — pure function, no side effects. */
        fun parseInteriorResponse(rawJson: String, mapId: Int, runId: String): HaikuConsult.InteriorClassification {
            val (innerText, costUsd) = parseEnvelope(rawJson)
            if (innerText == null) return HaikuConsult.InteriorClassification(emptyList(), costUsd)
            val landmarks = try {
                val match = JSON_OBJECT.find(innerText) ?: return HaikuConsult.InteriorClassification(emptyList(), costUsd)
                val obj = json.parseToJsonElement(match.value).jsonObject
                val arr = obj["landmarks"]?.jsonArray ?: return HaikuConsult.InteriorClassification(emptyList(), costUsd)
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val kindStr = o["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val kind = runCatching { LandmarkKind.valueOf(kindStr) }.getOrNull() ?: LandmarkKind.NPC_GENERIC
                    val note = o["note"]?.jsonPrimitive?.content ?: ""
                    Landmark(
                        id = "haiku_${kindStr.lowercase()}_${mapId}_${note.hashCode().toString(16)}",
                        kind = kind, mapId = mapId,
                        note = note, visited = false, discoveredRunId = runId,
                    )
                }
            } catch (e: Exception) {
                System.err.println("[haiku-consult] parseInterior failed: ${e.message}")
                emptyList()
            }
            return HaikuConsult.InteriorClassification(landmarks, costUsd)
        }

        fun parseDialogResponse(rawJson: String): HaikuConsult.DialogReading {
            val (innerText, costUsd) = parseEnvelope(rawJson)
            if (innerText == null) return HaikuConsult.DialogReading("", null, costUsd)
            return try {
                val match = JSON_OBJECT.find(innerText) ?: return HaikuConsult.DialogReading("", null, costUsd)
                val obj = json.parseToJsonElement(match.value).jsonObject
                val summary = obj["summary"]?.jsonPrimitive?.content ?: ""
                val rawHint = obj["landmarkHint"]?.jsonPrimitive?.content
                val hint = if (rawHint == null || rawHint.equals("null", ignoreCase = true) || rawHint.isBlank()) null else rawHint
                HaikuConsult.DialogReading(summary, hint, costUsd)
            } catch (e: Exception) {
                System.err.println("[haiku-consult] parseDialog failed: ${e.message}")
                HaikuConsult.DialogReading("", null, costUsd)
            }
        }

        /** Returns (innerText, costUsd). innerText is null when the envelope is malformed. */
        private fun parseEnvelope(rawJson: String): Pair<String?, Double> {
            return try {
                val root = json.parseToJsonElement(rawJson).jsonObject
                val text = root["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                val usage = root["usage"]?.jsonObject
                val inTok = usage?.get("input_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val outTok = usage?.get("output_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val cost = inTok * INPUT_USD_PER_TOKEN + outTok * OUTPUT_USD_PER_TOKEN
                text to cost
            } catch (e: Exception) {
                System.err.println("[haiku-consult] envelope parse failed: ${e.message}")
                null to 0.0
            }
        }
    }
}
