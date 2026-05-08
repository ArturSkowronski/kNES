package knes.agent.runtime

import knes.agent.perception.FrameChangeDetector
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.skills.InteriorScanner
import knes.agent.skills.WalkInteriorVision
import knes.agent.tools.EmulatorToolset

/** Trace event sink. Receives the structured note string; caller-side wraps in TraceEvent. */
typealias InteriorTraceSink = (String) -> Unit

/**
 * Adapter for the emulator state needed by InteriorExplorer.
 *
 * Concrete impl in Task 10 wraps the real toolset/RAM reader.
 * Test stubs implement directly.
 */
interface InteriorEmulatorState {
    /** Returns null when OAM API is unavailable; explorer falls back to pixel hash. */
    fun captureOam(): Set<FrameChangeDetector.SpriteSlot>?
    fun capturePixels(): ByteArray   // raw 256x240 byte buffer; empty array if unavailable
    fun captureScreenshotBase64(): String?
    fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int): String?
    fun currentMapId(): Int
    fun partyLocalX(): Int
    fun partyLocalY(): Int
}

sealed interface WalkOutcome {
    data class Stepped(val direction: String) : WalkOutcome
    data object Stuck : WalkOutcome
    data object EncounterStarted : WalkOutcome
}

/**
 * Adapter for the existing WalkInteriorVision skill.
 *
 * The existing skill is UNCHANGED per spec §1; this adapter maps its outcome
 * to [WalkOutcome] without leaking the existing API into the explorer.
 */
interface WalkInteriorVisionAdapter {
    suspend fun step(): WalkOutcome
}

class InteriorExplorer(
    private val walk: WalkInteriorVisionAdapter,
    private val scanner: InteriorScanner,
    private val frameDetector: FrameChangeDetector,
    private val emu: InteriorEmulatorState,
    private val memory: LandmarkMemory,
    private val traceSink: InteriorTraceSink = {},
) {
    sealed interface ExploreOutcome {
        data class Found(val landmark: Landmark, val stats: ExploreStats) : ExploreOutcome
        data class NotFoundCapReached(val stats: ExploreStats) : ExploreOutcome
        data class StuckBailout(val reason: String, val stats: ExploreStats) : ExploreOutcome
        data class EncounterTriggered(val stats: ExploreStats) : ExploreOutcome
    }

    data class ExploreStats(
        val walkSteps: Int,
        val scansTriggered: Int,
        val candidatesEvaluated: Int,
        val confirmed: Int,
        val rejected: Int,
        val errored: Int,
        val costUsd: Double,
    )

    /** Goal-aware exploration. */
    suspend fun exploreUntilFound(
        goal: LandmarkKind,
        predicate: (Landmark) -> Boolean,
        capSteps: Int,
    ): ExploreOutcome {
        var walkSteps = 0
        var scansTriggered = 0
        var candidatesEvaluated = 0
        var confirmedCount = 0
        var rejectedCount = 0
        var erroredCount = 0
        var costUsd = 0.0
        var consecutiveStuck = 0
        var consecutiveScanEmpty = 0

        fun stats() = ExploreStats(
            walkSteps, scansTriggered, candidatesEvaluated,
            confirmedCount, rejectedCount, erroredCount, costUsd,
        )

        while (walkSteps < capSteps) {
            val pixels = emu.capturePixels()
            val oam = emu.captureOam()

            if (frameDetector.shouldScan(oam, pixels)) {
                scansTriggered++
                traceSink(
                    "interior_scan_triggered: totalScansThisRun=$scansTriggered, " +
                        "fallback=${frameDetector.mode}",
                )
                val screenshot = emu.captureScreenshotBase64()
                val scan = scanner.scanCandidates(screenshot)
                costUsd += scan.costUsd
                traceSink(
                    "interior_scan_candidates: count=${scan.candidates.size}, " +
                        "kinds=[${scan.candidates.joinToString(",") { it.kind }}]",
                )
                if (scan.candidates.isEmpty()) {
                    consecutiveScanEmpty++
                    if (consecutiveScanEmpty >= 30) {
                        return ExploreOutcome.StuckBailout("pass1-degraded", stats())
                    }
                } else {
                    consecutiveScanEmpty = 0
                    for (cand in scan.candidates) {
                        candidatesEvaluated++
                        val focused = emu.captureFocusedCropBase64(cand.screenX, cand.screenY)
                        val pr = scanner.verifyAndPersist(
                            focused,
                            cand,
                            mapId = emu.currentMapId(),
                            partyLocalX = emu.partyLocalX(),
                            partyLocalY = emu.partyLocalY(),
                        )
                        when (pr) {
                            is InteriorScanner.PersistResult.Confirmed -> {
                                confirmedCount++
                                costUsd += pr.costUsd
                            }
                            is InteriorScanner.PersistResult.Rejected -> {
                                rejectedCount++
                                costUsd += pr.costUsd
                            }
                            is InteriorScanner.PersistResult.Errored -> {
                                erroredCount++
                                costUsd += pr.costUsd
                            }
                        }
                    }
                }
            }

            // Goal check after scan — landmark may have just been persisted.
            val foundLandmark = memory.findByKind(goal).firstOrNull(predicate)
            if (foundLandmark != null) {
                return ExploreOutcome.Found(foundLandmark, stats())
            }

            // Walk step.
            val walkOutcome = walk.step()
            walkSteps++
            when (walkOutcome) {
                is WalkOutcome.Stepped -> consecutiveStuck = 0
                is WalkOutcome.Stuck -> {
                    consecutiveStuck++
                    if (consecutiveStuck >= 30) {
                        return ExploreOutcome.StuckBailout(
                            "walk-stuck-after-${walkSteps}-steps", stats(),
                        )
                    }
                }
                is WalkOutcome.EncounterStarted ->
                    return ExploreOutcome.EncounterTriggered(stats())
            }
        }
        return ExploreOutcome.NotFoundCapReached(stats())
    }
}

/**
 * Real implementation of [InteriorEmulatorState] backed by the existing
 * [EmulatorToolset].
 *
 * NOTE on OAM (spec §10.2 open question #2): PPU OAM is **NOT** exposed by
 * either [knes.api.EmulatorSession] or [knes.emulator.ppu.PPU] — the sprite
 * arrays (`sprX/sprY/sprTile/sprCol`) and `sprMem` Memory are private fields
 * inside the PPU. Adding a public OAM accessor would require modifying
 * `knes-emulator` + `knes-emulator-session`, which is out of scope for Spec 4.
 *
 * Therefore [captureOam] returns `null` and [FrameChangeDetector] falls back
 * to the pixel-grid hash (acceptable per spec §10.2). [capturePixels] decodes
 * the screenshot PNG into a 256x240 grayscale byte buffer so the pixel hash
 * has a real signal to work with — returning ByteArray(0) would always hash
 * to 0 and silently disable change detection.
 */
class RealInteriorEmulatorState(
    private val toolset: EmulatorToolset,
) : InteriorEmulatorState {

    /**
     * OAM is not exposed by the public emulator surface (see class kdoc).
     * Returning null engages the pixel-hash fallback in FrameChangeDetector.
     */
    override fun captureOam(): Set<FrameChangeDetector.SpriteSlot>? = null

    /**
     * Decodes the current screenshot PNG into a 256x240 grayscale byte buffer
     * (one byte per pixel: average of R,G,B). Used by FrameChangeDetector's
     * pixel-hash fallback. Returns ByteArray(0) on decode failure (hash sees
     * zero — caller should treat the first call as a baseline).
     */
    override fun capturePixels(): ByteArray {
        return try {
            val pngB64 = toolset.getScreen().base64
            val pngBytes = java.util.Base64.getDecoder().decode(pngB64)
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(pngBytes))
                ?: return ByteArray(0)
            val w = img.width
            val h = img.height
            val out = ByteArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val rgb = img.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    out[y * w + x] = ((r + g + b) / 3).toByte()
                }
            }
            out
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    override fun captureScreenshotBase64(): String? {
        return try {
            toolset.getScreen().base64
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns a 32x32 px crop centered on the tile at (screenTileX, screenTileY)
     * (NES tiles are 16x16 px in FF1's overworld/town view; tile center is at
     * pixel (tileX*16+8, tileY*16+8)). Output is base64-encoded PNG.
     */
    override fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int): String? {
        val centerX = screenTileX * 16 + 8
        val centerY = screenTileY * 16 + 8
        val rawPngB64 = captureScreenshotBase64() ?: return null
        val rawPng = try {
            java.util.Base64.getDecoder().decode(rawPngB64)
        } catch (_: Throwable) {
            return null
        }
        return cropToBase64Png(rawPng, centerX, centerY, sizePx = 32)
    }

    override fun currentMapId(): Int = toolset.getState().ram["currentMapId"] ?: -1
    override fun partyLocalX(): Int = toolset.getState().ram["smPlayerX"] ?: 0
    override fun partyLocalY(): Int = toolset.getState().ram["smPlayerY"] ?: 0

    private fun cropToBase64Png(
        rawPng: ByteArray,
        centerPixelX: Int,
        centerPixelY: Int,
        sizePx: Int,
    ): String? {
        return try {
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(rawPng))
                ?: return null
            val maxX = (img.width - sizePx).coerceAtLeast(0)
            val maxY = (img.height - sizePx).coerceAtLeast(0)
            val x = (centerPixelX - sizePx / 2).coerceIn(0, maxX)
            val y = (centerPixelY - sizePx / 2).coerceIn(0, maxY)
            val w = sizePx.coerceAtMost(img.width)
            val h = sizePx.coerceAtMost(img.height)
            val sub = img.getSubimage(x, y, w, h)
            val out = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(sub, "png", out)
            java.util.Base64.getEncoder().encodeToString(out.toByteArray())
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Real adapter mapping the existing [WalkInteriorVision] skill's outcomes onto
 * [WalkOutcome]. Per spec §1, [WalkInteriorVision] itself is **unchanged**.
 *
 * Step semantics: [WalkInteriorVision] is a multi-step walk-to-exit skill, not
 * a per-step API. We invoke it with `maxSteps=1` so it performs at most one
 * tile of movement per [step] call, then we map the resulting [SkillResult]:
 *
 *  - `ok=true` + message contains `"encounter"` → [WalkOutcome.EncounterStarted]
 *  - `ok=true` + message starts with `"exited"`  → [WalkOutcome.Stuck] (we've
 *    left the interior; goal-aware explorer should bail to caller. Mapping
 *    this to Stuck triggers the consecutive-stuck bailout naturally.)
 *  - `ok=false` (vision STUCK/UNCLEAR or maxSteps reached without movement) →
 *    detect movement by comparing RAM smPlayerX/Y before vs after; if moved
 *    → [WalkOutcome.Stepped], else → [WalkOutcome.Stuck].
 *
 * Direction is reported as best-effort by inspecting the RAM delta; if the
 * party didn't move (stuck) or transitioned out, direction is "unknown".
 */
class RealWalkInteriorVisionAdapter(
    private val skill: WalkInteriorVision,
    private val toolset: EmulatorToolset,
) : WalkInteriorVisionAdapter {

    override suspend fun step(): WalkOutcome {
        val ramPre = toolset.getState().ram
        val xPre = ramPre["smPlayerX"] ?: 0
        val yPre = ramPre["smPlayerY"] ?: 0

        val result = skill.invoke(mapOf("maxSteps" to "1"))
        val msg = result.message.lowercase()

        if (result.ok && msg.contains("encounter")) {
            return WalkOutcome.EncounterStarted
        }
        if (result.ok && msg.startsWith("exited")) {
            // Left the interior — for goal-aware exploration this means we're
            // done with this map. Map to Stuck so consecutive-stuck logic in
            // InteriorExplorer bails to the caller.
            return WalkOutcome.Stuck
        }

        val ramPost = result.ramAfter.takeIf { it.isNotEmpty() } ?: toolset.getState().ram
        val xPost = ramPost["smPlayerX"] ?: xPre
        val yPost = ramPost["smPlayerY"] ?: yPre
        val dx = xPost - xPre
        val dy = yPost - yPre

        if (dx == 0 && dy == 0) {
            return WalkOutcome.Stuck
        }
        val direction = when {
            dy < 0 -> "NORTH"
            dy > 0 -> "SOUTH"
            dx < 0 -> "WEST"
            dx > 0 -> "EAST"
            else -> "unknown"
        }
        return WalkOutcome.Stepped(direction)
    }
}
