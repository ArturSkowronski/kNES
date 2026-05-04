package knes.agent.perception

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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * V5.18 — vision-driven overworld walker. Mirrors V3.0
 * [VisionInteriorNavigator] but tuned for FF1's outdoor map: open
 * terrain (grass / forest / desert / marsh) interrupted by mountains,
 * water, towns, and castles. The agent has a target world coordinate
 * and the vision model picks the next cardinal toward it, identifying
 * walkable terrain and avoiding visually-obvious blockers.
 *
 * Why this skill exists (per HANDOFF #4 + V5.17 evidence):
 * Coneria8VisualDiffTest fails because [OverworldTileClassifier]
 * cannot distinguish TOWN-entry from TOWN-decoration tiles — it's a
 * `tileset_prop` ROM bit, not a byte-id (per Entroper FF1 disasm
 * bank_0F.asm:1633). The deterministic BFS routes to a TOWN tile and
 * the engine refuses entry from non-canonical directions, which
 * cannot be diagnosed offline. A vision model can simply "look at the
 * screen" and aim for the obvious walkable approach.
 */
enum class OverworldMove(val button: String?) {
    NORTH("UP"),
    SOUTH("DOWN"),
    EAST("RIGHT"),
    WEST("LEFT"),
    /** Vision says party already entered an interior (mapflags bit 0 set, screen flipped). */
    ENTERED(null),
    /** Vision can't see a walkable path — give up to the outer loop. */
    STUCK(null),
    /** Parse / API failure — treat like STUCK. */
    UNCLEAR(null);

    companion object {
        fun fromCardinal(c: String): OverworldMove? = when (c.uppercase()) {
            "N", "NORTH" -> NORTH
            "S", "SOUTH" -> SOUTH
            "E", "EAST" -> EAST
            "W", "WEST" -> WEST
            "ENTERED" -> ENTERED
            "STUCK" -> STUCK
            else -> null
        }
    }
}

interface VisionOverworldNavigator {
    /**
     * @param screenshotBase64 base64-encoded PNG of current frame
     * @param frame current frame number (cache key)
     * @param partyWorldXY current party world coords (RAM-derived)
     * @param targetWorldXY destination world coords (the skill's goal)
     * @param hintLastBlocked direction the most-recent step physically failed on
     */
    suspend fun nextDirection(
        screenshotBase64: String,
        frame: Int,
        partyWorldXY: Pair<Int, Int>,
        targetWorldXY: Pair<Int, Int>,
        hintLastBlocked: OverworldMove? = null,
    ): OverworldMove
}

/**
 * Anthropic-backed implementation. Same model + API choice as
 * [AnthropicVisionInteriorNavigator] (Sonnet 4.6 for spatial reasoning
 * on sprite art).
 */
class AnthropicVisionOverworldNavigator(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
) : VisionOverworldNavigator, AutoCloseable {

    private var cachedFrame: Int = -1
    private var cachedKey: String = ""
    private var cachedMove: OverworldMove = OverworldMove.UNCLEAR
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun nextDirection(
        screenshotBase64: String,
        frame: Int,
        partyWorldXY: Pair<Int, Int>,
        targetWorldXY: Pair<Int, Int>,
        hintLastBlocked: OverworldMove?,
    ): OverworldMove {
        val key = "$frame|${partyWorldXY}|${targetWorldXY}|${hintLastBlocked?.name}"
        if (frame == cachedFrame && key == cachedKey && cachedMove != OverworldMove.UNCLEAR) {
            return cachedMove
        }
        val body = buildRequestBody(screenshotBase64, partyWorldXY, targetWorldXY, hintLastBlocked)
        val resp = try {
            client.post(API_URL) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            System.err.println("[ow-vision-nav] request failed: ${e.message}")
            return OverworldMove.UNCLEAR
        }
        if (!resp.status.isSuccess()) {
            System.err.println("[ow-vision-nav] http ${resp.status.value}: ${resp.bodyAsText().take(200)}")
            return OverworldMove.UNCLEAR
        }
        val move = parseMove(resp.bodyAsText())
        cachedFrame = frame
        cachedKey = key
        cachedMove = move
        return move
    }

    private fun buildRequestBody(
        b64: String,
        partyXY: Pair<Int, Int>,
        targetXY: Pair<Int, Int>,
        hintLastBlocked: OverworldMove?,
    ): String {
        val dx = targetXY.first - partyXY.first
        val dy = targetXY.second - partyXY.second
        val rough = buildString {
            if (dy < 0) append("N")
            if (dy > 0) append("S")
            if (dx > 0) append("E")
            if (dx < 0) append("W")
        }.ifEmpty { "(at target)" }
        val userText = buildString {
            append("Pick the next direction. Return JSON only.\n")
            append("Party at world ($partyXY); target at $targetXY (offset dx=$dx dy=$dy, rough=$rough).\n")
            if (hintLastBlocked != null) {
                append("Last attempt to go ${hintLastBlocked.name} did not move the party — pick a different walkable direction.\n")
            }
            append("If the screen has clearly transitioned to an interior view (close-up of a building " +
                "interior, castle hall, or shop counter), return ENTERED.")
        }
        val obj = buildJsonObject {
            put("model", model)
            put("max_tokens", 200)
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
                            put("text", userText)
                        })
                    })
                })
            })
        }
        return obj.toString()
    }

    internal fun parseMove(rawJson: String): OverworldMove {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val text = root["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return OverworldMove.UNCLEAR
            val match = DIRECTION_REGEX.find(text) ?: return OverworldMove.UNCLEAR
            OverworldMove.fromCardinal(match.groupValues[1]) ?: OverworldMove.UNCLEAR
        } catch (e: Exception) {
            System.err.println("[ow-vision-nav] parse failed: ${e.message}")
            OverworldMove.UNCLEAR
        }
    }

    override fun close() { client.close() }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private val DIRECTION_REGEX = Regex("""\"direction\"\s*:\s*\"([A-Za-z]+)\"""")

        private const val SYSTEM_PROMPT =
            "You navigate the FF1 overworld (NES). Top-down terrain map: grass (green), forest " +
                "(dark trees), mountains (gray peaks — IMPASSABLE on foot), water/ocean (blue " +
                "tiles — IMPASSABLE without ship/canoe; FF1 V2.x has no canoe yet), desert " +
                "(yellow tiles — slow but passable), bridges (brown horizontal/vertical strips " +
                "— passable). Towns appear as cluster of small buildings with a path; castles " +
                "as larger stone structures. Both can be ENTERED by stepping on the right entry " +
                "tile (not every visible tile of a town is an entry — usually the path leading " +
                "in). " +
                "The party stands at the centre of the screen (~tile 8,7). Pick ONE direction " +
                "(N/S/E/W) that moves the party CLOSER to its target world coordinate while " +
                "staying on walkable terrain. Forest is walkable but slows movement (encounter " +
                "rate is higher). " +
                "If the screen clearly switched to an interior (close-up walls, NPCs, throne, " +
                "shop counter — NOT the overworld terrain map) return ENTERED. " +
                "Return STUCK ONLY if all four cardinal tiles are mountain/water/wall — i.e. " +
                "literally nothing walkable. Forest, grass, desert, marsh, bridges, towns and " +
                "castles all count as walkable. " +
                "When approaching a TOWN or CASTLE, the entry tile is usually the path or door " +
                "facing the direction of approach — try that direction even if it looks like " +
                "the building wall, the engine will transition the party inside if it's a real " +
                "entry. " +
                "Output ONLY JSON: {\"direction\":\"N|S|E|W|ENTERED|STUCK\",\"reason\":\"<<=80 chars\"}."
    }
}
