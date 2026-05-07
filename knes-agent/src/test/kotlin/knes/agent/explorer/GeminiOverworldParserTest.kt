package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class GeminiOverworldParserTest : FunSpec({
    val happyPathEnvelope = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\"found\": true, \"screenX\": 11, \"screenY\": 4}"}]}}],
          "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":0}
        }
    """.trimIndent()

    val notFoundEnvelope = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\"found\": false}"}]}}],
          "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"thoughtsTokenCount":0}
        }
    """.trimIndent()

    val malformedEnvelope = "not json at all"

    test("parses Found(11,4) from happy-path envelope") {
        val r = GeminiVisionConsult.parseOverworldResponse(happyPathEnvelope)
        r.shouldBeInstanceOf<HaikuConsult.OverworldClassification.Found>()
        r.screenX shouldBe 11
        r.screenY shouldBe 4
    }

    test("parses NotFound from explicit not-found envelope") {
        val r = GeminiVisionConsult.parseOverworldResponse(notFoundEnvelope)
        r.shouldBeInstanceOf<HaikuConsult.OverworldClassification.NotFound>()
    }

    test("returns NotFound on malformed envelope (no exception)") {
        val r = GeminiVisionConsult.parseOverworldResponse(malformedEnvelope)
        r.shouldBeInstanceOf<HaikuConsult.OverworldClassification.NotFound>()
    }
})
