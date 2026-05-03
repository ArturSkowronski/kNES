package knes.agent.perception

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/**
 * Coarse phase hint derived from the rendered NES frame, *not* RAM.
 *
 * V2.5: V2.3.1's "non-zero localX/Y → Indoors" RAM heuristic mis-classified the
 * spawn-state (Indoors at boot when party is on overworld). Visual frame is
 * unambiguous — title screen, world map view, and interior view are visually
 * distinct. Battle / PostBattle / PartyDefeated stay on RAM (deterministic via
 * screenState / status flags).
 */
enum class PhaseHint { TITLE, OVERWORLD, INDOORS, UNKNOWN }

interface VisionPhaseClassifier {
    /**
     * @param screenshotBase64 base64-encoded PNG of current frame
     * @param frame current frame number (used for caching — same frame skips API call)
     */
    suspend fun classify(screenshotBase64: String, frame: Int): PhaseHint
}

/**
 * Calls Anthropic Messages API directly via Ktor with a single image attachment
 * and asks Haiku 4.5 for a one-of-three phase classification. JSON output only.
 *
 * Cached by frame: identical frame returns the prior answer without an API call.
 */
class AnthropicVisionPhaseClassifier(
    private val apiKey: String,
    private val model: String = "claude-haiku-4-5",
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
) : VisionPhaseClassifier, AutoCloseable {

    private var cachedFrame: Int = -1
    private var cachedHint: PhaseHint = PhaseHint.UNKNOWN
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun classify(screenshotBase64: String, frame: Int): PhaseHint {
        if (frame == cachedFrame && cachedHint != PhaseHint.UNKNOWN) return cachedHint
        val body = buildRequestBody(screenshotBase64)
        val resp = try {
            client.post(API_URL) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            System.err.println("[vision] request failed: ${e.message}")
            return PhaseHint.UNKNOWN
        }
        if (!resp.status.isSuccess()) {
            System.err.println("[vision] http ${resp.status.value}: ${resp.bodyAsText().take(200)}")
            return PhaseHint.UNKNOWN
        }
        val hint = parseHint(resp.bodyAsText())
        cachedFrame = frame
        cachedHint = hint
        return hint
    }

    private fun buildRequestBody(b64: String): String {
        val obj = buildJsonObject {
            put("model", model)
            put("max_tokens", 50)
            // Schema-ish prompt: tight system + JSON output instruction on user.
            put("system", SYSTEM_PROMPT)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/png")
                                put("data", b64)
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", USER_PROMPT)
                        })
                    })
                })
            })
        }
        return obj.toString()
    }

    private fun parseHint(rawJson: String): PhaseHint {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val text = root["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return PhaseHint.UNKNOWN
            // Look for {"phase":"..."} possibly with surrounding whitespace/codefence.
            val match = PHASE_REGEX.find(text) ?: return PhaseHint.UNKNOWN
            when (match.groupValues[1].uppercase()) {
                "TITLE" -> PhaseHint.TITLE
                "OVERWORLD" -> PhaseHint.OVERWORLD
                "INDOORS" -> PhaseHint.INDOORS
                else -> PhaseHint.UNKNOWN
            }
        } catch (e: Exception) {
            System.err.println("[vision] parse failed: ${e.message}")
            PhaseHint.UNKNOWN
        }
    }

    override fun close() { client.close() }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private val PHASE_REGEX = Regex("""\"phase\"\s*:\s*\"([A-Za-z]+)\"""")

        private const val SYSTEM_PROMPT =
            "You classify Final Fantasy 1 (NES) game screens. Output only JSON: " +
                "{\"phase\":\"TITLE\"|\"OVERWORLD\"|\"INDOORS\"}. " +
                "TITLE = title/menu/character creation/name entry/intro screen. " +
                "OVERWORLD = world map view (top-down terrain: grass, forests, mountains, water, " +
                "small towns/castles visible as map sprites; party visible as a small group on the map). " +
                "INDOORS = interior view (inside a town, shop, castle, dungeon — discrete tiled rooms, " +
                "walls, floors, NPCs; no overworld terrain). No prose, just JSON."

        private const val USER_PROMPT = "Classify this FF1 frame. Return JSON only."
    }
}
