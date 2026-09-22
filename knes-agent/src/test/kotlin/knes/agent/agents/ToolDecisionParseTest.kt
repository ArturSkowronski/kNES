package knes.agent.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The Executor's decision parser.
 *
 * A decision that fails to parse is thrown away and replaced by the plan tail, silently.
 * That is worse than a wrong decision: the model's reading of the screen never reaches
 * the game, and the log still shows a tool being dispatched.
 */
class ToolDecisionParseTest : FunSpec({

    val agent = ExecutorAgent(
        sonnet = knes.agent.llm.SonnetClient(NoChat),
        haiku = knes.agent.llm.HaikuClient(NoChat),
        tools = NoTools,
        memory = knes.agent.runtime.Memory(knes.agent.runtime.RunDirectory.freshRun()),
        run = null,
    )

    test("coordinates written as numbers are accepted") {
        // What a model actually writes for a coordinate. Decoding args straight into
        // Map<String,String> rejected this, and every walkTo decision was discarded.
        val (tool, args, _) = agent.parseToolDecision(
            """{"tool":"walkTo","args":{"x":11,"y":11},"reasoning":"to the shop"}"""
        )

        tool shouldBe "walkTo"
        args shouldBe mapOf("x" to "11", "y" to "11")
    }

    test("coordinates written as strings still work") {
        val (_, args, _) = agent.parseToolDecision("""{"tool":"walkTo","args":{"x":"11","y":"11"}}""")
        args shouldBe mapOf("x" to "11", "y" to "11")
    }

    test("a button sequence becomes a comma-joined argument") {
        val (tool, args, _) = agent.parseToolDecision("""{"sequence":["Up"],"reasoning":"north"}""")
        tool shouldBe "sequence"
        args shouldBe mapOf("buttons" to "Up")
    }

    test("prose around the JSON is tolerated") {
        val (tool, _, _) = agent.parseToolDecision("""Here you go: {"sequence":["B"]} — hope that helps""")
        tool shouldBe "sequence"
    }

    test("the reasoning is carried through, trimmed") {
        val (_, _, reasoning) = agent.parseToolDecision(
            """{"sequence":["A"],"reasoning":"talk to the shopkeeper"}"""
        )
        reasoning shouldBe "llm: talk to the shopkeeper"
    }
})

private object NoChat : knes.agent.llm.ChatLlm {
    override val providerName = "none"
    override val fastModel = "none"
    override val strongModel = "none"
    override suspend fun generate(
        model: String, systemPrompt: String, userText: String, imageB64: String?, maxTokens: Int,
    ): String = error("not called")
    override fun close() = Unit
}

private object NoTools : knes.agent.tools.ToolSurface {
    override suspend fun boot() = error("not called")
    override suspend fun walkTo(x: Int, y: Int) = error("not called")
    override suspend fun interactAt(x: Int, y: Int) = error("not called")
    override suspend fun useMenu(path: String) = error("not called")
    override suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>) = error("not called")
    override suspend fun equipWeapon(charSlot: Int, weaponSlot: Int) = error("not called")
    override suspend fun restAtInn(innMapId: String) = error("not called")
    override suspend fun battleFightAll() = error("not called")
    override suspend fun approachSprite(kind: String) = error("not called")
    override suspend fun sequence(buttons: List<String>) = error("not called")
}
