package knes.agent.v2.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class HaikuClientParseTest : StringSpec({
    val client = HaikuClient(http = AnthropicHttp(apiKey = "unused-no-network-in-this-test"))

    "parses sprite lines from describeScene format" {
        val scene = """
            party: vp(8,7) facing S
            overlay: none
            sprite: shopkeeper vp(11,10) [weapon]
            sprite: generic-npc vp(6,4) [walking E]
            exits-visible: south
        """.trimIndent()

        val out = client.parseSpriteLines(scene)

        out.size shouldBe 2
        out[0].kind shouldBe "shopkeeper"
        out[0].vpX shouldBe 11
        out[0].vpY shouldBe 10
        out[0].note shouldBe "weapon"
        out[1].kind shouldBe "generic-npc"
    }

    "empty list when no sprite lines" {
        val scene = """
            party: vp(8,7) facing S
            overlay: shop-menu
            exits-visible: none
        """.trimIndent()

        client.parseSpriteLines(scene).shouldBeEmpty()
    }

    "sprite without note still parses" {
        val out = client.parseSpriteLines("sprite: shopkeeper vp(11,10)")
        out.size shouldBe 1
        out[0].kind shouldBe "shopkeeper"
        out[0].note shouldBe ""
    }

    "viewport coords clamped to valid range" {
        val out = client.parseSpriteLines("sprite: shopkeeper vp(99,99) [stray]")
        out.size shouldBe 1
        out[0].vpX shouldBe 15
        out[0].vpY shouldBe 14
    }
})
