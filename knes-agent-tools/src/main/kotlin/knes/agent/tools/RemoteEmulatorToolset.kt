package knes.agent.tools

import knes.agent.tools.results.ActionDescriptor
import knes.agent.tools.results.ActionToolResult
import knes.agent.tools.results.ProfileSummary
import knes.agent.tools.results.ScreenPng
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StatusResult
import knes.agent.tools.results.StepEntry
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Remote variant of [EmulatorToolset] that talks to a running kNES REST API
 * (`/health`, `/rom`, `/step`, `/tap`, `/screen/base64`, `/state`, `/profiles/...`)
 * instead of an in-process [EmulatorSession]. This lets the v2 agent play
 * against the emulator hosted by the Compose UI (which exposes the same
 * endpoints via [knes.api.EmbeddedApiServer]) so the audience sees the live
 * gameplay on the Compose window while the agent decides remotely.
 *
 * The parent class' `session` field is required by the type hierarchy but
 * NEVER touched here — every overridden method takes the HTTP path. A
 * default-constructed [EmulatorSession] still boots an in-process NES (~30 MB
 * idle), which is wasted memory but harmless; the trade-off keeps the
 * existing `EmulatorToolset` consumers (ToolSurface, SnapshotDumper, etc.)
 * working unchanged.
 *
 * **NOT used:** `loadRom` and direct save-state checkpoints. Use the Compose
 * UI's "Open ROM" menu to load before starting the agent. Resume mode is
 * also unsupported in remote (skip `--resume` when `--remote` is set).
 */
class RemoteEmulatorToolset(
    private val baseUrl: String,
    sessionStub: EmulatorSession = EmulatorSession(),
) : EmulatorToolset(sessionStub) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "RemoteEmulatorToolset baseUrl must start with http:// or https://, got: $baseUrl"
        }
        // Fail fast if the remote API is unreachable. Without this, the first
        // tool call would throw deep inside the agent loop with a confusing
        // ConnectException — better to die at startup with a clear message.
        val probe = try { get("/health") } catch (e: Exception) {
            error("Cannot reach kNES REST API at $baseUrl: ${e.message}. " +
                  "Start the Compose UI and click 'Start API Server'.")
        }
        System.err.println("[RemoteEmulatorToolset] connected to $baseUrl — health: $probe")
    }

    override fun loadRom(path: String): StatusResult {
        val resp = postJson("/rom", """{"path":"${escape(path)}"}""")
        val ok = resp != null && parseRomLoaded(resp)
        return StatusResult(ok, if (ok) "ROM loaded (remote): $path" else "Remote loadRom failed: ${resp ?: "no response"}")
    }

    override fun reset(): StatusResult {
        postJson("/reset", "{}")
        return StatusResult(true, "reset (remote)")
    }

    override fun step(buttons: List<String>, frames: Int, screenshot: Boolean): StepResult {
        require(frames in 1..600) { "frames must be 1..600, got $frames" }
        val body = """{"buttons":[${buttons.joinToString(",") { "\"${escape(it)}\"" }}],"frames":$frames,"screenshot":$screenshot}"""
        val resp = postJson("/step", body) ?: error("Remote /step returned no body")
        return parseStepResult(resp)
    }

    override fun tap(
        button: String,
        count: Int,
        pressFrames: Int,
        gapFrames: Int,
        screenshot: Boolean,
    ): StepResult {
        require(count in 1..50) { "count must be 1..50, got $count" }
        val body = """{"button":"${escape(button)}","count":$count,"pressFrames":$pressFrames,"gapFrames":$gapFrames,"screenshot":$screenshot}"""
        val resp = postJson("/tap", body) ?: error("Remote /tap returned no body")
        return parseStepResult(resp)
    }

    override fun sequence(steps: List<StepEntry>, screenshot: Boolean): StepResult {
        require(steps.isNotEmpty()) { "sequence requires at least one entry" }
        val stepsJson = steps.joinToString(",") { s ->
            val btns = s.buttons.joinToString(",") { "\"${escape(it)}\"" }
            """{"buttons":[$btns],"frames":${s.frames}}"""
        }
        val body = """{"sequence":[$stepsJson],"screenshot":$screenshot}"""
        val resp = postJson("/step", body) ?: error("Remote /step (sequence) returned no body")
        return parseStepResult(resp)
    }

    override fun getState(): StateSnapshot {
        val resp = get("/state") ?: error("Remote /state returned no body")
        val obj = json.parseToJsonElement(resp).jsonObject
        val frame = obj["frame"]?.jsonPrimitive?.intOrNull ?: 0
        val ram = (obj["ram"] as? JsonObject)?.mapValues {
            it.value.jsonPrimitive.intOrNull ?: 0
        } ?: emptyMap()
        val cpuRaw = obj["cpu"] as? JsonObject
        val cpu = cpuRaw?.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 }
            ?: emptyMap()
        val held = (obj["buttons"] as? JsonArray
            ?: obj["heldButtons"] as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()
        return StateSnapshot(frame = frame, ram = ram, cpu = cpu, heldButtons = held)
    }

    override fun getScreen(): ScreenPng {
        val resp = get("/screen/base64") ?: error("Remote /screen/base64 returned no body")
        val obj = json.parseToJsonElement(resp).jsonObject
        val b64 = obj["image"]?.jsonPrimitive?.contentOrNull ?: ""
        return ScreenPng(base64 = b64)
    }

    override fun applyProfile(id: String): StatusResult {
        val resp = postJson("/profiles/${escapePath(id)}/apply", "{}")
        return if (resp != null) StatusResult(true, "applied (remote): $id")
        else StatusResult(false, "Remote applyProfile failed for $id")
    }

    override fun listProfiles(): List<ProfileSummary> {
        val resp = get("/profiles") ?: return emptyList()
        val arr = json.parseToJsonElement(resp) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ProfileSummary(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    override fun listActions(profileId: String?): List<ActionDescriptor> {
        val resp = if (profileId != null) {
            get("/profiles/${escapePath(profileId)}/actions") ?: return emptyList()
        } else return emptyList()  // remote /profiles+actions cross-listing not exposed
        val obj = json.parseToJsonElement(resp) as? JsonObject ?: return emptyList()
        val pid = obj["profileId"]?.jsonPrimitive?.contentOrNull ?: profileId
        val arr = obj["actions"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ActionDescriptor(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                profileId = pid,
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    override fun executeAction(profileId: String, actionId: String): ActionToolResult {
        val resp = postJson(
            "/profiles/${escapePath(profileId)}/actions/${escapePath(actionId)}",
            """{"screenshot":false}""",
        ) ?: return ActionToolResult(false, "Remote executeAction got no response")
        val obj = json.parseToJsonElement(resp).jsonObject
        return ActionToolResult(
            ok = obj["success"]?.jsonPrimitive?.boolean ?: false,
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
            data = (obj["state"] as? JsonObject)?.mapValues {
                it.value.jsonPrimitive.contentOrNull ?: ""
            } ?: emptyMap(),
        )
    }

    override fun press(buttons: List<String>): StatusResult {
        val body = """{"buttons":[${buttons.joinToString(",") { "\"${escape(it)}\"" }}]}"""
        postJson("/press", body)
        return StatusResult(true, "press (remote): ${buttons.joinToString(",")}")
    }

    override fun release(buttons: List<String>): StatusResult {
        val body = """{"buttons":[${buttons.joinToString(",") { "\"${escape(it)}\"" }}]}"""
        if (buttons.isEmpty()) postJson("/release-all", "{}") else postJson("/release", body)
        return StatusResult(true, "release (remote)")
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────

    private fun get(path: String): String? = send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
    )

    private fun postJson(path: String, body: String): String? = send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    )

    private fun send(req: HttpRequest): String? {
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) return resp.body()
        System.err.println("[RemoteEmulatorToolset] HTTP ${resp.statusCode()} on ${req.method()} ${req.uri()}: ${resp.body().take(200)}")
        return null
    }

    private fun parseStepResult(body: String): StepResult {
        val obj = json.parseToJsonElement(body).jsonObject
        val frame = obj["frame"]?.jsonPrimitive?.intOrNull ?: 0
        val ram = (obj["ram"] as? JsonObject)?.mapValues {
            it.value.jsonPrimitive.intOrNull ?: 0
        } ?: emptyMap()
        val shot = obj["screenshot"]?.let { el ->
            if (el is JsonNull) null else (el as? JsonPrimitive)?.contentOrNull
        }
        return StepResult(
            frame = frame,
            ram = ram,
            heldButtons = emptyList(),  // not exposed by /step response
            screenshot = shot,
        )
    }

    private fun parseRomLoaded(body: String): Boolean {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return false
        val romLoaded = obj["romLoaded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        if (romLoaded == true) return true
        // Some routes return {"status":"loaded"} on success — accept that too.
        return obj["status"]?.jsonPrimitive?.contentOrNull == "loaded"
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun escapePath(s: String): String = URI(null, null, s, null).rawPath
}
