package knes.agent.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private class FakeChat(
    override val fastModel: String = "fast",
    override val strongModel: String = "strong",
) : ChatLlm {
    override val providerName = "fake"
    override suspend fun generate(
        model: String,
        systemPrompt: String,
        userText: String,
        imageB64: String?,
        maxTokens: Int,
    ): String = "unused"
    override fun close() = Unit
}

class LlmProviderTest : FunSpec({

    test("an explicit choice wins over what keys happen to be present") {
        LlmProvider.select("openai", hasOpenAiKey = false) shouldBe LlmProvider.OpenAi
        LlmProvider.select("gemini", hasOpenAiKey = true) shouldBe LlmProvider.Gemini
        LlmProvider.select("anthropic+gemini", hasOpenAiKey = true) shouldBe LlmProvider.AnthropicAndGemini
    }

    test("the choice is case and whitespace insensitive") {
        LlmProvider.select("  OpenAI ", hasOpenAiKey = false) shouldBe LlmProvider.OpenAi
    }

    test("with no choice, a key that covers every role wins") {
        LlmProvider.select(null, hasOpenAiKey = true) shouldBe LlmProvider.OpenAi
        LlmProvider.select("", hasOpenAiKey = true) shouldBe LlmProvider.OpenAi

        // Gemini alone is enough now. It used to fall through to the pairing, which
        // then failed for a missing Anthropic key even with a working vision model.
        LlmProvider.select(null, hasOpenAiKey = false, hasGeminiKey = true) shouldBe LlmProvider.Gemini

        LlmProvider.select(null, hasOpenAiKey = false, hasGeminiKey = false) shouldBe
            LlmProvider.AnthropicAndGemini
    }

    test("a running provider never runs out of one key and silently switches") {
        // Choice is by configuration, not by whether a call just failed: a provider
        // swapping mid-run would make a trace impossible to read.
        LlmProvider.select("openai", hasOpenAiKey = false, hasGeminiKey = true) shouldBe LlmProvider.OpenAi
    }

    test("an unknown provider is refused by name rather than silently defaulted") {
        val error = shouldThrow<IllegalStateException> { LlmProvider.select("llama", hasOpenAiKey = true) }
        error.message!! shouldContain "llama"
    }

    test("the Haiku and Sonnet roles follow whatever provider is in use") {
        // Their model ids used to be hardcoded Anthropic strings, which is what made
        // them impossible to point anywhere else.
        val chat = FakeChat(fastModel = "quick-one", strongModel = "capable-one")

        HaikuClient(chat).modelId shouldBe "quick-one"
        SonnetClient(chat).modelId shouldBe "capable-one"
    }
})
