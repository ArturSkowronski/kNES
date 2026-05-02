package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import knes.agent.perception.FfPhase

enum class AgentRole { EXECUTOR, ADVISOR }

/**
 * Route per (phase, role) → model. See spec §7 for rationale and pricing.
 *
 * Haiku 4.5 is 15× cheaper than Sonnet, 75× cheaper than Opus. We use it wherever the
 * choice is "pick which scripted skill to invoke" — Overworld, Battle, PostBattle. Sonnet
 * runs uncertain pre-game phases. Opus only advises on novel/uncertain pre-game phases.
 */
class ModelRouter {
    fun modelFor(phase: FfPhase, role: AgentRole): LLModel = when (phase) {
        FfPhase.Boot, FfPhase.TitleOrMenu, FfPhase.NewGameMenu, FfPhase.NameEntry ->
            if (role == AgentRole.EXECUTOR) AnthropicModels.Sonnet_4_5 else AnthropicModels.Opus_4
        is FfPhase.Overworld, is FfPhase.Battle, FfPhase.PostBattle, FfPhase.PartyDefeated ->
            if (role == AgentRole.EXECUTOR) AnthropicModels.Haiku_4_5 else AnthropicModels.Sonnet_4_5
    }
}
