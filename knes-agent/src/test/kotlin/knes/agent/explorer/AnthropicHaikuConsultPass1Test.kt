package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Pure-unit tests on the Pass-1 candidate scan parser and prompt content.
 * The HTTP boundary is exercised live (manual / opt-in).
 */
class AnthropicHaikuConsultPass1Test : FunSpec({
    test("parses well-formed candidates response") {
        val raw = """
        {"candidates":[
          {"kind":"shopkeeper","screenX":5,"screenY":3,"confidence":0.85},
          {"kind":"stairs_down","screenX":12,"screenY":7,"confidence":0.9}
        ]}
        """.trimIndent()
        val parsed = AnthropicHaikuConsult.parsePass1(raw)
        parsed.size shouldBe 2
        parsed[0].kind shouldBe "shopkeeper"
        parsed[0].screenX shouldBe 5
    }

    test("handles markdown-fenced JSON") {
        val raw = "```json\n{\"candidates\":[{\"kind\":\"chest\",\"screenX\":2,\"screenY\":2,\"confidence\":0.6}]}\n```"
        AnthropicHaikuConsult.parsePass1(raw).size shouldBe 1
    }

    test("returns empty list on malformed JSON") {
        AnthropicHaikuConsult.parsePass1("not json") shouldBe emptyList()
    }

    test("returns empty list on null candidates field") {
        AnthropicHaikuConsult.parsePass1("""{"candidates":null}""") shouldBe emptyList()
    }

    test("system prompt mentions all 9 landmark kinds") {
        val expected = listOf(
            "shopkeeper", "king", "innkeeper", "generic_npc",
            "stairs_up", "stairs_down", "chest", "sign", "exit_tile",
        )
        expected.forEach { kind ->
            AnthropicHaikuConsult.SYSTEM_INTERIOR_SCAN.shouldContain("\"$kind\"")
        }
    }
})
