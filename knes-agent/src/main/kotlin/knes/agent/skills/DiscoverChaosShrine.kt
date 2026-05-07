package knes.agent.skills

import knes.agent.explorer.HaikuConsult
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset

/**
 * Vision skill: looks at the current overworld viewport and asks the configured
 * vision backend to locate the Chaos Shrine (Temple of Fiends) entrance tile.
 * On Found, persists a TEMPLE_ENTRY landmark at the derived world coordinates
 * and returns SkillResult(ok=true, "Discovered (wx,wy)"). On NotFound or any
 * failure path, returns SkillResult(ok=true, "NotVisible") or
 * "ClassifyFailed:<msg>" — never throws. The caller (BridgeTick) treats these
 * uniformly via message-prefix dispatch.
 */
class DiscoverChaosShrine(
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource,
    private val landmarks: LandmarkMemory,
    private val vision: HaikuConsult,
) : Skill {
    override val id = "discover_chaos_shrine"
    override val description =
        "Locate the Chaos Shrine in the current overworld viewport via vision; " +
            "persists a TEMPLE_ENTRY landmark at the derived world coords."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val ram = toolset.getState().ram
        val wx = ram["worldX"] ?: return SkillResult(false, "worldX missing")
        val wy = ram["worldY"] ?: return SkillResult(false, "worldY missing")
        val viewport = viewportSource.readViewport(wx to wy)
        val screenshotB64 = try {
            toolset.getScreen().base64
        } catch (e: Exception) {
            return SkillResult(true, "ClassifyFailed: screenshot unavailable: ${e.message}")
        }
        val classification = try {
            vision.classifyOverworldLandmark(screenshotB64, kind = "chaos_shrine")
        } catch (e: Exception) {
            return SkillResult(true, "ClassifyFailed: ${e.message}")
        }
        return when (classification) {
            is HaikuConsult.OverworldClassification.NotFound ->
                SkillResult(true, "NotVisible (cost=\$${"%.4f".format(classification.costUsd)})")
            is HaikuConsult.OverworldClassification.Found -> {
                val (tx, ty) = viewport.localToWorld(classification.screenX, classification.screenY)
                landmarks.recordIfNew(Landmark(
                    id = "temple-of-fiends-entry",
                    kind = LandmarkKind.TEMPLE_ENTRY,
                    worldX = tx, worldY = ty,
                    note = "chaos-shrine entrance discovered via vision",
                ))
                landmarks.save()
                SkillResult(true, "Discovered ($tx,$ty) (cost=\$${"%.4f".format(classification.costUsd)})")
            }
        }
    }
}
