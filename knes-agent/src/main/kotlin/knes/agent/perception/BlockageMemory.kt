package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Serializable
data class Blockage(
    val runId: String,
    val fromX: Int,
    val fromY: Int,
    val attemptedTo: String,
    val result: String,
    val ts: String,  // ISO-8601 instant
)

@Serializable
private data class BlockageFile(
    val version: Int = 1,
    val blockages: List<Blockage> = emptyList(),
    val runStartDirections: Map<String, String> = emptyMap(),
)

class BlockageMemory(
    private val file: File = defaultFile(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val blockages: MutableList<Blockage> = mutableListOf()
    private val runStartDirections: MutableMap<String, String> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(BlockageFile.serializer(), file.readText())
            blockages.addAll(parsed.blockages)
            runStartDirections.putAll(parsed.runStartDirections)
        } catch (e: Exception) {
            System.err.println("[blockage-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        val payload = BlockageFile(blockages = blockages.toList(),
            runStartDirections = runStartDirections.toMap())
        AtomicJsonWriter.write(file, json.encodeToString(BlockageFile.serializer(), payload))
    }

    fun record(runId: String, from: Pair<Int, Int>, attemptedTo: String, result: String) {
        blockages += Blockage(
            runId = runId, fromX = from.first, fromY = from.second,
            attemptedTo = attemptedTo, result = result,
            ts = Instant.now(clock).toString(),
        )
    }

    fun recordRunStartDirection(runId: String, dir: String) {
        runStartDirections[runId] = dir
    }

    fun recentFailures(within: Duration): List<Blockage> {
        val cutoff = Instant.now(clock).minus(within)
        return blockages.filter { runCatching { Instant.parse(it.ts) > cutoff }.getOrDefault(false) }
    }

    fun recentlyFailedTargets(within: Duration): Set<String> =
        recentFailures(within).map { it.attemptedTo }.toSet()

    /** Returns the K most-recent runStartDirections, oldest first. */
    fun pathTriedRecentDirections(k: Int): List<String> =
        runStartDirections.values.toList().takeLast(k)

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-blockages.json")
    }
}
