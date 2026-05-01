package knes.agent.advisor

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ScreenPng
import knes.agent.tools.results.StateSnapshot

/**
 * Subset of EmulatorToolset that only exposes read-only tools to a Koog agent.
 * Used by AdvisorAgent so the planner cannot mutate emulator state.
 */
@LLMDescription("Read-only emulator inspection tools: state and screenshot.")
class ReadOnlyToolset(private val full: EmulatorToolset) : ToolSet {
    @Tool @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    fun getState(): StateSnapshot = full.getState()

    @Tool @LLMDescription("PNG screenshot of the current frame, base64-encoded.")
    fun getScreen(): ScreenPng = full.getScreen()
}
