package knes.agent.v2.agents

import knes.agent.perception.FogOfWar
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldMap
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.runtime.SnapshotDumper
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory

/**
 * Online vision exploration phase. Pushes party around spawn using vision-LLM
 * cardinal hints to fill fog and identify landmarks (innkeeper / weapon shop /
 * armor shop in Coneria).
 *
 * Budget: time + vision-call cap from V2Config. If exceeded, falls back to
 * static ROM-decoder map only.
 *
 * Vision prompts MUST enforce locate-party → locate-target → derive-direction
 * (per repo's feedback_locate_party_first.md).
 */
class CartographerAgent(
    private val gemini: GeminiPro31Client,
    private val toolset: EmulatorToolset,
    private val memory: V2Memory,
    private val snapshotDumper: SnapshotDumper,
    private val overworldMap: OverworldMap,
    private val fog: FogOfWar,
    private val landmarks: LandmarkMemory,
    private val budgetSeconds: Int,
    private val maxVisionCalls: Int,
    private val run: V2RunDirectory? = null,
) {
    suspend fun exploreInitialOverworld() {
        val started = System.currentTimeMillis()
        var visionCalls = 0
        var turnCounter = 0

        run?.markActive("cartographer", 0)
        knes.agent.v2.runtime.Log.cartographer("start: budget=${budgetSeconds}s maxCalls=$maxVisionCalls")

        while (true) {
            val elapsed = (System.currentTimeMillis() - started) / 1000
            if (elapsed > budgetSeconds) { knes.agent.v2.runtime.Log.cartographer("time budget exceeded (${elapsed}s)"); break }
            if (visionCalls >= maxVisionCalls) { knes.agent.v2.runtime.Log.cartographer("vision call cap reached"); break }

            val ram = toolset.getState().ram
            val worldX = ram["worldX"] ?: 0
            val worldY = ram["worldY"] ?: 0

            val viewport = overworldMap.readFullMapView(worldX to worldY)
            fog.merge(viewport)

            if (fog.allReachableKnown(worldX to worldY)) {
                knes.agent.v2.runtime.Log.cartographer("frontier exhausted at ($worldX,$worldY)")
                break
            }

            val snap = toolset.getScreen().base64
            val direction = askGeminiNextDirection(snap, worldX, worldY)
            visionCalls++
            when (direction.uppercase()) {
                "N" -> toolset.tap("UP", 1)
                "S" -> toolset.tap("DOWN", 1)
                "E" -> toolset.tap("RIGHT", 1)
                "W" -> toolset.tap("LEFT", 1)
                "DONE" -> break
                else -> knes.agent.v2.runtime.Log.warn("cartographer unexpected dir: $direction")
            }

            turnCounter++
            // Pre-campaign iters write to a separate prefix so the cosmetic
            // negative-turn format never lands in the campaign decisions/
            // dir naming.
            snapshotDumper.dumpCartographer(turnCounter)
        }

        // Landmark scans (weapon/armor/inn) happen lazily during first Executor
        // visit to each building — keeps Cartographer scope minimal.
        knes.agent.v2.runtime.Log.cartographer("done: $visionCalls vision calls, $turnCounter steps")
        run?.markIdle()
    }

    suspend fun targetedRepass(flags: List<String>) {
        knes.agent.v2.runtime.Log.cartographer("targetedRepass: ${flags.size} flags (stub)")
    }

    private var cartIter: Int = 0
    private suspend fun askGeminiNextDirection(snap: String, x: Int, y: Int): String {
        cartIter++
        val prompt = """
            You are looking at the FF1 NES overworld viewport.
            Step 1: LOCATE the party sprite on the screen — DO NOT use prior knowledge of FF1 geography.
            Step 2: From that visual position, identify which cardinal direction (N/S/E/W) leads to the most UNEXPLORED area.
            Step 3: Return that direction.

            Party world-coords: ($x, $y).
            Return ONLY the letter: N or S or E or W, or DONE if no unexplored frontier.
        """.trimIndent()
        val raw = gemini.generate(prompt, imageB64 = snap).trim()
        runCatching {
            run?.promptFile(0, "cart-%02d".format(cartIter))?.toFile()?.writeText(
                "=== CARTOGRAPHER iter $cartIter — Gemini Pro 3.1 vision ===\n" +
                "world=($x,$y)\n\n=== PROMPT ===\n$prompt\n\n=== RESPONSE ===\n$raw"
            )
        }
        return raw.take(4)
    }
}
