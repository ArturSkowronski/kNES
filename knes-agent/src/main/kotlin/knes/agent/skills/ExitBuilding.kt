package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.tools.EmulatorToolset

/**
 * V2.4: thin alias to ExitInterior. Kept so existing callers / advisor prompts
 * referencing "exitBuilding" continue to work.
 */
class ExitBuilding(
    toolset: EmulatorToolset,
    mapSession: MapSession,
    fog: FogOfWar,
) : Skill {
    override val id = "exit_building"
    override val description = "Alias for exitInterior — walks to the nearest exit of the current interior."

    private val delegate = ExitInterior(toolset, mapSession, fog)

    override suspend fun invoke(args: Map<String, String>): SkillResult = delegate.invoke(args)
}
