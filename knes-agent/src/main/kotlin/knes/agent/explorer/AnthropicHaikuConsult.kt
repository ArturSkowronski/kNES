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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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

    /** Stub: overworld landmark classification is delegated to Gemini in this codebase. */
    override suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): HaikuConsult.OverworldClassification =
        HaikuConsult.OverworldClassification.NotFound(0.0)

    override suspend fun scanInteriorCandidates(
        screenshotBase64: String?,
    ): HaikuConsult.CandidatesScan {
        if (screenshotBase64.isNullOrEmpty()) return HaikuConsult.CandidatesScan(emptyList(), 0.0)
        return try {
            val body = buildBody(
                systemPrompt = SYSTEM_INTERIOR_SCAN,
                userText = "Identify visible landmarks.",
                b64 = screenshotBase64,
                maxTokens = 1500,
            )
            val raw = postOrNull(body) ?: return HaikuConsult.CandidatesScan(emptyList(), 0.0)
            val (innerText, costUsd) = parseEnvelope(raw)
            if (innerText == null) return HaikuConsult.CandidatesScan(emptyList(), costUsd)
            HaikuConsult.CandidatesScan(parsePass1(innerText), costUsd)
        } catch (e: Exception) {
            System.err.println("[haiku-consult] scanInteriorCandidates failed: ${e.message}")
            HaikuConsult.CandidatesScan(emptyList(), 0.0)
        }
    }

    override suspend fun verifyLandmark(
        focusedScreenshotBase64: String?,
        candidateKind: String,
        candidateScreenX: Int,
        candidateScreenY: Int,
    ): HaikuConsult.VerifyResult {
        return HaikuConsult.VerifyResult.Errored("stub-not-implemented", 0.0)
    }

    /** Spec 5: Opus 4.5 advisor for one-step interior navigation. */
    override suspend fun adviseShopApproach(
        screenshotBase64: String?,
        contextText: String,
    ): HaikuConsult.AdviceResponse {
        if (screenshotBase64.isNullOrEmpty()) {
            return HaikuConsult.AdviceResponse("Fail", "no-screenshot", 0.0)
        }
        return try {
            // Build body with Opus model override (bypassing default Haiku model).
            val body = buildBodyWithModel(
                modelOverride = ADVISOR_MODEL,
                systemPrompt = SYSTEM_ADVISOR,
                userText = contextText,
                b64 = screenshotBase64,
                maxTokens = 800,
            )
            val raw = postOrNull(body) ?: return HaikuConsult.AdviceResponse("Fail", "api-error", 0.0)
            parseAdvice(raw)
        } catch (e: Throwable) {
            HaikuConsult.AdviceResponse("Fail", "exception: ${e.message}", 0.0)
        }
    }

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

    private fun buildBody(systemPrompt: String, userText: String, b64: String?, maxTokens: Int): String =
        buildBodyWithModel(model, systemPrompt, userText, b64, maxTokens)

    private fun buildBodyWithModel(
        modelOverride: String,
        systemPrompt: String,
        userText: String,
        b64: String?,
        maxTokens: Int,
    ): String {
        val obj = buildJsonObject {
            put("model", modelOverride)
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

    private fun parseAdvice(raw: String): HaikuConsult.AdviceResponse {
        return try {
            val match = JSON_OBJECT.find(raw)?.value
                ?: return HaikuConsult.AdviceResponse("Fail", "envelope-malformed", 0.0)
            val envelope = json.parseToJsonElement(match).jsonObject
            // Extract usage cost (Opus 4.5 pricing: $15/MTok in, $75/MTok out)
            val usage = envelope["usage"]?.jsonObject
            val inTok = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val outTok = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val cost = inTok * 15.0e-6 + outTok * 75.0e-6
            // Extract content[0].text
            val content = envelope["content"]?.jsonArray?.firstOrNull()?.jsonObject
            val text = content?.get("text")?.jsonPrimitive?.contentOrNull
                ?: return HaikuConsult.AdviceResponse("Fail", "no-content", cost)
            // Find inner JSON in advice response
            val innerMatch = JSON_OBJECT.find(text)?.value
                ?: return HaikuConsult.AdviceResponse("Fail", "advice-not-json: ${text.take(80)}", cost)
            val advice = json.parseToJsonElement(innerMatch).jsonObject
            val action = advice["action"]?.jsonPrimitive?.contentOrNull ?: "Fail"
            val reason = advice["reason"]?.jsonPrimitive?.contentOrNull ?: "no-reason"
            HaikuConsult.AdviceResponse(action, reason, cost)
        } catch (e: Throwable) {
            HaikuConsult.AdviceResponse("Fail", "parse-exception: ${e.message}", 0.0)
        }
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"

        // Spec 5: Opus 4.5 used for interior nav advice (richer spatial reasoning).
        // The koog AnthropicModels enum tops out at Opus_4_5 in version 0.6.1; we
        // hardcode the dated id directly to bypass the framework's model dispatch.
        private const val ADVISOR_MODEL = "claude-opus-4-5-20251101"

        private const val SYSTEM_ADVISOR =
            """You are a navigation advisor for an autonomous Final Fantasy 1 (NES) agent inside Coneria town.

The screenshot shows the FF1 NES viewport (256x240 px, 16x15 tiles). The party renders at viewport tile (8, 7).

CONERIA TOWN MAP (mapId=8) — empirically observed coordinate layout:
- Party SPAWN: smPlayer(12, 35) — south plaza, just north of town entry
- Plaza area: smPlayerY 18-30, smPlayerX 4-22 (open floor)
- CASTLE GATE: smPlayer(~10-12, ~16-18) — TOP CENTER of plaza, leads to mapId=24 (NOT a shop! It's a long pillared corridor — AVOID).
- Building doors are on south walls. To enter, step N onto door tile.

CRITICAL — buildings to AVOID:
- CASTLE GATE at smPlayer(10-12, ~17): if party blocked moving Up at Y=18 with X near 11-12, the wall in front IS the castle entrance. DO NOT enter.

Building positions (approximate smPlayer X — verify with screenshot landmarks):
- Building 1 INN: south plaza X=10-13, Y~30
- Building 2 ARMOR shop: middle-west, X~5-7, Y~18-20
- Building 3 WEAPON shop: middle-west, X~8-9 (just east of armor), Y~18-20
- Building 4 BLACK MAGIC: top-west, X~3-5, Y~10
- Building 5 WHITE MAGIC: top, X~7-9, Y~10
- Building 7 ITEM shop: middle-east, X~22-24, Y~18-20

Strategy from spawn (12, 35):
1. Walk N until Y~21 (mid-plaza)
2. Walk W (Left) until X~8-9 (toward weapon shop)
3. Walk N — should now hit weapon shop south wall
4. Try Tap_A or step onto specific door tile (door is one-tile gap in wall)

If blocked moving Up between Y=17 and Y=18, you are at CASTLE GATE — back off (Down) and re-route West/East.

Output JSON only, no prose. Schema:
{"action":"Up|Down|Left|Right|Tap_A|Done|Fail","reason":"<short>"}

Rules:
- Up/Down/Left/Right: move party one tile in that direction
- Tap_A: try to interact with what's directly in front of party
- Done: party is already inside the weapon shop interior (mapId changed AND keeper visible)
- Fail: cannot determine path — abort
"""

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

        const val SYSTEM_INTERIOR_SCAN =
            """You are reading a Final Fantasy 1 (NES) interior screenshot. The image is
256x240 px, a 16-tile-wide x 15-tile-tall viewport (each tile 16x16 px).
The party (4 sprites overlapping into one figure) renders at tile (8, 7).

Identify ALL visible non-party landmarks. Possible kinds:
- "shopkeeper"        — NPC behind a counter, often dressed distinctively
- "king"              — NPC on a throne / royal sprite
- "innkeeper"         — NPC near a bed/inn counter
- "generic_npc"       — generic townsperson/villager (dialogue trigger)
- "stairs_up"         — stair sprite leading up
- "stairs_down"       — stair sprite leading down
- "chest"             — treasure chest (open or closed)
- "sign"              — sign or tablet
- "exit_tile"         — door/staircase clearly leading outside

Output JSON only. Schema:
{"candidates":[{"kind":"<kind>","screenX":<int 0..15>,
                "screenY":<int 0..14>,"confidence":<float 0..1>}]}

If no landmarks visible: {"candidates":[]}.

Do NOT guess. confidence ≥ 0.7 only when you can see the sprite clearly.
Return ONLY JSON."""

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

        /** Parse Pass-1 candidate scan inner text. Public for unit testing.
         *  Returns empty list on any parse failure. */
        fun parsePass1(raw: String): List<HaikuConsult.CandidateLandmark> {
            return try {
                val match = JSON_OBJECT.find(raw) ?: return emptyList()
                val obj = json.parseToJsonElement(match.value).jsonObject
                val arr = obj["candidates"]?.jsonArray ?: return emptyList()
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val sx = o["screenX"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val sy = o["screenY"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val conf = o["confidence"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    HaikuConsult.CandidateLandmark(
                        kind = kind, screenX = sx, screenY = sy, confidence = conf,
                    )
                }
            } catch (e: Exception) {
                System.err.println("[haiku-consult] parsePass1 failed: ${e.message}")
                emptyList()
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
