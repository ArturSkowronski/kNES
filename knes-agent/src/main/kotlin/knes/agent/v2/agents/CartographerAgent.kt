package knes.agent.v2.agents

import knes.agent.perception.FogOfWar
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldMap
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.runtime.SnapshotDumper
import knes.agent.v2.runtime.V2Memory

/**
 * Online vision exploration phase. Pushes party around spawn using vision-LLM
 * cardinal hints to fill fog and identify landmarks (innkeeper / weapon shop /
 * armor shop in Coneria).
 *
 * Budget: time + vision-call cap from V2Config. If exceeded, falls back to
 * static ROM-decoder map only.
 *
 * IMPORTANT: vision prompts MUST enforce locate-party → locate-target →
 * derive-direction (per repo's feedback_locate_party_first.md).
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
) {
    suspend fun exploreInitialOverworld() {
        val started = System.currentTimeMillis()
        var visionCalls = 0
        var turnCounter = 0   // Cartographer turns (not yet entered campaign loop)

        System.err.println("[v2.cartographer] start: budget=${budgetSeconds}s maxCalls=$maxVisionCalls")

        while (true) {
            val elapsed = (System.currentTimeMillis() - started) / 1000
            if (elapsed > budgetSeconds) { System.err.println("[v2.cartographer] time budget exceeded ($elapsed s)"); break }
            if (visionCalls >= maxVisionCalls) { System.err.println("[v2.cartographer] vision call cap reached"); break }

            val ram = toolset.getState().ram
            val worldX = ram["worldX"] ?: 0
            val worldY = ram["worldY"] ?: 0

            // Fog merge with current viewport (cheap, no LLM).
            val viewport = overworldMap.readFullMapView(worldX to worldY)
            fog.merge(viewport)

            // Stop condition: all reachable adjacent tiles known.
            if (fog.allReachableKnown(worldX to worldY)) {
                System.err.println("[v2.cartographer] frontier exhausted at ($worldX,$worldY)")
                break
            }

            // Ask Gemini for next direction. Enforce locate-party-first contract.
            val snap = toolset.getScreen().base64
            val direction = askGeminiNextDirection(snap, worldX, worldY)
            visionCalls++
            when (direction.uppercase()) {
                "N" -> toolset.tap("UP", 1)
                "S" -> toolset.tap("DOWN", 1)
                "E" -> toolset.tap("RIGHT", 1)
                "W" -> toolset.tap("LEFT", 1)
                "DONE" -> break
                else -> System.err.println("[v2.cartographer] unexpected dir: $direction")
            }

            turnCounter++
            snapshotDumper.dump(-turnCounter)  // negative turns reserved for pre-campaign Cartographer iters
        }

        // Landmark scans: weapon/armor/inn discovery in Coneria.
        // For first cut, defer to v1 DiscoverShop/DiscoverInn skills wired in Main.
        // This method just builds overworld fog. Indoor scans happen lazily during
        // first Executor visit to each building.

        System.err.println("[v2.cartographer] done: ${visionCalls} vision calls, ${turnCounter} steps")
    }

    suspend fun targetedRepass(flags: List<String>) {
        // Triggered by Reviewer when ≥3 inconsistencies flagged. For each flag,
        // walk to the flagged tile and re-classify with vision.
        System.err.println("[v2.cartographer] targetedRepass: ${flags.size} flags (stub)")
    }

    private suspend fun askGeminiNextDirection(snap: String, x: Int, y: Int): String {
        val prompt = """
            You are looking at the FF1 NES overworld viewport.
            Step 1: LOCATE the party sprite on the screen — DO NOT use prior knowledge of FF1 geography.
            Step 2: From that visual position, identify which cardinal direction (N/S/E/W) leads to the most UNEXPLORED area.
            Step 3: Return that direction.

            Party world-coords: ($x, $y).
            Return ONLY the letter: N or S or E or W, or DONE if no unexplored frontier.
        """.trimIndent()
        return gemini.generate(prompt, imageB64 = snap).trim().take(4)
    }
}
