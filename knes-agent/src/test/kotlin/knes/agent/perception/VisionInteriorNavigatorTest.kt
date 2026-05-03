package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VisionInteriorNavigatorTest : FunSpec({
    val nav = AnthropicVisionInteriorNavigator(apiKey = "unused-for-parse-test")

    test("parses valid JSON happy path") {
        val raw = """{"id":"x","content":[{"type":"text","text":"{\"direction\":\"N\",\"reason\":\"corridor north\"}"}]}"""
        nav.parseMove(raw) shouldBe InteriorMove.NORTH
    }

    test("parses each cardinal letter") {
        listOf("N" to InteriorMove.NORTH, "S" to InteriorMove.SOUTH,
               "E" to InteriorMove.EAST, "W" to InteriorMove.WEST).forEach { (letter, move) ->
            val raw = """{"content":[{"type":"text","text":"{\"direction\":\"$letter\",\"reason\":\"r\"}"}]}"""
            nav.parseMove(raw) shouldBe move
        }
    }

    test("parses EXIT") {
        val raw = """{"content":[{"type":"text","text":"{\"direction\":\"EXIT\",\"reason\":\"on overworld\"}"}]}"""
        nav.parseMove(raw) shouldBe InteriorMove.EXIT
    }

    test("parses STUCK") {
        val raw = """{"content":[{"type":"text","text":"{\"direction\":\"STUCK\",\"reason\":\"walls all around\"}"}]}"""
        nav.parseMove(raw) shouldBe InteriorMove.STUCK
    }

    test("tolerates JSON wrapped in code fence / extra prose") {
        val text = """Looking at the screen…\n\n```json\n{\"direction\": \"E\", \"reason\": \"door east\"}\n```"""
        val raw = """{"content":[{"type":"text","text":"$text"}]}"""
        nav.parseMove(raw) shouldBe InteriorMove.EAST
    }

    test("returns UNCLEAR on non-JSON text") {
        val raw = """{"content":[{"type":"text","text":"I cannot help with this image."}]}"""
        nav.parseMove(raw) shouldBe InteriorMove.UNCLEAR
    }

    test("returns UNCLEAR on unknown direction value") {
        val raw = """{"content":[{"type":"text","text":"{\"direction\":\"UP\",\"reason\":\"x\"}"}]}"""
        nav.parseMove(raw) shouldBe InteriorMove.UNCLEAR
    }

    test("returns UNCLEAR on malformed Anthropic envelope") {
        nav.parseMove("not json at all") shouldBe InteriorMove.UNCLEAR
        nav.parseMove("""{"error":"bad request"}""") shouldBe InteriorMove.UNCLEAR
    }
})
