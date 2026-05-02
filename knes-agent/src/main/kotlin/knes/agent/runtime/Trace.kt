package knes.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Serializable
data class TraceEvent(
    val turn: Int,
    val role: String,           // "executor" | "advisor" | "watchdog" | "outcome"
    val phase: String,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val toolCalls: List<String> = emptyList(),
    val ramDiff: Map<String, Int> = emptyMap(),
    val screenshot: String? = null,
    val note: String? = null,
    /** Full prompt input sent to the LLM (system prompt context excluded; user message only). */
    val input: String? = null,
    /** Full untruncated text the LLM produced (advisor plan or executor reply). */
    val output: String? = null,
)

class Trace(dir: Path) {
    private val json = Json { prettyPrint = false }
    private val out = run {
        Files.createDirectories(dir)
        Files.newBufferedWriter(dir.resolve("trace.jsonl"))
    }
    private var turn = 0

    fun record(event: TraceEvent) {
        out.appendLine(json.encodeToString(TraceEvent.serializer(), event.copy(turn = ++turn)))
        out.flush()
    }

    fun close() = out.close()

    companion object {
        fun newRunDir(root: Path = Path.of("runs")): Path =
            root.resolve(Instant.now().toString().replace(':', '-'))
    }
}
