package knes.agent.tools.save

import kotlinx.serialization.json.Json
import java.util.Base64

object SaveFormatCodec {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(
        emulatorStateBytes: ByteArray,
        rom: String,
        intent: String,
        recentMoves: List<MoveEntry>,
        decisionLog: List<DecisionEntry>,
        landmarks: LandmarksSnapshot,
        visitedMinimap: VisitedMinimap,
        createdAtMs: Long = System.currentTimeMillis(),
    ): KnesSave = KnesSave(
        schemaVersion = SUPPORTED_SCHEMA_VERSION,
        createdAtMs = createdAtMs,
        rom = rom,
        emulatorState = Base64.getEncoder().encodeToString(emulatorStateBytes),
        currentIntent = intent,
        recentMoves = recentMoves,
        decisionLog = decisionLog,
        landmarks = landmarks,
        visitedMinimap = visitedMinimap,
    )

    fun decodeEmulatorBytes(save: KnesSave): ByteArray =
        Base64.getDecoder().decode(save.emulatorState)

    fun toJson(save: KnesSave): String = json.encodeToString(KnesSave.serializer(), save)

    fun fromJson(text: String): KnesSave {
        val parsed = json.decodeFromString(KnesSave.serializer(), text)
        check(parsed.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported KnesSave schemaVersion=${parsed.schemaVersion} (supported=$SUPPORTED_SCHEMA_VERSION)"
        }
        return parsed
    }
}
