package knes.agent.llm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun body(
    model: String = "gpt-5",
    systemPrompt: String = "be brief",
    userText: String = "what is on screen?",
    imageB64: String? = null,
    maxTokens: Int = 800,
    reasoningEffort: String = "low",
) = OpenAiHttp.requestBody(model, systemPrompt, userText, imageB64, maxTokens, reasoningEffort)

class OpenAiHttpTest : FunSpec({

    test("the budget is max_completion_tokens, because reasoning spends it too") {
        val request = body(maxTokens = 800)

        // `max_tokens` is rejected by these models, and a budget sized for the answer
        // alone comes back empty with finish_reason=length.
        request["max_tokens"] shouldBe null
        request["max_completion_tokens"]!!.jsonPrimitive.content shouldBe
            OpenAiHttp.MIN_TOKEN_BUDGET.toString()
    }

    test("a caller asking for more than the floor gets what it asked for") {
        body(maxTokens = 9_000)["max_completion_tokens"]!!.jsonPrimitive.content shouldBe "9000"
    }

    test("reasoning effort is sent to models that have it, and only those") {
        body(model = "gpt-5")["reasoning_effort"]!!.jsonPrimitive.content shouldBe "low"
        body(model = "o4-mini")["reasoning_effort"]!!.jsonPrimitive.content shouldBe "low"
        body(model = "gpt-4.1")["reasoning_effort"] shouldBe null
    }

    test("the system prompt and the user text keep their roles") {
        val messages = body(systemPrompt = "S", userText = "U")["messages"]!!.jsonArray

        messages.size shouldBe 2
        messages[0].jsonObject["role"]!!.jsonPrimitive.content shouldBe "system"
        messages[0].jsonObject["content"]!!.jsonPrimitive.content shouldBe "S"
        messages[1].jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
    }

    test("a screenshot travels as a data URI next to the text") {
        val content = body(imageB64 = "QUJD")["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray

        content.size shouldBe 2
        content[0].jsonObject["type"]!!.jsonPrimitive.content shouldBe "text"
        content[1].jsonObject["type"]!!.jsonPrimitive.content shouldBe "image_url"
        val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        url shouldStartWith "data:image/png;base64,"
        url shouldContain "QUJD"
    }

    test("without a screenshot the content is text alone") {
        val content = body(imageB64 = null)["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray

        content.size shouldBe 1
        content[0].jsonObject["type"]!!.jsonPrimitive.content shouldBe "text"
    }

    test("the fast model is not a cheaper one by default") {
        // gpt-5-mini called a solid red image "blue" while this was being wired, and a
        // cheap model misreading the screen has already cost this project a smoke run.
        OpenAiHttp.DEFAULT_FAST_MODEL shouldBe OpenAiHttp.DEFAULT_STRONG_MODEL
    }
})
