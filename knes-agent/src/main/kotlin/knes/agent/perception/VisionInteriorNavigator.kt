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
     */
    suspend fun nextDirection(
        screenshotBase64: String,
        frame: Int,
        hintLastBlocked: InteriorMove? = null,
        entryDirection: InteriorMove? = null,
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
    ): InteriorMove {
        val key = "$frame|${hintLastBlocked?.name}|${entryDirection?.name}"
        if (frame == cachedFrame && key == cachedKey && cachedMove != InteriorMove.UNCLEAR) {
            return cachedMove
        }
        val body = buildRequestBody(screenshotBase64, hintLastBlocked, entryDirection)
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
            "You navigate a party in Final Fantasy 1 (NES) inside a town/castle/dungeon. " +
                "The party stands at the centre of the screen (around tile column 8, row 7). " +
                "Pick ONE direction (N/S/E/W) that moves the party CLOSER TO THE EXIT — " +
                "the way out to the world map. Exits look like: an unadorned doorway at a " +
                "wall edge, a staircase tile (small steps icon), an open corridor that " +
                "extends off the visible viewport at the SOUTH edge of the room, a path " +
                "leading to the bottom of the screen. " +
                "NON-EXITS (do NOT walk toward these as if they were exits): thrones, " +
                "kings/NPCs in chairs, treasure chests, dais platforms, fountains, shop " +
                "counters, locked doors with rings/handles. These are decoration / interactive " +
                "tiles, not the way out. " +
                "Castles in FF1 are entered through their southern entrance, so the way out " +
                "is normally toward the SOUTH — head AWAY from thrones and ornate features. " +
                "If the screen shows the overworld (top-down terrain map with no walls/floors, " +
                "visible grass, mountains, water), return EXIT. " +
                "If you cannot identify any clear walkable direction, return STUCK. " +
                "Output ONLY JSON: {\"direction\":\"N|S|E|W|EXIT|STUCK\",\"reason\":\"<<=80 chars\"}."
    }
}
