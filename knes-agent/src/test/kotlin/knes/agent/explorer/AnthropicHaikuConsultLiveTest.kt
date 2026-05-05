package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * Live HTTP smoke test for AnthropicHaikuConsult — round-trips against the real
 * Anthropic API with a text-only prompt (no screenshot). Skips silently when
 * ANTHROPIC_API_KEY is unset so CI doesn't break.
 *
 * Asserts the round-trip succeeds and the cost calc reports a non-zero value
 * (proves usage parsing is wired). The model's actual JSON output isn't fixed
 * — without a screenshot Haiku will return an empty landmarks array, but the
 * envelope parsing is still exercised end-to-end.
 */
class AnthropicHaikuConsultLiveTest : FunSpec({
    test("classifyInterior round-trips against real API (text only)") {
        val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        if (key == null) { println("ANTHROPIC_API_KEY not set; skipping live test"); return@test }

        val consult = AnthropicHaikuConsult(apiKey = key)
        try {
            val res = runBlocking {
                consult.classifyInterior(mapId = 1, visitedTileCount = 0, screenshotBase64 = null, runId = "live-test")
            }
            // Without a screenshot the model has nothing to classify — landmarks
            // should be empty or contain only hallucinated entries. Either way
            // the cost must reflect that the HTTP call actually happened.
            (res.costUsd > 0.0) shouldBe true
            println("[live] classifyInterior: landmarks=${res.landmarks.size} cost=$${"%.6f".format(res.costUsd)}")
        } finally {
            consult.close()
        }
    }

    test("readDialog round-trips against real API (text only)") {
        val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        if (key == null) { println("ANTHROPIC_API_KEY not set; skipping live test"); return@test }

        val consult = AnthropicHaikuConsult(apiKey = key)
        try {
            val res = runBlocking { consult.readDialog(screenshotBase64 = null) }
            (res.costUsd > 0.0) shouldBe true
            println("[live] readDialog: hint=${res.landmarkHint} cost=$${"%.6f".format(res.costUsd)}")
        } finally {
            consult.close()
        }
    }
})
