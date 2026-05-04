package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.runtime.AgentSession
import knes.agent.runtime.Budget
import knes.agent.runtime.Outcome
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * A1: drive the production ExecutorAgent to step into Coneria Town (mapId=8),
 * snapshot emulator state at the entry moment, persist as a reusable test fixture.
 *
 * Why: the visual diff test (Coneria8VisualDiffTest) needs the engine to be
 * inside mapId=8 to dump the live frame for comparison against the offline
 * decoder. Hand-coded navigation has been brittle (4 sessions burned). The
 * production agent has its own skills (walkOverworldTo + decoder + advisor)
 * — let it try, and capture the moment it succeeds.
 *
 * Two outcomes:
 *  - SUCCESS: agent reaches mapId=8 within budget → fixture saved → other
 *    tests can load it instantly. Bonus: validates the agent is capable of
 *    that goal (against current V3.0 status memory's 13% step-success claim).
 *  - FAILURE: budget exhausted before mapId=8 → fixture NOT saved, but the
 *    Trace logs in ~/.knes/runs/<ts>/ tell us where the agent got stuck.
 *    That trace is itself the answer to "does the production agent navigate
 *    interiors reliably?"
 *
 * Disabled by default (LLM cost). Run manually with REBUILD_FIXTURE=true.
 */
class ConeriaTownFixtureBuilderTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()
    val rebuild = System.getProperty("rebuildFixture") == "true" ||
        System.getenv("REBUILD_FIXTURE") == "true"
    val apiKey = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }

    test("drive ExecutorAgent until mapId=8 entered, snapshot fixture")
        .config(enabled = canRun && rebuild && apiKey != null,
            timeout = kotlin.time.Duration.parse("10m")) {

        AnthropicSession(apiKey!!).use { anthropic ->
            val session = EmulatorSession()
            val toolset = EmulatorToolset(session)
            check(toolset.loadRom(romPath).ok)
            check(toolset.applyProfile("ff1").ok)

            // Start from post-boot fixture if present (skips ~10s of title-screen mashing).
            val postBootFixture = File("src/test/resources/fixtures/ff1-post-boot.savestate")
            if (postBootFixture.exists()) {
                check(session.loadState(postBootFixture.readBytes()))
                toolset.step(buttons = emptyList(), frames = 1)
                println("[fixture-builder] resumed from post-boot fixture")
            } else {
                println("[fixture-builder] post-boot fixture missing — agent will replay title screen")
            }

            val router = ModelRouter()
            val overworldMap = OverworldMap.fromRom(File(romPath))
            val fog = FogOfWar()
            val mapSession = MapSession(InteriorMapLoader(File(romPath).readBytes()), fog)
            val visionClassifier = AnthropicVisionPhaseClassifier(apiKey = apiKey)
            val visionInteriorNavigator = AnthropicVisionInteriorNavigator(apiKey = apiKey)
            val observer = RamObserver(toolset, overworldMap, vision = visionClassifier)
            val toolCallLog = knes.agent.runtime.ToolCallLog()
            // Advisor goal MUST also be overridden — the executor follows the advisor's
            // plan, and the production advisor steers toward Chaos Shrine. Use the same
            // goal text but indented to match advisor prompt's column-2 bullet shape.
            val advisorGoalText = "  - Goal: enter Coneria Town interior (currentMapId == 8 && locationType != 0).\n" +
                "    Coneria Town footprint is around (worldX 150-153, worldY 160-161), south-east of\n" +
                "    spawn (146, 158). Per V2.5 advisor knowledge: Coneria Town entries are around\n" +
                "    (151, 162). Plan: 1) walkOverworldTo to one TOWN candidate (try (151, 162) first,\n" +
                "    then (152, 161), then (153, 161)); 2) check RAM — if locationType still 0, the\n" +
                "    target was decoration; 3) step UP 3-4 tiles to leave the footprint, then try a\n" +
                "    different TOWN candidate. The runtime stops the loop the moment locationType != 0\n" +
                "    && currentMapId == 8."
            val advisor = AdvisorAgent(anthropic, router, toolset,
                viewportSource = overworldMap, interiorSource = mapSession, fog = fog,
                goalOverride = advisorGoalText)
            // Goal override: tell the agent we want Coneria Town, not Garland.
            // Includes V5.2 empirical knowledge from this session: ROM-scan TOWN
            // candidates clustered at y=160-161, x=150-153; some are decoration
            // (locType stays 0 when stepped on), entry tile is among them but not
            // identifiable offline. Agent must try multiple targets and re-aim
            // when one fails. Hard-impassable rule means each candidate must be
            // passed as the explicit BFS target.
            val coneriaGoal = "- Goal: enter Coneria Town interior (currentMapId == 8 && locationType != 0).\n" +
                "  Coneria Town footprint is on the overworld around (worldX 150-153, worldY 160-161),\n" +
                "  south-east of spawn (146, 158).\n" +
                "  STRATEGY:\n" +
                "  1. Use walkOverworldTo(targetX, targetY) with one of these TOWN-tile candidates:\n" +
                "     (151, 161), (152, 161), (153, 161), (152, 160), (153, 160).\n" +
                "  2. After BFS reaches the target, observe RAM. If locationType != 0 → DONE.\n" +
                "     If locationType is still 0, you landed on a decoration tile, not the entry.\n" +
                "  3. To retry: step UP 3-4 tiles via toolset action to leave the town footprint,\n" +
                "     then call walkOverworldTo with a DIFFERENT candidate from the list.\n" +
                "  4. V2.5.4 rule: TOWN tiles are hard-impassable for BFS UNLESS passed as the\n" +
                "     explicit target. You MUST target a TOWN tile directly.\n" +
                "  5. When locationType != 0 && currentMapId == 8 the runtime stops the loop."
            val executor = ExecutorAgent(anthropic, router, toolset, advisor,
                overworldMap, mapSession, fog, toolCallLog, visionInteriorNavigator,
                goalOverride = coneriaGoal)

            // Hook: stop & snapshot the moment mapId=8 with locType!=0.
            var entered = false
            val onTurnEnd: (FfPhase, Map<String, Int>) -> Outcome? = { _, ram ->
                val mapId = ram["currentMapId"] ?: 0
                val locType = ram["locationType"] ?: 0
                if (mapId == 8 && locType != 0) {
                    entered = true
                    val snapshot = session.saveState()
                    val fixture = File("src/test/resources/fixtures/ff1-coneria-town.savestate")
                    fixture.parentFile.mkdirs()
                    fixture.writeBytes(snapshot)
                    val desc = File("src/test/resources/fixtures/ff1-coneria-town.json")
                    desc.writeText("""
                        {
                          "description": "FF1 inside Coneria Town (mapId=8) — captured by ExecutorAgent",
                          "savestate_bytes": ${snapshot.size},
                          "locationType": $locType,
                          "currentMapId": $mapId,
                          "localX": ${ram["localX"] ?: 0},
                          "localY": ${ram["localY"] ?: 0},
                          "worldX_at_entry": ${ram["worldX"] ?: 0},
                          "worldY_at_entry": ${ram["worldY"] ?: 0}
                        }
                    """.trimIndent())
                    File("src/test/resources/fixtures/ff1-coneria-town.png")
                        .writeBytes(java.util.Base64.getDecoder().decode(toolset.getScreen().base64))
                    println("[fixture-builder] mapId=8 entered: localX=${ram["localX"]} " +
                        "localY=${ram["localY"]} — saved ${snapshot.size} bytes to ${fixture.path}")
                    Outcome.Victory  // any non-InProgress short-circuits the loop
                } else null
            }

            val agentSession = AgentSession(
                toolset = toolset,
                observer = observer,
                executor = executor,
                advisor = advisor,
                toolCallLog = toolCallLog,
                budget = Budget(maxSkillInvocations = 30, maxAdvisorCalls = 10,
                    costCapUsd = 2.0, wallClockCapSeconds = 540),
                onTurnEnd = onTurnEnd,
            )

            // Seed the agent with the explicit goal — overrides default Garland goal
            // for this run via plan injection (the executor reads the plan each turn).
            // Since plan injection is via the executor system prompt (immutable),
            // we just let the agent run and rely on the advisor to figure out
            // "enter Coneria Town" from RAM/screen alone. If that proves too hard
            // the next iteration would extend AgentSession with an injectable goal.

            val outcome = agentSession.run()
            println("[fixture-builder] agent outcome: $outcome (entered=$entered)")
            check(entered) {
                "agent did not reach mapId=8 within budget; outcome=$outcome — see trace in ~/.knes/runs/"
            }
        }
    }
})
