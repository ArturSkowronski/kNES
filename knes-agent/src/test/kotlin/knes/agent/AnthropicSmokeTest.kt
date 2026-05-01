package knes.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContainIgnoringCase

class AnthropicSmokeTest : FunSpec({

    test("roundtrips a trivial prompt") {
        val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        if (key == null) {
            println("ANTHROPIC_API_KEY not set; skipping live test")
            return@test
        }

        val client = AnthropicLLMClient(apiKey = key)
        val response = client.execute(
            prompt = prompt("smoke") {
                system("Reply with the single word PONG, nothing else.")
                user("ping")
            },
            model = AnthropicModels.Sonnet_4_5,
        )
        response.toString() shouldContainIgnoringCase "PONG"
    }
})
