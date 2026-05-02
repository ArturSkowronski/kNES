package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.FfPhase

class ModelRouterTest : FunSpec({
    val router = ModelRouter()

    test("executor in TitleOrMenu uses Sonnet 4.5") {
        router.modelFor(FfPhase.TitleOrMenu, AgentRole.EXECUTOR) shouldBe AnthropicModels.Sonnet_4_5
    }
    test("advisor in TitleOrMenu uses Opus 4") {
        router.modelFor(FfPhase.TitleOrMenu, AgentRole.ADVISOR) shouldBe AnthropicModels.Opus_4
    }
    test("executor in Overworld uses Haiku 4.5") {
        router.modelFor(FfPhase.Overworld(0, 0), AgentRole.EXECUTOR) shouldBe AnthropicModels.Haiku_4_5
    }
    test("advisor in Overworld uses Sonnet 4.5") {
        router.modelFor(FfPhase.Overworld(0, 0), AgentRole.ADVISOR) shouldBe AnthropicModels.Sonnet_4_5
    }
    test("executor in Battle uses Haiku 4.5") {
        router.modelFor(FfPhase.Battle(0x7C, 100, false), AgentRole.EXECUTOR) shouldBe AnthropicModels.Haiku_4_5
    }
})
