package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/**
 * V3.0 Task 0 — feasibility probe.
 *
 * Boots the FF1 ROM, walks into an interior, captures three screenshots from
 * different positions and asks Sonnet 4.6 vision to pick a direction. Saves
 * shots + responses to docs/superpowers/notes/2026-05-03-vision-interior-feasibility.md
 * for manual GO / NO-GO verdict before any production code lands.
 */
class VisionInteriorFeasibilityTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val apiKey = System.getenv("ANTHROPIC_API_KEY") ?: ""
    val canRun = File(romPath).exists() && apiKey.isNotBlank()

    // V3.0 Task 0 — feasibility probe. Disabled by default so the full test run
    // doesn't repeatedly hit the Anthropic API. Re-enable manually to re-probe.
    test("Sonnet 4.6 picks plausible directions on three FF1 interior frames")
        .config(enabled = false && canRun, timeout = kotlin.time.Duration.parse("3m")) {

        // Test JVM cwd is the knes-agent subproject; resolve doc paths against repo root.
        val repoRoot = File(".").canonicalFile.let { if (it.name == "knes-agent") it.parentFile else it }
        val outDir = File(repoRoot, "docs/superpowers/notes/feasibility-shots-2026-05-03")
            .also { it.mkdirs() }

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(romPath).ok shouldBe true
        toolset.applyProfile("ff1").ok shouldBe true

        // 1) Boot to overworld
        val boot = PressStartUntilOverworld(toolset).invoke()
        check(boot.ok) { "press-start-until-overworld failed: ${boot.message}" }
        val ramOw = toolset.getState().ram
        println("[probe] spawn world=(${ramOw["worldX"]},${ramOw["worldY"]}) " +
            "loc=0x${(ramOw["locationType"] ?: 0).toString(16)}")

        // 2) Walk into ANY interior. Spawn is south of Coneria Castle; tap UP
        //    until locationType signals interior or 40 attempts pass.
        var enteredInterior = (ramOw["locationType"] ?: 0) != 0
        for (attempt in 0 until 40) {
            if (enteredInterior) break
            toolset.step(buttons = listOf("UP"), frames = 24)
            val ram = toolset.getState().ram
            if ((ram["locationType"] ?: 0) != 0) {
                enteredInterior = true
                println("[probe] entered interior after $attempt UP-steps " +
                    "(world=(${ram["worldX"]},${ram["worldY"]}) " +
                    "local=(${ram["localX"]},${ram["localY"]}) " +
                    "mapId=${ram["currentMapId"]})")
            }
        }
        // Fallback: try other cardinals.
        if (!enteredInterior) {
            val dirs = listOf("DOWN", "LEFT", "RIGHT", "UP")
            for (i in 0 until 60) {
                toolset.step(buttons = listOf(dirs[i % 4]), frames = 24)
                val ram = toolset.getState().ram
                if ((ram["locationType"] ?: 0) != 0) {
                    enteredInterior = true
                    println("[probe] entered interior after fallback walk")
                    break
                }
            }
        }
        check(enteredInterior) { "could not enter any interior in 100 attempts" }

        val client = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 60_000 }
        }
        val notesPath = File(repoRoot, "docs/superpowers/notes/2026-05-03-vision-interior-feasibility.md")
        val sb = StringBuilder()
        sb.appendLine("# V3.0 vision-feasibility probe — 2026-05-03")
        sb.appendLine()
        sb.appendLine("Three FF1 interior screenshots, each sent to **claude-sonnet-4-6** with the V3.0")
        sb.appendLine("navigator system prompt. Goal: confirm vision can pick a plausible direction")
        sb.appendLine("toward an exit on FF1 8-bit sprite art before we build the full skill stack.")
        sb.appendLine()

        val nudges = listOf("UP", "RIGHT", "DOWN")
        try {
            for (i in 1..3) {
                val ram = toolset.getState().ram
                val b64 = toolset.getScreen().base64
                val pngBytes = Base64.getDecoder().decode(b64)
                val shotFile = File(outDir, "shot-$i.png")
                shotFile.writeBytes(pngBytes)
                val response = callAnthropicVision(client, apiKey, b64)
                println("--- Shot $i (lx=${ram["localX"]}, ly=${ram["localY"]}, " +
                    "mapId=${ram["currentMapId"]}, locType=0x${(ram["locationType"]?:0).toString(16)}) ---")
                println(response)

                sb.appendLine("## Shot $i")
                sb.appendLine()
                sb.appendLine("- RAM: localX=${ram["localX"]}, localY=${ram["localY"]}, " +
                    "currentMapId=${ram["currentMapId"]}, " +
                    "locationType=0x${(ram["locationType"]?:0).toString(16)}")
                sb.appendLine("- Image: ![shot-$i](feasibility-shots-2026-05-03/shot-$i.png)")
                sb.appendLine()
                sb.appendLine("Response from Sonnet 4.6:")
                sb.appendLine("```")
                sb.appendLine(response.trim())
                sb.appendLine("```")
                sb.appendLine()

                // Nudge the party so the next screenshot is from a different position.
                if (i < 3) {
                    toolset.step(buttons = listOf(nudges[i - 1]), frames = 48)
                }
            }
        } finally {
            client.close()
        }

        sb.appendLine("## Verdict")
        sb.appendLine()
        sb.appendLine("Fill in after manually reviewing shots + responses:")
        sb.appendLine()
        sb.appendLine("- [ ] **GO** — vision picks plausible directions; proceed to Task 1.")
        sb.appendLine("- [ ] **NO-GO** — vision is unreliable on FF1 sprite art; pivot to hybrid C")
        sb.appendLine("  (advisor screenshots only, decoder remains for executor).")
        sb.appendLine("- [ ] **UNCERTAIN** — iterate the navigator system prompt 1–2 times.")
        sb.appendLine()
        notesPath.parentFile.mkdirs()
        notesPath.writeText(sb.toString())
        println("[probe] notes written → ${notesPath.absolutePath}")
        println("[probe] shots in → ${outDir.absolutePath}")
    }
})

private val json = Json { ignoreUnknownKeys = true }

private const val NAVIGATOR_SYSTEM_PROMPT =
    "You are a navigator playing Final Fantasy 1 (NES) inside a town/castle/dungeon. " +
        "The party stands at the centre of the screen (around tile column 8, row 7). " +
        "Your job: pick ONE direction (N/S/E/W) that moves the party toward the nearest exit " +
        "— a door, staircase, opening at the south edge, or any clear corridor leading off the visible area. " +
        "Avoid: walls, water, shop counters, locked rooms, NPCs blocking the path. " +
        "If the screen shows the overworld (top-down terrain map with no walls), return EXIT. " +
        "If you cannot identify any clear walkable direction, return STUCK. " +
        "Output ONLY JSON: {\"direction\":\"N|S|E|W|EXIT|STUCK\",\"reason\":\"<<=80 chars\"}."

private const val NAVIGATOR_USER_PROMPT =
    "Pick the next direction for the party. Return JSON only."

private suspend fun callAnthropicVision(client: HttpClient, apiKey: String, b64: String): String {
    val body = buildJsonObject {
        put("model", "claude-sonnet-4-6")
        put("max_tokens", 200)
        put("system", NAVIGATOR_SYSTEM_PROMPT)
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
                        put("text", NAVIGATOR_USER_PROMPT)
                    })
                })
            })
        })
    }.toString()

    val resp = client.post("https://api.anthropic.com/v1/messages") {
        header("x-api-key", apiKey)
        header("anthropic-version", "2023-06-01")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    val text = resp.bodyAsText()
    if (!resp.status.isSuccess()) return "HTTP ${resp.status.value}: ${text.take(400)}"
    return try {
        val root = json.parseToJsonElement(text).jsonObject
        val content = root["content"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return "PARSE_ERROR (no content): ${text.take(200)}"
        content["text"]?.jsonPrimitive?.content ?: "PARSE_ERROR (no text): ${text.take(200)}"
    } catch (e: Exception) {
        "PARSE_ERROR: ${e.message} :: ${text.take(200)}"
    }
}
