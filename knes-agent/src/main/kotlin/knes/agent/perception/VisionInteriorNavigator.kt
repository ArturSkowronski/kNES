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
 * Picks one cardinal step or signals exit/stuck for the FF1 interior walker.
 * V3.0 Task 1: vision-driven; bypasses Koog and calls Anthropic Messages API
 * directly with a single image attachment, mirroring AnthropicVisionPhaseClassifier.
 */
enum class InteriorMove(val button: String?) {
    NORTH("UP"),
    SOUTH("DOWN"),
    EAST("RIGHT"),
    WEST("LEFT"),
    EXIT(null),     // model says we're already on / about to leave for the overworld
    STUCK(null),    // no walkable direction visible — give up to outer loop
    UNCLEAR(null);  // parse / API failure — treat like STUCK

    companion object {
        fun fromCardinal(c: String): InteriorMove? = when (c.uppercase()) {
            "N", "NORTH" -> NORTH
            "S", "SOUTH" -> SOUTH
            "E", "EAST" -> EAST
            "W", "WEST" -> WEST
            "EXIT" -> EXIT
            "STUCK" -> STUCK
            else -> null
        }
    }
}

interface VisionInteriorNavigator {
    /**
     * @param screenshotBase64 base64-encoded PNG of current frame
     * @param frame current frame number (used for caching — same frame skips API call)
     * @param hintLastBlocked direction the most-recent step physically failed on, if any
     * @param entryDirection direction the party walked to ENTER this interior (i.e.
     *   the opposite is usually the way out). Optional bias for the prompt.
     * @param frontierHint V5.11 forced-exploration: cardinal direction toward the nearest
     *   reachable tile we have NOT yet visited in this interior. Soft bias the model
     *   away from premature EXIT.
     * @param unvisitedReachable V5.11: estimated count of reachable unvisited tiles. 0
     *   means the map is fully covered — model is free to head for the exit.
     */
    suspend fun nextDirection(
        screenshotBase64: String,
        frame: Int,
        hintLastBlocked: InteriorMove? = null,
        entryDirection: InteriorMove? = null,
        frontierHint: InteriorMove? = null,
        unvisitedReachable: Int = 0,
    ): InteriorMove
}

/**
 * Anthropic-backed implementation. Uses Sonnet 4.6 (better spatial reasoning on
 * sprite art than Haiku per V3.0 Task 0 feasibility probe).
 *
 * Prompt design lessons from Task 0:
 *  - Vision tends to mistake throne dais / treasure / shop counters for "doors".
 *    Explicitly list non-exits.
 *  - In castles the entry corridor is usually the southern pillar-corridor; bias
 *    away from the "ornate end" of the room.
 *  - Optional `entryDirection` hint lets the navigator know "you walked N to enter,
 *    head S to exit" — directly addresses the inside-vs-outside ambiguity.
 */
class AnthropicVisionInteriorNavigator(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
) : VisionInteriorNavigator, AutoCloseable {

    private var cachedFrame: Int = -1
    private var cachedKey: String = ""
    private var cachedMove: InteriorMove = InteriorMove.UNCLEAR
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun nextDirection(
        screenshotBase64: String,
        frame: Int,
        hintLastBlocked: InteriorMove?,
        entryDirection: InteriorMove?,
        frontierHint: InteriorMove?,
        unvisitedReachable: Int,
    ): InteriorMove {
        val key = "$frame|${hintLastBlocked?.name}|${entryDirection?.name}" +
            "|${frontierHint?.name}|$unvisitedReachable"
        if (frame == cachedFrame && key == cachedKey && cachedMove != InteriorMove.UNCLEAR) {
            return cachedMove
        }
        val body = buildRequestBody(screenshotBase64, hintLastBlocked, entryDirection,
            frontierHint, unvisitedReachable)
        val resp = try {
            client.post(API_URL) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            System.err.println("[vision-nav] request failed: ${e.message}")
            return InteriorMove.UNCLEAR
        }
        if (!resp.status.isSuccess()) {
            System.err.println("[vision-nav] http ${resp.status.value}: ${resp.bodyAsText().take(200)}")
            return InteriorMove.UNCLEAR
        }
        val move = parseMove(resp.bodyAsText())
        cachedFrame = frame
        cachedKey = key
        cachedMove = move
        return move
    }

    private fun buildRequestBody(
        b64: String,
        hintLastBlocked: InteriorMove?,
        entryDirection: InteriorMove?,
        frontierHint: InteriorMove?,
        unvisitedReachable: Int,
    ): String {
        val userText = buildString {
            append("Pick the next direction for the party. Return JSON only.")
            if (entryDirection != null) {
                val opposite = when (entryDirection) {
                    InteriorMove.NORTH -> "S"
                    InteriorMove.SOUTH -> "N"
                    InteriorMove.EAST -> "W"
                    InteriorMove.WEST -> "E"
                    else -> null
                }
                if (opposite != null) {
                    append("\nContext: party entered this interior by walking ${entryDirection.name}; ")
                    append("the way OUT is usually $opposite.")
                }
            }
            if (hintLastBlocked != null) {
                append("\nLast attempt to go ${hintLastBlocked.name} was physically blocked — pick a different direction.")
            }
            if (unvisitedReachable > 0) {
                append("\nExploration status: $unvisitedReachable reachable tile(s) still unvisited.")
                if (frontierHint != null) {
                    append(" Nearest unvisited area is to the ${frontierHint.name}.")
                }
                append(" PREFER exploring unvisited area over EXIT — uncover the map first.")
            } else if (entryDirection == null && hintLastBlocked == null) {
                // Map fully covered (no frontier) and no other context: silent.
            }
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

    internal fun parseMove(rawJson: String): InteriorMove {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val text = root["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return InteriorMove.UNCLEAR
            val match = DIRECTION_REGEX.find(text) ?: return InteriorMove.UNCLEAR
            InteriorMove.fromCardinal(match.groupValues[1]) ?: InteriorMove.UNCLEAR
        } catch (e: Exception) {
            System.err.println("[vision-nav] parse failed: ${e.message}")
            InteriorMove.UNCLEAR
        }
    }

    override fun close() { client.close() }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private val DIRECTION_REGEX = Regex("""\"direction\"\s*:\s*\"([A-Za-z]+)\"""")

        private const val SYSTEM_PROMPT =
            "You navigate a party in Final Fantasy 1 (NES) inside a town, castle, or dungeon. " +
                "The party stands at the centre of the screen (around tile column 8, row 7). " +
                "Pick ONE direction (N/S/E/W) that moves the party CLOSER TO THE EXIT (the world map). " +
                "Interior types and how to read them: " +
                "(1) TOWN — open OUTDOOR area with shops/houses, dirt or stone PATHS between buildings, " +
                "NPCs (red/blue/green sprites) standing on paths. The path-network IS walkable. NPCs are " +
                "minor obstacles you walk AROUND. The exit is the south edge of the visible map — " +
                "walk SOUTH along visible paths until the screen scrolls off the town. " +
                "(2) CASTLE — stone-floored corridors and rooms, walls, columns, sometimes a throne or " +
                "king. The way OUT is the southern pillar-corridor (the way the party came in). " +
                "(3) DUNGEON — dark rooms, staircases (`>` icon), warps (`*` icon). Stairs/warps are " +
                "transitions to other sub-maps; head toward them or toward the south edge. " +
                "Walkable terrain: dirt paths, stone floors, bridges, open doorways, staircase tiles. " +
                "Impassable: SOLID walls, water tiles (~), impassable bushes. " +
                "Decorative / interactive (NOT exits): thrones, kings/NPCs in chairs, treasure chests, " +
                "dais platforms, fountains, shop counters, ornate doors with rings — do NOT mistake these " +
                "for the way out. " +
                "If the screen clearly shows the overworld (top-down terrain map: grass, mountains, " +
                "water, party visible on terrain) return EXIT. " +
                "Return STUCK ONLY if the party is fully surrounded by walls/water on all four cardinal " +
                "tiles — i.e. there is genuinely no walkable adjacent tile. If even ONE direction shows " +
                "walkable terrain, pick that direction even if the path looks long or winding. " +
                "FORCED EXPLORATION (V5.11): when the user message says reachable tiles are still " +
                "unvisited, treat covering the map as your TOP priority — even above seeking the exit. " +
                "Pick the suggested explore direction (or any direction toward unvisited area) and " +
                "return EXIT only when no unvisited tiles remain or the party is genuinely on the overworld. " +
                "Output ONLY JSON: {\"direction\":\"N|S|E|W|EXIT|STUCK\",\"reason\":\"<<=80 chars\"}."
    }
}
