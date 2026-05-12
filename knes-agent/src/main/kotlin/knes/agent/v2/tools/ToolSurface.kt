package knes.agent.v2.tools

import knes.agent.skills.ExitInterior
import knes.agent.skills.SkillResult
import knes.agent.skills.WalkOverworldTo
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.runtime.Phase

sealed class ToolOutcome {
    data class Ok(val message: String = "", val data: Map<String, String> = emptyMap()) : ToolOutcome()
    data class Fail(val message: String) : ToolOutcome()
    data class Reject(val reason: String) : ToolOutcome()
}

interface ToolSurface {
    suspend fun walkTo(x: Int, y: Int): ToolOutcome
    suspend fun interactAt(x: Int, y: Int): ToolOutcome
    suspend fun useMenu(path: String): ToolOutcome
    suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome
    suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome
    suspend fun restAtInn(innMapId: String): ToolOutcome
    suspend fun battleFightAll(): ToolOutcome
}

class DefaultToolSurface(
    private val toolset: EmulatorToolset,
    private val phaseProvider: () -> Phase,
    private val walkOverworld: WalkOverworldTo,
    private val exitInterior: ExitInterior,
    private val menuWalker: MenuWalker = MenuWalker(),
) : ToolSurface {

    override suspend fun walkTo(x: Int, y: Int): ToolOutcome = when (phaseProvider()) {
        Phase.Overworld -> wrap(walkOverworld.invoke(mapOf("targetX" to "$x", "targetY" to "$y", "maxSteps" to "32")))
        Phase.Indoors   -> wrap(exitInterior.invoke(mapOf("maxSteps" to "64")))
        else -> ToolOutcome.Reject("walkTo not applicable in phase ${phaseProvider()}")
    }

    override suspend fun interactAt(x: Int, y: Int): ToolOutcome {
        val walk = walkTo(x, y)
        if (walk !is ToolOutcome.Ok) return walk
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
        return ToolOutcome.Ok("interactAt done")
    }

    override suspend fun useMenu(path: String): ToolOutcome {
        val taps = try { menuWalker.parse(path) }
                   catch (e: IllegalArgumentException) { return ToolOutcome.Reject(e.message ?: "bad path") }
        for (t in taps) toolset.tap(button = t.button, count = t.count, pressFrames = 5, gapFrames = 12)
        return ToolOutcome.Ok("menu walked: $path")
    }

    override suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome {
        return ToolOutcome.Reject("buyAtShop wiring TBD until Main wires v1 BuyAtShop instance")
            .also { System.err.println("[v2.tool] buyAtShop placeholder hit — wire in Phase C.") }
    }

    override suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome {
        return ToolOutcome.Reject("equipWeapon wiring TBD until Main wires v1 instance")
    }

    override suspend fun restAtInn(innMapId: String): ToolOutcome =
        ToolOutcome.Reject("restAtInn wiring TBD")

    override suspend fun battleFightAll(): ToolOutcome {
        val r = toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")
        return if (r.ok) ToolOutcome.Ok(r.message, r.data) else ToolOutcome.Fail(r.message)
    }

    private fun wrap(s: SkillResult): ToolOutcome =
        if (s.ok) ToolOutcome.Ok(s.message, s.ramAfter.mapValues { it.value.toString() })
        else ToolOutcome.Fail(s.message)
}
