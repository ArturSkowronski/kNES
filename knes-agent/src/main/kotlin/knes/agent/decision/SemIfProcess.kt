package knes.agent.decision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * A local SemIf model, kept alive as a child process.
 *
 * SemIf's own CLI is batch — a JSONL file in, a new JSONL file out — and loads the
 * checkpoint on every invocation, about nine seconds for a 4-bit 4B on this machine.
 * A turn-by-turn agent cannot pay that per decision, so `tools/semif_sidecar.py` holds
 * the model and speaks one JSON decision per line. Measured on an M-series Mac with
 * Qwen3.5-4B at 4 bits: 8s to load, then **66-120 ms per decision**, which is one to two
 * orders of magnitude under the hosted chat call the Executor makes today.
 *
 * Calls are serialised: there is one model and one pipe, and interleaving two decisions
 * would mix up whose line is whose. A decision that does not answer within [callTimeout]
 * kills the process rather than returning — after a timeout the stream is out of step
 * and every later answer would belong to an earlier question.
 */
class SemIfProcess(
    private val command: List<String>,
    private val startupTimeoutMs: Long = 180_000,
    private val callTimeoutMs: Long = 30_000,
    private val workingDir: java.io.File? = null,
) : DecisionModel {

    private val lock = Mutex()
    private var process: Process? = null
    private var sink: BufferedWriter? = null
    private var source: BufferedReader? = null
    private var ready: String? = null

    override val name: String get() = ready ?: "semif (not started)"

    /**
     * Starts the child and waits for its ready line.
     *
     * Eager rather than lazy on purpose: a missing interpreter, an unimportable
     * `semif_phase1` or an uncached checkpoint should stop the run at startup with the
     * reason, not surface as a failed turn a quarter of an hour in.
     */
    suspend fun start(): SemIfProcess = lock.withLock {
        if (process != null) return@withLock this
        val started = ProcessBuilder(command)
            .redirectErrorStream(false)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .also { builder -> workingDir?.let { builder.directory(it) } }
            .start()
        process = started
        sink = started.outputStream.bufferedWriter()
        source = started.inputStream.bufferedReader()
        val hello = readLine(startupTimeoutMs) ?: run {
            // Distinguish the two ways this goes wrong: a process that died (a missing
            // interpreter, a sidecar path that does not resolve, an unimportable
            // semif_phase1 — its own message is on stderr) from one still loading.
            val exit = if (started.isAlive) null else started.exitValue()
            fail(
                if (exit == null) "SemIf sidecar did not become ready within ${startupTimeoutMs}ms"
                else "SemIf sidecar exited with code $exit before it was ready (its reason is on stderr above)" +
                    "; command was ${command.joinToString(" ")}",
            )
        }
        val parsed = json.parseToJsonElement(hello).jsonObject
        parsed["error"]?.let { fail("SemIf sidecar failed to start: ${it.jsonPrimitive.content}") }
        val model = parsed["model"]?.jsonPrimitive?.content ?: "unknown"
        val backend = parsed["backend"]?.jsonPrimitive?.content ?: "unknown"
        ready = "semif/$backend/$model"
        // A child process outlives an abrupt JVM exit, and an agent run ends with Ctrl-C
        // as often as it ends by returning. A shutdown hook covers both; `close()` stays
        // for tests, and running it twice is harmless.
        Runtime.getRuntime().addShutdownHook(Thread { closeQuietly() })
        this
    }

    override suspend fun choose(choice: Choice): Ranking = lock.withLock {
        val out = sink ?: error("SemIfProcess.start() has not been called")
        withContext(Dispatchers.IO) {
            out.write(json.encodeToString(JsonObject.serializer(), wire(choice)))
            out.write("\n")
            out.flush()
        }
        val reply = readLine(callTimeoutMs)
            ?: fail("SemIf did not answer '${choice.id}' within ${callTimeoutMs}ms")
        parseRanking(choice, reply, name)
    }

    /**
     * Blocking reads cannot be interrupted, so the read runs on its own IO coroutine and
     * a timeout takes the whole process down with it — see the class note on why a
     * desynchronised pipe is not worth recovering.
     */
    private suspend fun readLine(timeoutMs: Long): String? = coroutineScope {
        val reader = source ?: error("SemIfProcess.start() has not been called")
        val read = async(Dispatchers.IO) { reader.readLine() }
        val line = withTimeoutOrNull(timeoutMs) { read.await() }
        if (line == null) {
            read.cancel()
            closeQuietly()
        }
        line
    }

    private fun fail(message: String): Nothing {
        closeQuietly()
        error(message)
    }

    private fun closeQuietly() {
        runCatching { sink?.close() }
        runCatching { source?.close() }
        runCatching { process?.destroy() }
        process = null
        sink = null
        source = null
    }

    override fun close() = closeQuietly()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** A [Choice] is already SemIf's row shape, so this is a rename-free encode. */
        internal fun wire(choice: Choice): JsonObject = buildJsonObject {
            put("id", choice.id)
            put("state", choice.state)
            put("question", choice.question)
            choice.imageB64?.let { put("image", it) }
            put("options", buildJsonArray {
                choice.options.forEach { option ->
                    add(buildJsonObject {
                        put("id", option.id)
                        put("description", option.description)
                    })
                }
            })
        }

        /**
         * Split from the pipe so the wire contract can be tested without a model.
         *
         * The reply names its options, and they are checked against the ones that were
         * asked about: a reply for some other decision, or one that renames an option,
         * is a bug worth failing on rather than a ranking worth acting on.
         */
        internal fun parseRanking(choice: Choice, reply: String, model: String): Ranking {
            val parsed = json.parseToJsonElement(reply).jsonObject
            parsed["error"]?.let { error("SemIf rejected '${choice.id}': ${it.jsonPrimitive.content}") }
            val ids = parsed["option_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: error("SemIf reply for '${choice.id}' has no option_ids: ${reply.take(200)}")
            val probabilities = parsed["probabilities"]?.jsonArray?.map { it.jsonPrimitive.double }
                ?: error("SemIf reply for '${choice.id}' has no probabilities: ${reply.take(200)}")
            require(ids.size == probabilities.size) {
                "SemIf reply for '${choice.id}' has ${ids.size} options and ${probabilities.size} probabilities"
            }
            require(ids.toSet() == choice.options.map { it.id }.toSet()) {
                "SemIf answered about $ids, but '${choice.id}' declared ${choice.options.map { it.id }}"
            }
            return Ranking.of(choice.id, model, ids.zip(probabilities))
        }
    }
}
