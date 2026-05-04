package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VisionOverworldNavigatorTest : FunSpec({
    val nav = AnthropicVisionOverworldNavigator(apiKey = "unused-for-parse-test")

    test("parses valid JSON happy path") {
        val raw = """{"id":"x","content":[{"type":"text","text":"{\"direction\":\"N\",\"reason\":\"north toward target\"}"}]}"""
        nav.parseMove(raw) shouldBe OverworldMove.NORTH
    }

    test("parses each cardinal letter") {
        listOf(
            "N" to OverworldMove.NORTH,
            "S" to OverworldMove.SOUTH,
            "E" to OverworldMove.EAST,
            "W" to OverworldMove.WEST,
        ).forEach { (letter, move) ->
            val raw = """{"content":[{"type":"text","text":"{\"direction\":\"$letter\",\"reason\":\"r\"}"}]}"""
            nav.parseMove(raw) shouldBe move
        }
    }

    test("parses ENTERED") {
        val raw = """{"content":[{"type":"text","text":"{\"direction\":\"ENTERED\",\"reason\":\"interior view\"}"}]}"""
        nav.parseMove(raw) shouldBe OverworldMove.ENTERED
    }

    test("parses STUCK") {
        val raw = """{"content":[{"type":"text","text":"{\"direction\":\"STUCK\",\"reason\":\"all walls\"}"}]}"""
        nav.parseMove(raw) shouldBe OverworldMove.STUCK
    }

    test("tolerates JSON wrapped in code fence / extra prose") {
        val text = """Looking…\n\n```json\n{\"direction\": \"E\", \"reason\": \"east bridge\"}\n```"""
        val raw = """{"content":[{"type":"text","text":"$text"}]}"""
        nav.parseMove(raw) shouldBe OverworldMove.EAST
    }

    test("returns UNCLEAR on non-JSON text") {
        val raw = """{"content":[{"type":"text","text":"I cannot help with this image."}]}"""
        nav.parseMove(raw) shouldBe OverworldMove.UNCLEAR
    }

    test("returns UNCLEAR on unknown direction value") {
        val raw = """{"content":[{"type":"text","text":"{\"direction\":\"UPLEFT\",\"reason\":\"x\"}"}]}"""
        nav.parseMove(raw) shouldBe OverworldMove.UNCLEAR
    }

    test("returns UNCLEAR on malformed Anthropic envelope") {
        nav.parseMove("not json at all") shouldBe OverworldMove.UNCLEAR
        nav.parseMove("""{"error":"bad request"}""") shouldBe OverworldMove.UNCLEAR
    }
})
