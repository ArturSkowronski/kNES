package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File
import java.util.Base64

/**
 * V5.1 — visual diff: live Coneria Town frame vs offline-decoded mapId=$targetMapId.
 *
 * Goal: identify the fingerprint of the InteriorMapLoader.load(8) bug.
 * Off-by-one? Wrong bank? Completely wrong ROM section?
 *
 * Procedure:
 *   1. Boot ROM, walk into Coneria area until mapId=$targetMapId appears in RAM.
 *   2. Capture screenshot of the live frame (Coneria Town as the engine
 *      actually renders it).
 *   3. Read RAM party position (localX, localY).
 *   4. Render the offline decoder's mapId=$targetMapId ASCII glyph dump at the same
 *      party-centred 16x15 viewport.
 *   5. Save both to docs/superpowers/notes/coneria8-diff-2026-05-03/.
 *
 * Manual review compares the screenshot against the ASCII window. Mismatch
 * fingerprint → diagnosis path:
 *   - "ASCII looks like a castle, screenshot is a town" → wrong map data
 *     (loader resolves to a different mapId entirely; off-by-one in pointer
 *     table or wrong bank).
 *   - "ASCII matches town shape but tiles offset N rows/columns" → coord
 *     transform missing.
 *   - "ASCII matches" → bug is somewhere else (collision tables?
 *     viewport-vs-full-map mismatch?).
 */
class Coneria8VisualDiffTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("capture Coneria Town live frame + decoded mapId=8 ASCII for visual diff")
        .config(enabled = false && canRun, timeout = kotlin.time.Duration.parse("2m")) {

        val repoRoot = File(".").canonicalFile.let { if (it.name == "knes-agent") it.parentFile else it }
        val outDir = File(repoRoot, "docs/superpowers/notes/coneria8-diff-2026-05-03").also { it.mkdirs() }

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        check(PressStartUntilOverworld(toolset).invoke().ok)

        // Reach mapId=$targetMapId via the V2.6.5-known route. From spawn (146, 158):
        // walk roughly NW around mountain blocks, ending up on the south
        // side of Coneria Town's invisible entry trigger, then step S to
        // transition. Empirically tries cardinal sequences until mapId=$targetMapId
        // is reached.
        suspend fun tryReachMap8(): Boolean {
            val seq = listOf(
                "UP", "UP", "UP", "UP", "UP", "UP",      // approach (146,152)
                "LEFT", "LEFT",                            // approach (144,152)
                "DOWN", "DOWN", "DOWN", "DOWN",            // try entering town
                "LEFT", "LEFT", "DOWN", "DOWN",
                "RIGHT", "DOWN", "DOWN",
                "RIGHT", "RIGHT", "DOWN",
            )
            for (dir in seq) {
                toolset.step(buttons = listOf(dir), frames = 24)
                val ram = toolset.getState().ram
                val mid = ram["currentMapId"] ?: 0
                val locType = ram["locationType"] ?: 0
                if (mid == 8 && locType != 0) return true
                // If we're indoors but not in mapId=$targetMapId, try walking south
                // to exit, then continue the sequence.
                if (locType != 0 && mid != 8) {
                    repeat(15) { toolset.step(buttons = listOf("DOWN"), frames = 16) }
                }
            }
            return false
        }
        var reachedTown = tryReachMap8()
        if (!reachedTown) {
            // Fallback: random cardinal walk for up to 200 attempts.
            val cardinals = listOf("UP", "DOWN", "LEFT", "RIGHT")
            for (i in 0 until 200) {
                toolset.step(buttons = listOf(cardinals[i % 4]), frames = 24)
                val ram = toolset.getState().ram
                if ((ram["currentMapId"] ?: 0) == 8 && (ram["locationType"] ?: 0) != 0) {
                    reachedTown = true
                    break
                }
            }
        }
        if (reachedTown) {
            val ram = toolset.getState().ram
            println("[diff] reached mapId=8 at " +
                "world=(${ram["worldX"]},${ram["worldY"]}) " +
                "local=(${ram["localX"]},${ram["localY"]})")
        } else {
            val ram = toolset.getState().ram
            val mid = ram["currentMapId"] ?: 0
            val locType = ram["locationType"] ?: 0
            println("[diff] could not reach mapId=8; current interior: " +
                "mapId=$mid locType=0x${locType.toString(16)} — dumping anyway for diff")
            check(locType != 0) { "agent is on overworld; no interior to diff" }
        }

        // Capture live screenshot
        val ram = toolset.getState().ram
        val partyLx = ram["localX"] ?: 0
        val partyLy = ram["localY"] ?: 0
        val pngBytes = Base64.getDecoder().decode(toolset.getScreen().base64)
        File(outDir, "live-frame.png").writeBytes(pngBytes)
        File(outDir, "live-frame.ram.txt").writeText(buildString {
            appendLine("Party RAM at capture:")
            appendLine("  worldX=${ram["worldX"]}  worldY=${ram["worldY"]}")
            appendLine("  localX=$partyLx  localY=$partyLy")
            appendLine("  mapId=${ram["currentMapId"]}  locType=0x${(ram["locationType"]?:0).toString(16)}")
            appendLine("  scrolling=${ram["scrolling"]}  screenState=0x${(ram["screenState"]?:0).toString(16)}")
        })

        // Decode the current mapId and render glyph dump centred on party position.
        val rom = File(romPath).readBytes()
        val targetMapId = ram["currentMapId"] ?: 0
        val map = InteriorMapLoader(rom).load(targetMapId)
        // 16x15 viewport (NES screen size in tiles), centred on party
        val viewW = 16
        val viewH = 15
        val originX = (partyLx - viewW / 2).coerceAtLeast(0)
        val originY = (partyLy - viewH / 2).coerceAtLeast(0)

        val ascii = StringBuilder()
        ascii.appendLine("=== Decoded mapId=$targetMapId ASCII viewport (16x15, party @ centre) ===")
        ascii.appendLine("Party RAM: localX=$partyLx, localY=$partyLy")
        ascii.appendLine("Viewport origin: ($originX, $originY)")
        ascii.appendLine()
        ascii.append("       ")
        for (x in 0 until viewW) ascii.append((originX + x) % 10)
        ascii.appendLine()
        for (y in 0 until viewH) {
            val mapY = originY + y
            ascii.append("y=${mapY.toString().padStart(2)}: ")
            for (x in 0 until viewW) {
                val mapX = originX + x
                val isParty = (mapX == partyLx && mapY == partyLy)
                val tile = map.classifyAt(mapX, mapY)
                ascii.append(if (isParty) '@' else tile.glyph)
            }
            ascii.appendLine()
        }

        // Also include raw bytes for the same window (for hex-pattern matching)
        ascii.appendLine()
        ascii.appendLine("=== Raw byte dump (same window) ===")
        ascii.append("       ")
        for (x in 0 until viewW) ascii.append("%2d ".format((originX + x) % 100))
        ascii.appendLine()
        for (y in 0 until viewH) {
            val mapY = originY + y
            ascii.append("y=${mapY.toString().padStart(2)}: ")
            for (x in 0 until viewW) {
                val mapX = originX + x
                ascii.append("%02x ".format(map.tileAt(mapX, mapY)))
            }
            ascii.appendLine()
        }
        File(outDir, "decoded-mapid-viewport.txt").writeText(ascii.toString())

        // Also dump full mapId=$targetMapId for reference
        val full = StringBuilder()
        full.appendLine("=== mapId=$targetMapId FULL 64x64 glyph dump ===")
        full.append("       ")
        for (x in 0 until 64) full.append(x % 10)
        full.appendLine()
        for (y in 0 until 64) {
            full.append("y=${y.toString().padStart(2)}: ")
            for (x in 0 until 64) {
                val isParty = (x == partyLx && y == partyLy)
                full.append(if (isParty) '@' else map.classifyAt(x, y).glyph)
            }
            full.appendLine()
        }
        File(outDir, "decoded-mapid-full.txt").writeText(full.toString())

        println("[diff] saved live frame + ASCII viewport + full 64x64 dump → ${outDir.absolutePath}")
    }
})
