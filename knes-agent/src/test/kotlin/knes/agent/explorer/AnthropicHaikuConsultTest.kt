package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import knes.agent.perception.LandmarkKind

/**
 * Pure-unit tests on the response parser. The HTTP boundary is exercised live
 * (manual / opt-in) — these only validate the JSON envelope + inner-text parsing.
 */
class AnthropicHaikuConsultTest : FunSpec({
    test("parseInteriorResponse extracts NPC_KING landmark with correct cost") {
        val raw = """
            {
              "content": [{"type":"text","text":"{\"landmarks\":[{\"kind\":\"NPC_KING\",\"note\":\"crowned NPC on throne\"}]}"}],
              "usage": {"input_tokens": 1500, "output_tokens": 30}
            }
        """.trimIndent()
        val res = AnthropicHaikuConsult.parseInteriorResponse(raw, mapId = 1, runId = "run-1")
        res.landmarks shouldHaveSize 1
        res.landmarks[0].kind shouldBe LandmarkKind.NPC_KING
        res.landmarks[0].mapId shouldBe 1
        res.landmarks[0].note shouldBe "crowned NPC on throne"
        res.landmarks[0].discoveredRunId shouldBe "run-1"
        // 1500 * $1e-6 + 30 * $5e-6 = 0.0015 + 0.00015
        res.costUsd shouldBe (0.001650).plusOrMinus(1e-9)
    }

    test("parseInteriorResponse falls back to NPC_GENERIC for unknown kind") {
        val raw = """
            {"content":[{"type":"text","text":"{\"landmarks\":[{\"kind\":\"WIZARD\",\"note\":\"robed figure\"}]}"}],"usage":{"input_tokens":100,"output_tokens":10}}
        """.trimIndent()
        val res = AnthropicHaikuConsult.parseInteriorResponse(raw, mapId = 8, runId = "")
        res.landmarks shouldHaveSize 1
        res.landmarks[0].kind shouldBe LandmarkKind.NPC_GENERIC
    }

    test("parseInteriorResponse handles malformed inner text gracefully") {
        val raw = """{"content":[{"type":"text","text":"not json"}],"usage":{"input_tokens":100,"output_tokens":10}}"""
        val res = AnthropicHaikuConsult.parseInteriorResponse(raw, mapId = 8, runId = "")
        res.landmarks.shouldBeEmpty()
        res.costUsd shouldBe (100 * 1e-6 + 10 * 5e-6).plusOrMinus(1e-9)
    }

    test("parseInteriorResponse handles missing usage field") {
        val raw = """{"content":[{"type":"text","text":"{\"landmarks\":[]}"}]}"""
        val res = AnthropicHaikuConsult.parseInteriorResponse(raw, mapId = 1, runId = "")
        res.landmarks.shouldBeEmpty()
        res.costUsd shouldBe 0.0
    }

    test("parseDialogResponse extracts summary and KING hint") {
        val raw = """
            {"content":[{"type":"text","text":"{\"summary\":\"King asks for bridge\",\"landmarkHint\":\"KING\"}"}],"usage":{"input_tokens":800,"output_tokens":20}}
        """.trimIndent()
        val res = AnthropicHaikuConsult.parseDialogResponse(raw)
        res.summary shouldBe "King asks for bridge"
        res.landmarkHint shouldBe "KING"
        res.costUsd shouldBe (800 * 1e-6 + 20 * 5e-6).plusOrMinus(1e-9)
    }

    test("parseDialogResponse normalizes literal 'null' hint to kotlin null") {
        val raw = """{"content":[{"type":"text","text":"{\"summary\":\"x\",\"landmarkHint\":\"null\"}"}],"usage":{"input_tokens":50,"output_tokens":10}}"""
        val res = AnthropicHaikuConsult.parseDialogResponse(raw)
        res.landmarkHint shouldBe null
    }

    test("parseDialogResponse extracts JSON even when wrapped in prose") {
        val raw = """{"content":[{"type":"text","text":"Here is the result: {\"summary\":\"hi\",\"landmarkHint\":null}"}],"usage":{"input_tokens":40,"output_tokens":5}}"""
        val res = AnthropicHaikuConsult.parseDialogResponse(raw)
        res.summary shouldBe "hi"
        res.landmarkHint shouldBe null
    }
})
