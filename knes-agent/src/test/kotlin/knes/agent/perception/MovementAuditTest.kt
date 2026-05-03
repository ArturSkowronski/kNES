package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * V5 slice 1 — movement primitive audit.
 *
 * Captures per-frame RAM during a long button hold inside an interior to
 * understand exactly how FF1 reports the party's position. Disabled by
 * default; flip `enabled = false &&` to run with a present ROM.
 *
 * Question: when the agent taps DOWN for 48 frames inside Coneria Town,
 * does `localY` (RAM 0x002A) increment once cleanly, oscillate, jump
 * by multiple tiles, or do something else? V2.6.4 hypothesised it is
 * a scroll offset; V2.6.5 saw direction-coord inconsistencies. This test
 * captures the ground truth so we stop guessing.
 */
class MovementAuditTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("per-frame RAM dump during sustained DOWN press inside Coneria Town")
        .config(enabled = false && canRun, timeout = kotlin.time.Duration.parse("2m")) {

        val repoRoot = File(".").canonicalFile.let { if (it.name == "knes-agent") it.parentFile else it }
        val outDir = File(repoRoot, "docs/superpowers/notes/movement-audit-2026-05-03").also { it.mkdirs() }

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")

        // Boot to overworld
        check(PressStartUntilOverworld(toolset).invoke().ok)
        val ram0 = toolset.getState().ram
        println("[audit] spawn world=(${ram0["worldX"]},${ram0["worldY"]})")

        // Walk UP into Coneria region until we hit Indoors (locType != 0).
        var entered = false
        for (i in 0 until 50) {
            toolset.step(buttons = listOf("UP"), frames = 24)
            if ((toolset.getState().ram["locationType"] ?: 0) != 0) { entered = true; break }
        }
        check(entered) { "could not enter interior in 50 UP-steps" }
        val rin = toolset.getState().ram
        println("[audit] entered interior: world=(${rin["worldX"]},${rin["worldY"]}) " +
            "local=(${rin["localX"]},${rin["localY"]}) mapId=${rin["currentMapId"]} " +
            "locType=0x${(rin["locationType"]?:0).toString(16)}")

        // Now: hold DOWN for 200 frames stepping ONE FRAME AT A TIME,
        // capturing per-frame RAM. We want to see (a) does locX/Y change,
        // (b) when, (c) does it match an expected one-tile-per-N-frames cadence.
        val sb = StringBuilder()
        sb.appendLine("frame,locType,mapId,worldX,worldY,localX,localY,scrolling,screenState,heldButtons")
        var lastSnap = ""
        val transitions = mutableListOf<Pair<Int, String>>()
        for (f in 0 until 200) {
            // Single-frame step holding DOWN.
            toolset.step(buttons = listOf("DOWN"), frames = 1)
            val ram = toolset.getState().ram
            val held = toolset.getState().heldButtons.joinToString("|")
            val snap = "${ram["locationType"]},${ram["currentMapId"]},${ram["worldX"]},${ram["worldY"]}," +
                "${ram["localX"]},${ram["localY"]},${ram["scrolling"]},${ram["screenState"]},$held"
            sb.appendLine("$f,$snap")
            if (snap != lastSnap) {
                if (lastSnap.isNotEmpty()) transitions += f to snap
                lastSnap = snap
            }
        }
        File(outDir, "down-hold-200frames.csv").writeText(sb.toString())
        println("[audit] CSV → ${outDir.absolutePath}/down-hold-200frames.csv")

        // Print a summary of changes.
        println("\n[audit] state transitions (frame → loc/map/wX/wY/lX/lY/scrolling/screen/held):")
        for ((f, snap) in transitions.take(40)) {
            println("  f=$f  $snap")
        }
        println("...total transitions: ${transitions.size}")

        // Then UP 200 frames
        val sb2 = StringBuilder()
        sb2.appendLine("frame,locType,mapId,worldX,worldY,localX,localY,scrolling,screenState,heldButtons")
        for (f in 0 until 200) {
            toolset.step(buttons = listOf("UP"), frames = 1)
            val ram = toolset.getState().ram
            val held = toolset.getState().heldButtons.joinToString("|")
            sb2.appendLine("$f,${ram["locationType"]},${ram["currentMapId"]}," +
                "${ram["worldX"]},${ram["worldY"]},${ram["localX"]},${ram["localY"]}," +
                "${ram["scrolling"]},${ram["screenState"]},$held")
        }
        File(outDir, "up-hold-200frames.csv").writeText(sb2.toString())
        println("[audit] CSV → ${outDir.absolutePath}/up-hold-200frames.csv")

        // Capture a screenshot at end for visual reference
        val pngB64 = toolset.getScreen().base64
        File(outDir, "after-up.png").writeBytes(java.util.Base64.getDecoder().decode(pngB64))
        println("[audit] post-UP screenshot saved.")
    }
})
