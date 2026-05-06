package knes.agent.explorer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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
 * Alternative HaikuConsult backed by Google Gemini 2.5 Pro (text + image).
 *
 * Empirical comparison vs Anthropic Haiku 4.5 (2026-05-06):
 *  - On UnknownMapTrap mapId=0 void state, Pro returns `{"landmarks": []}` correctly;
 *    Haiku hallucinates 4 landmarks (NPC_KING + 2× STAIRS_DOWN false positives).
 *  - On real Coneria Castle throne (mapId=24), Pro identifies KING + describes the
 *    flanking dragon emblems; Haiku also catches KING but hallucinates a STAIRS_DOWN.
 *  - Cost: Pro ~6× more expensive per call ($0.005-0.007 vs $0.001) but the precision
 *    gain ($0.30 vs $0.05 over a 50-run campaign with ~50% Haiku false positives)
 *    is worth it.
 *
 * Selected via `KNES_VISION` env var (`gemini-pro` | `haiku`); default haiku.
 */
class GeminiVisionConsult(
    private val apiKey: String,
    private val model: String = "gemini-2.5-pro",
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    },
) : HaikuConsult, AutoCloseable {

    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotBase64: String?, runId: String,
    ): HaikuConsult.InteriorClassification {
        val body = buildBody(SYSTEM_CLASSIFY, classifyUserText(mapId, visitedTileCount), screenshotBase64,
            maxOutputTokens = 2000)
        val raw = postOrNull(body) ?: return HaikuConsult.InteriorClassification(emptyList(), 0.0)
        return parseInteriorResponse(raw, mapId = mapId, runId = runId)
    }

    override suspend fun readDialog(screenshotBase64: String?): HaikuConsult.DialogReading {
        val body = buildBody(SYSTEM_DIALOG, DIALOG_USER_TEXT, screenshotBase64, maxOutputTokens = 800)
        val raw = postOrNull(body) ?: return HaikuConsult.DialogReading("", null, 0.0)
        return parseDialogResponse(raw)
    }

    override suspend fun classifyShopMenu(screenshotBase64: String?): HaikuConsult.ShopClassification {
        val body = buildBody(SYSTEM_SHOP, SHOP_USER_TEXT, screenshotBase64, maxOutputTokens = 800)
        val raw = postOrNull(body) ?: return HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        return parseShopResponse(raw)
    }

    override fun close() { client.close() }

    private suspend fun postOrNull(body: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val resp = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            System.err.println("[gemini-vision] request failed: ${e.message}")
            return null
        }
        if (!resp.status.isSuccess()) {
            System.err.println("[gemini-vision] http ${resp.status.value}: ${resp.bodyAsText().take(200)}")
            return null
        }
        return resp.bodyAsText()
    }

    private fun classifyUserText(mapId: Int, visitedTileCount: Int): String =
        "Map id: $mapId. Visited tiles in this interior: $visitedTileCount. " +
            "Identify any NPCs visible on screen and stairs. Output JSON only:\n" +
            """{"landmarks":[{"kind":"NPC_KING|NPC_SHOPKEEPER|NPC_GENERIC|STAIRS_UP|STAIRS_DOWN","note":"<short>"}]}"""

    private fun buildBody(systemPrompt: String, userText: String, b64: String?, maxOutputTokens: Int): String {
        val obj = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        if (b64 != null) {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "image/png")
                                    put("data", b64)
                                })
                            })
                        }
                        add(buildJsonObject { put("text", userText) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", maxOutputTokens)
                put("temperature", 0)
            })
        }
        return obj.toString()
    }

    companion object {
        // Gemini 2.5 Pro pricing (Google, late 2025): $1.25 / MTok input, $10 / MTok
        // output. thoughtsTokenCount is billed as output (reasoning tokens). Costs
        // here are upper-bound; if pricing changes the absolute number drifts but
        // the budget cap still triggers correctly.
        private const val INPUT_USD_PER_TOKEN = 1.25e-6
        private const val OUTPUT_USD_PER_TOKEN = 10.0e-6

        private val JSON_OBJECT = Regex("""\{[\s\S]*\}""")
        private val json = Json { ignoreUnknownKeys = true }

        private const val SYSTEM_CLASSIFY =
            "You analyze a screenshot of a Final Fantasy 1 (NES) interior — castle, town, " +
                "or dungeon room. Identify visible NPCs and staircases. " +
                "NPC_KING: a crowned figure on a throne (large royal room). " +
                "NPC_SHOPKEEPER: a counter with item icons or weapons displayed. " +
                "NPC_GENERIC: any other person sprite. " +
                "STAIRS_UP / STAIRS_DOWN: ascending / descending staircase tile. " +
                "Return ONLY JSON, no prose. If nothing matches the categories, return an empty list."

        private const val SYSTEM_DIALOG =
            "You read short on-screen dialog text from a Final Fantasy 1 (NES) screenshot. " +
                "Dialog appears as a white-bordered box near the bottom of the screen with white " +
                "text on dark background. Paraphrase concisely. If the speaker is identified by " +
                "context (king on throne / shopkeeper at counter), set landmarkHint to KING / " +
                "SHOPKEEPER. Otherwise set landmarkHint to null. Return ONLY JSON, no prose."

        private const val DIALOG_USER_TEXT =
            "Read the dialog text in this FF1 NES screenshot (white text on black box). " +
                "Output JSON only:\n" +
                """{"summary":"<paraphrase ≤80 chars>","landmarkHint":"KING|SHOPKEEPER|null"}"""

        private const val SYSTEM_SHOP =
            "You are reading the BUY menu screen of a Final Fantasy 1 (NES) shop. " +
                "Classify the shop kind and list each item name + price. " +
                """Output JSON only: {"kind":"weapon|armor|whiteMagic|blackMagic|item|unknown","items":[{"name":"<short>","price":N}]}""" +
                " Rules: " +
                "weapon = physical weapons (sword, axe, dagger, hammer, nunchucks, staff). " +
                "armor = body equipment (cloth, leather, mail, shield, helm, gauntlets). " +
                "whiteMagic = spells like CURE / HARM / FOG / RUSE. " +
                "blackMagic = spells like FIRE / LIT / SLEP / LOCK. " +
                "item = potions, antidote, tents, cabins. " +
                "unknown = cannot classify with confidence. " +
                "Return ONLY JSON, no prose."

        private const val SHOP_USER_TEXT = "Classify this shop BUY menu."

        /** Parse the Gemini response envelope and the inner LLM-emitted JSON.
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
                    val kind = runCatching { LandmarkKind.valueOf(kindStr) }.getOrNull() ?: return@mapNotNull null
                    val note = o["note"]?.jsonPrimitive?.content ?: ""
                    Landmark(
                        id = "gemini_${kindStr.lowercase()}_${mapId}_${note.hashCode().toString(16)}",
                        kind = kind, mapId = mapId,
                        note = note, visited = false, discoveredRunId = runId,
                    )
                }
            } catch (e: Exception) {
                System.err.println("[gemini-vision] parseInterior failed: ${e.message}")
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
                System.err.println("[gemini-vision] parseDialog failed: ${e.message}")
                HaikuConsult.DialogReading("", null, costUsd)
            }
        }

        fun parseShopResponse(rawJson: String): HaikuConsult.ShopClassification {
            val (innerText, costUsd) = parseEnvelope(rawJson)
            if (innerText == null) return HaikuConsult.ShopClassification("unknown", emptyList(), costUsd)
            return try {
                val match = JSON_OBJECT.find(innerText)
                    ?: return HaikuConsult.ShopClassification("unknown", emptyList(), costUsd)
                val obj = json.parseToJsonElement(match.value).jsonObject
                val kind = obj["kind"]?.jsonPrimitive?.content ?: "unknown"
                val items = obj["items"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    // price may be emitted as int or string — accept both.
                    val price = o["price"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                    name to price
                } ?: emptyList()
                HaikuConsult.ShopClassification(kind, items, costUsd)
            } catch (e: Exception) {
                System.err.println("[gemini-vision] parseShop failed: ${e.message}")
                HaikuConsult.ShopClassification("unknown", emptyList(), costUsd)
            }
        }

        /** Returns (innerText, costUsd). innerText is null when the envelope is malformed.
         *  Gemini's `usageMetadata` exposes promptTokenCount, candidatesTokenCount,
         *  and thoughtsTokenCount. The latter is billed as output. */
        private fun parseEnvelope(rawJson: String): Pair<String?, Double> {
            return try {
                val root = json.parseToJsonElement(rawJson).jsonObject
                val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                val text = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                    ?.joinToString("")
                val usage = root["usageMetadata"]?.jsonObject
                val inTok = usage?.get("promptTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val outTok = usage?.get("candidatesTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val thoughtTok = usage?.get("thoughtsTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val cost = inTok * INPUT_USD_PER_TOKEN + (outTok + thoughtTok) * OUTPUT_USD_PER_TOKEN
                (if (text.isNullOrBlank()) null else text) to cost
            } catch (e: Exception) {
                System.err.println("[gemini-vision] envelope parse failed: ${e.message}")
                null to 0.0
            }
        }
    }
}
