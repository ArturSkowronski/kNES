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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

    override suspend fun classifyShopMenuPhase(
        screenshotBase64: String?,
    ): HaikuConsult.ShopMenuPhaseClassification {
        if (screenshotBase64.isNullOrEmpty()) {
            return HaikuConsult.ShopMenuPhaseClassification(HaikuConsult.ShopMenuPhase.UNKNOWN, 0.0)
        }
        return try {
            val body = buildBody(SYSTEM_SHOP_PHASE, SHOP_PHASE_USER_TEXT, screenshotBase64,
                maxOutputTokens = 800)
            val raw = postOrNull(body)
                ?: return HaikuConsult.ShopMenuPhaseClassification(HaikuConsult.ShopMenuPhase.UNKNOWN, 0.0)
            parseShopMenuPhaseResponse(raw)
        } catch (e: Throwable) {
            System.err.println("[gemini-vision] classifyShopMenuPhase failed: ${e.message}")
            HaikuConsult.ShopMenuPhaseClassification(HaikuConsult.ShopMenuPhase.UNKNOWN, 0.0)
        }
    }

    override suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): HaikuConsult.OverworldClassification {
        // gemini-2.5-pro requires thinking mode (cannot set thinkingBudget=0).
        // Empirically, thinking consumes 400-600 tokens before producing the JSON
        // response (~10 tokens). Budget = 2000 leaves comfortable headroom.
        val body = buildBody(SYSTEM_OVERWORLD_LANDMARK, overworldUserText(kind), screenshotBase64,
            maxOutputTokens = 2000)
        val raw = postOrNull(body) ?: return HaikuConsult.OverworldClassification.NotFound(0.0)
        return parseOverworldResponse(raw)
    }

    override suspend fun scanInteriorCandidates(
        screenshotBase64: String?,
    ): HaikuConsult.CandidatesScan {
        if (screenshotBase64.isNullOrEmpty()) return HaikuConsult.CandidatesScan(emptyList(), 0.0)
        return try {
            // Gemini 2.5 Pro thinking mode mandatory → maxOutputTokens=2000.
            val body = buildBody(
                systemPrompt = SYSTEM_INTERIOR_SCAN,
                userText = "Identify visible landmarks.",
                b64 = screenshotBase64,
                maxOutputTokens = 2000,
            )
            val raw = postOrNull(body) ?: return HaikuConsult.CandidatesScan(emptyList(), 0.0)
            val (innerText, costUsd) = parseEnvelope(raw)
            if (innerText == null) return HaikuConsult.CandidatesScan(emptyList(), costUsd)
            HaikuConsult.CandidatesScan(parsePass1(innerText), costUsd)
        } catch (e: Throwable) {
            System.err.println("[gemini-vision] scanInteriorCandidates failed: ${e.message}")
            HaikuConsult.CandidatesScan(emptyList(), 0.0)
        }
    }

    override suspend fun verifyLandmark(
        focusedScreenshotBase64: String?,
        candidateKind: String,
        candidateScreenX: Int,
        candidateScreenY: Int,
    ): HaikuConsult.VerifyResult {
        if (focusedScreenshotBase64.isNullOrEmpty()) {
            return HaikuConsult.VerifyResult.Errored("no-screenshot", 0.0)
        }
        return try {
            // Mirror the classifyShopMenu / classifyOverworldLandmark pattern:
            // build request body, POST to Gemini, parse envelope -> innerText + costUsd,
            // then parsePass2. maxOutputTokens=2000 because gemini-2.5-pro requires
            // thinking mode (which consumes ~400-600 tokens before producing JSON).
            val body = buildBody(
                systemPrompt = SYSTEM_VERIFY_LANDMARK,
                userText = "Verify candidate kind=$candidateKind at tile ($candidateScreenX, $candidateScreenY).",
                b64 = focusedScreenshotBase64,
                maxOutputTokens = 2000,
            )
            val raw = postOrNull(body)
                ?: return HaikuConsult.VerifyResult.Errored("api-error: no response", 0.0)
            val (innerText, costUsd) = parseEnvelope(raw)
            if (innerText == null) {
                return HaikuConsult.VerifyResult.Errored("envelope-malformed", costUsd)
            }
            parsePass2(innerText, costUsd)
        } catch (e: Throwable) {
            HaikuConsult.VerifyResult.Errored("api-error: ${e.message}", 0.0)
        }
    }

    /** V5.45: Gemini 2.5 Pro shop-purchase advisor — drives in-shop menu nav
     *  one tap at a time based on current screenshot + context. */
    override suspend fun adviseShopPurchase(
        screenshotBase64: String?,
        contextText: String,
    ): HaikuConsult.ShopPurchaseAdvice {
        if (screenshotBase64.isNullOrEmpty()) {
            return HaikuConsult.ShopPurchaseAdvice("Fail", "no-screenshot", 0.0)
        }
        return try {
            val body = buildBody(
                systemPrompt = HaikuConsult.SYSTEM_SHOP_PURCHASE,
                userText = contextText,
                b64 = screenshotBase64,
                maxOutputTokens = 4000,
                thinkingBudget = 1500,
            )
            val raw = postOrNull(body)
                ?: return HaikuConsult.ShopPurchaseAdvice("Fail", "api-error", 0.0)
            val (innerText, costUsd) = parseEnvelope(raw)
            if (innerText.isNullOrBlank()) {
                return HaikuConsult.ShopPurchaseAdvice("Fail", "envelope-malformed", costUsd)
            }
            val unfenced = innerText.replace(Regex("```(?:json)?\\s*"), "").replace("```", "")
            val match = JSON_OBJECT.find(unfenced)?.value
                ?: return HaikuConsult.ShopPurchaseAdvice("Fail",
                    "advice-not-json: ${innerText.take(80)}", costUsd)
            val advice = json.parseToJsonElement(match).jsonObject
            val action = advice["action"]?.jsonPrimitive?.contentOrNull ?: "Fail"
            val reason = advice["reason"]?.jsonPrimitive?.contentOrNull ?: "no-reason"
            HaikuConsult.ShopPurchaseAdvice(action, reason, costUsd)
        } catch (e: Throwable) {
            System.err.println("[gemini-vision] adviseShopPurchase failed: ${e.message}")
            HaikuConsult.ShopPurchaseAdvice("Fail", "exception: ${e.message}", 0.0)
        }
    }

    /** Spec 5: Gemini 2.5 Pro thinking-mode advisor for one-step shop nav. */
    override suspend fun adviseShopApproach(
        screenshotBase64: String?,
        contextText: String,
    ): HaikuConsult.AdviceResponse {
        if (screenshotBase64.isNullOrEmpty()) {
            return HaikuConsult.AdviceResponse("Fail", "no-screenshot", 0.0)
        }
        return try {
            // Gemini 2.5 Pro mandates thinking. Run #7 still hit envelope-malformed
            // at iter 33 with budget 1500 / max 4000 (cost $0.042 — runaway).
            // Bump headroom further so navigation thinking has room and JSON
            // emission isn't truncated.
            val body = buildBody(
                systemPrompt = HaikuConsult.SYSTEM_ADVISOR,
                userText = contextText,
                b64 = screenshotBase64,
                maxOutputTokens = 6000,
                thinkingBudget = 2000,
            )
            val raw = postOrNull(body)
                ?: return HaikuConsult.AdviceResponse("Fail", "api-error", 0.0)
            val (innerText, costUsd) = parseEnvelope(raw)
            if (innerText.isNullOrBlank()) {
                return HaikuConsult.AdviceResponse("Fail", "envelope-malformed", costUsd)
            }
            // Strip ```json … ``` markdown fences before regex (Gemini sometimes
            // wraps despite the system prompt asking for raw JSON).
            val unfenced = innerText.replace(Regex("```(?:json)?\\s*"), "").replace("```", "")
            val match = JSON_OBJECT.find(unfenced)?.value
                ?: return HaikuConsult.AdviceResponse("Fail", "advice-not-json: ${innerText.take(80)}", costUsd)
            val advice = json.parseToJsonElement(match).jsonObject
            val action = advice["action"]?.jsonPrimitive?.contentOrNull ?: "Fail"
            val reason = advice["reason"]?.jsonPrimitive?.contentOrNull ?: "no-reason"
            HaikuConsult.AdviceResponse(action, reason, costUsd)
        } catch (e: Throwable) {
            System.err.println("[gemini-vision] adviseShopApproach failed: ${e.message}")
            HaikuConsult.AdviceResponse("Fail", "exception: ${e.message}", 0.0)
        }
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

    private fun buildBody(
        systemPrompt: String,
        userText: String,
        b64: String?,
        maxOutputTokens: Int,
        thinkingBudget: Int? = null,
    ): String {
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
                if (thinkingBudget != null) {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingBudget", thinkingBudget)
                    })
                }
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

        // Spec 4 §3.1 — Pass 1 candidate scan. Verbatim from design doc.
        // Reuses AnthropicHaikuConsult.SYSTEM_INTERIOR_SCAN value to keep the prompt
        // identical across providers (Gemini Pro tends to recognize NES sprites better
        // than Haiku, per empirical 2026-05-08 runs — "pixele najlepiej w gemini").
        const val SYSTEM_INTERIOR_SCAN = AnthropicHaikuConsult.SYSTEM_INTERIOR_SCAN

        /** Pass-1 parser delegated to AnthropicHaikuConsult companion (same JSON schema). */
        fun parsePass1(raw: String) = AnthropicHaikuConsult.parsePass1(raw)

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

        private const val SYSTEM_SHOP_PHASE =
            "You are reading the current sub-screen of a Final Fantasy 1 (NES) shop dialog. " +
                "Distinguish between these phases:\n" +
                "  MAIN_MENU — three rows BUY / SELL / EXIT visible (cursor finger on one row).\n" +
                "  ITEM_LIST — a list of item names with prices on the right (cursor on one row).\n" +
                "  FOR_WHOM  — character portraits / names on the right with cursor finger; the prompt asks 'for whom?' or shows the 4-character party list to pick a recipient.\n" +
                "  BUY_CONFIRM — small YES/NO box with text like 'Buy for X G?' or 'Are you sure?'.\n" +
                "  ANOTHER   — post-purchase prompt asking if you want to buy another (YES/NO box, no item list visible).\n" +
                "  WELCOME   — only the 'Welcome' greeting box from the keeper, no menu cursor on BUY/SELL/EXIT yet.\n" +
                "  CLOSED    — no shop dialog at all; party is on the town overlay walking layer.\n" +
                "  UNKNOWN   — cannot determine.\n" +
                "Output strict JSON only: {\"phase\":\"MAIN_MENU|ITEM_LIST|FOR_WHOM|BUY_CONFIRM|ANOTHER|WELCOME|CLOSED|UNKNOWN\"}."

        private const val SHOP_PHASE_USER_TEXT = "Which sub-screen is this FF1 shop dialog showing?"

        fun parseShopMenuPhaseResponse(rawJson: String): HaikuConsult.ShopMenuPhaseClassification {
            val (innerText, costUsd) = parseEnvelope(rawJson)
            if (innerText == null) {
                return HaikuConsult.ShopMenuPhaseClassification(HaikuConsult.ShopMenuPhase.UNKNOWN, costUsd)
            }
            return try {
                val match = JSON_OBJECT.find(innerText)
                    ?: return HaikuConsult.ShopMenuPhaseClassification(HaikuConsult.ShopMenuPhase.UNKNOWN, costUsd)
                val obj = json.parseToJsonElement(match.value).jsonObject
                val phaseStr = obj["phase"]?.jsonPrimitive?.content?.uppercase()
                val phase = runCatching { HaikuConsult.ShopMenuPhase.valueOf(phaseStr ?: "UNKNOWN") }
                    .getOrDefault(HaikuConsult.ShopMenuPhase.UNKNOWN)
                HaikuConsult.ShopMenuPhaseClassification(phase, costUsd)
            } catch (e: Exception) {
                System.err.println("[gemini-vision] parseShopMenuPhase failed: ${e.message}")
                HaikuConsult.ShopMenuPhaseClassification(HaikuConsult.ShopMenuPhase.UNKNOWN, costUsd)
            }
        }

        /** Pass 2 system prompt (Spec 4 §3.2). Verbatim — public for unit test only. */
        const val SYSTEM_VERIFY_LANDMARK: String = """You are verifying a Final Fantasy 1 (NES) interior landmark candidate.
The image is a focused 32x32 pixel crop centered on tile coordinates the
candidate scanner reported. The candidate's claimed kind is provided in
the user message.

Two-step task:
(1) Confirm or reject that the candidate kind matches what you see.
(2) If confirmed AND the candidate kind is "shopkeeper", additionally
    classify the shop type from visual context (counter contents, NPC
    sprite palette): weapon|armor|whiteMagic|blackMagic|item|unknown.
    For non-shopkeeper kinds, refinedShopKind = null.

Output JSON only. Schema:
{"confirmed": true,
 "refinedKind":"<same as candidate kind>",
 "refinedShopKind":"<weapon|armor|whiteMagic|blackMagic|item|unknown>"
                    OR null for non-shopkeeper,
 "reason":"<short>"}
or
{"confirmed": false, "reason":"<short>"}

Confirmed examples by kind:
- shopkeeper: NPC behind a counter; refinedShopKind required.
  - weapon shop:  counter shows weapons (sword/axe/dagger/hammer/staff).
  - armor shop:   counter shows shields/helms/body armor.
  - whiteMagic:   CURE/HARM/FOG/RUSE scroll sprites.
  - blackMagic:   FIRE/LIT/SLEP/LOCK scroll sprites.
  - item shop:    potion/tent/cabin sprites.
  - unknown:      shopkeeper sprite clear but counter unclear.
- innkeeper: NPC near a bed/inn counter; refinedShopKind = null.
- king: NPC on throne; refinedShopKind = null.
- generic_npc / chest / sign / stairs_up / stairs_down / exit_tile:
  refinedShopKind = null.

Note: "innkeeper" is its own kind. Do NOT classify a shopkeeper as
"inn" — if you see a bed/inn context, the kind is "innkeeper", not
"shopkeeper" with shop type "inn".

Return ONLY JSON."""

        /** Pure parser for Pass 2 inner text. Public for unit test.
         *  Returns [HaikuConsult.VerifyResult.Confirmed] when JSON has confirmed=true
         *  with a refinedKind; [HaikuConsult.VerifyResult.Rejected] when confirmed=false
         *  OR the inner text is malformed (the envelope already responded — only the
         *  vision content is bad). Use [HaikuConsult.VerifyResult.Errored] only for
         *  HTTP/network/setup failures upstream of this function. */
        fun parsePass2(raw: String, costUsd: Double): HaikuConsult.VerifyResult {
            return try {
                val match = JSON_OBJECT.find(raw)?.value
                    ?: return HaikuConsult.VerifyResult.Rejected("malformed: no JSON object", costUsd)
                val obj = json.parseToJsonElement(match).jsonObject
                val confirmed = obj["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!confirmed) {
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "no reason"
                    return HaikuConsult.VerifyResult.Rejected(reason, costUsd)
                }
                val refinedKind = obj["refinedKind"]?.jsonPrimitive?.contentOrNull
                    ?: return HaikuConsult.VerifyResult.Rejected("malformed: missing refinedKind", costUsd)
                // refinedShopKind may be JsonNull or absent for non-shopkeeper kinds.
                val rsk = obj["refinedShopKind"]
                val refinedShopKind = if (rsk == null || rsk is JsonNull) {
                    null
                } else {
                    rsk.jsonPrimitive.contentOrNull
                }
                val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                HaikuConsult.VerifyResult.Confirmed(refinedKind, refinedShopKind, reason, costUsd)
            } catch (e: Throwable) {
                HaikuConsult.VerifyResult.Rejected("malformed: ${e.message}", costUsd)
            }
        }

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

        private const val SYSTEM_OVERWORLD_LANDMARK = """
You are a vision tool for the FF1 NES overworld. The user provides a 256x240
pixel screenshot which is a 16-tile-wide x 15-tile-tall viewport (each tile is
16x16 pixels). The party (4 sprite avatars overlapping into one figure) is
rendered approximately at the screen center, tile (8, 7). Your job is to locate
a specific landmark sprite.

Respond with strict JSON ONLY (no commentary, no markdown fences). Schema:
  {"found": true, "screenX": <int 0..15>, "screenY": <int 0..14>}
or
  {"found": false}

Use tile coordinates. Top-left tile is (0,0); bottom-right is (15,14). If the
landmark is not visible in the viewport, return {"found": false}.
"""

        private fun overworldUserText(kind: String): String = when (kind) {
            "chaos_shrine" -> """
Locate the Chaos Shrine (Temple of Fiends) in the viewport.

Visual cues: a small dark/grey stone temple structure, distinct from town walls.
It has a single visible front entrance. It is NOT a castle (no flag/towers),
NOT a town (no surrounding wall ring), NOT a forest tile. The shrine sits on
grass terrain in the early-game continent north of Coneria.

Return the tile coordinates of the entrance (the bottom-center tile of the
shrine sprite — the tile the party will step onto to enter).
""".trimIndent()
            else -> "Locate the landmark of kind '$kind' in the viewport."
        }

        fun parseOverworldResponse(rawJson: String): HaikuConsult.OverworldClassification {
            val (innerText, costUsd) = parseEnvelope(rawJson)
            if (innerText == null) return HaikuConsult.OverworldClassification.NotFound(costUsd)
            return try {
                val match = JSON_OBJECT.find(innerText)
                    ?: return HaikuConsult.OverworldClassification.NotFound(costUsd)
                val obj = json.parseToJsonElement(match.value).jsonObject
                val found = obj["found"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) ?: false
                if (!found) return HaikuConsult.OverworldClassification.NotFound(costUsd)
                val sx = obj["screenX"]?.jsonPrimitive?.content?.toIntOrNull()
                val sy = obj["screenY"]?.jsonPrimitive?.content?.toIntOrNull()
                if (sx == null || sy == null || sx !in 0..15 || sy !in 0..14) {
                    HaikuConsult.OverworldClassification.NotFound(costUsd)
                } else {
                    HaikuConsult.OverworldClassification.Found(sx, sy, costUsd)
                }
            } catch (e: Exception) {
                System.err.println("[gemini-vision] parseOverworld failed: ${e.message}")
                HaikuConsult.OverworldClassification.NotFound(costUsd)
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
