package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.perception.LandmarkKind

class GeminiVisionConsultTest : FunSpec({
    test("parseInteriorResponse extracts landmarks from Gemini envelope with thoughts billing") {
        // Real Gemini 2.5 Pro response shape captured from the live A/B test on the
        // Coneria Castle throne screenshot. Note the markdown code fence around the
        // inner JSON — JSON_OBJECT regex extracts the first object regardless.
        val envelope = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "```json\n{\n  \"landmarks\": [\n    {\n      \"kind\": \"NPC_KING\",\n      \"note\": \"A king sits on a throne in a large royal room, flanked by two golden dragon emblems.\"\n    }\n  ]\n}\n```"
                  }]
                }
              }],
              "usageMetadata": {
                "promptTokenCount": 375,
                "candidatesTokenCount": 14,
                "thoughtsTokenCount": 432
              }
            }
        """.trimIndent()
        val res = GeminiVisionConsult.parseInteriorResponse(envelope, mapId = 24, runId = "test-run")
        res.landmarks shouldHaveSize 1
        res.landmarks[0].kind shouldBe LandmarkKind.NPC_KING
        res.landmarks[0].mapId shouldBe 24
        res.landmarks[0].note shouldContain "throne"
        res.landmarks[0].discoveredRunId shouldBe "test-run"
        res.landmarks[0].id shouldContain "gemini_npc_king_24"
        // Cost: 375 in @ $1.25/M + (14 + 432) out/thoughts @ $10/M
        // = 0.000469 + 0.00446 = ~$0.00493
        (res.costUsd > 0.004 && res.costUsd < 0.006) shouldBe true
    }

    test("parseInteriorResponse returns empty list when Pro correctly rejects void state") {
        // From the live trap-screen A/B test: Gemini 2.5 Pro returns empty landmarks
        // on the mapId=0 UnknownMapTrap screen where Haiku 4.5 hallucinates 4 false
        // positives. This is the explicit win for Pro on the edge case.
        val envelope = """
            {
              "candidates": [{
                "content": {"parts": [{"text": "```json\n{\n  \"landmarks\": []\n}\n```"}]}
              }],
              "usageMetadata": {
                "promptTokenCount": 375, "candidatesTokenCount": 14, "thoughtsTokenCount": 652
              }
            }
        """.trimIndent()
        val res = GeminiVisionConsult.parseInteriorResponse(envelope, mapId = 0, runId = "test")
        res.landmarks.shouldBeEmpty()
    }

    test("parseInteriorResponse drops landmarks with kind values not in LandmarkKind enum") {
        // Gemini 2.5 Flash sometimes hallucinates outside the requested schema
        // (e.g. "Building", "Path", "Vegetation"). Drop those to avoid polluting
        // LandmarkMemory with unparseable kinds.
        val envelope = """
            {
              "candidates": [{
                "content": {"parts": [{"text": "{\"landmarks\":[{\"kind\":\"Building\",\"note\":\"Inn\"},{\"kind\":\"NPC_KING\",\"note\":\"throne\"}]}"}]}
              }],
              "usageMetadata": {"promptTokenCount":1,"candidatesTokenCount":1,"thoughtsTokenCount":0}
            }
        """.trimIndent()
        val res = GeminiVisionConsult.parseInteriorResponse(envelope, mapId = 24, runId = "test")
        // Only NPC_KING survives; "Building" is not a valid LandmarkKind.
        res.landmarks shouldHaveSize 1
        res.landmarks[0].kind shouldBe LandmarkKind.NPC_KING
    }

    test("parseInteriorResponse returns empty list on malformed envelope") {
        val res = GeminiVisionConsult.parseInteriorResponse("{not valid json", mapId = 0, runId = "x")
        res.landmarks.shouldBeEmpty()
        res.costUsd shouldBe 0.0
    }

    test("parseInteriorResponse returns empty list when candidates array is missing (safety/blocked)") {
        // Gemini may return a safety-blocked response without `candidates`.
        val envelope = """{"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":10}}"""
        val res = GeminiVisionConsult.parseInteriorResponse(envelope, mapId = 0, runId = "x")
        res.landmarks.shouldBeEmpty()
    }

    test("parseDialogResponse extracts summary + landmarkHint, normalises null") {
        val envelope = """
            {
              "candidates": [{
                "content": {"parts": [{"text": "{\"summary\":\"the king asks for help\",\"landmarkHint\":\"KING\"}"}]}
              }],
              "usageMetadata": {"promptTokenCount":50,"candidatesTokenCount":20}
            }
        """.trimIndent()
        val res = GeminiVisionConsult.parseDialogResponse(envelope)
        res.summary shouldBe "the king asks for help"
        res.landmarkHint shouldBe "KING"
    }

    test("parseDialogResponse normalises 'null' string to actual null") {
        val envelope = """
            {
              "candidates": [{
                "content": {"parts": [{"text": "{\"summary\":\"shopkeeper greets you\",\"landmarkHint\":\"null\"}"}]}
              }],
              "usageMetadata": {"promptTokenCount":40,"candidatesTokenCount":15}
            }
        """.trimIndent()
        val res = GeminiVisionConsult.parseDialogResponse(envelope)
        res.landmarkHint shouldBe null
    }
})
