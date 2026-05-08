package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class GeminiVisionConsultPass2Test : FunSpec({
    test("parses confirmed shopkeeper response with refined shop kind") {
        val raw = """{"confirmed":true,"refinedKind":"shopkeeper","refinedShopKind":"weapon","reason":"counter shows weapons"}"""
        val r = GeminiVisionConsult.parsePass2(raw, costUsd = 0.005)
        (r is HaikuConsult.VerifyResult.Confirmed) shouldBe true
        r as HaikuConsult.VerifyResult.Confirmed
        r.refinedKind shouldBe "shopkeeper"
        r.refinedShopKind shouldBe "weapon"
    }

    test("parses confirmed non-shopkeeper with null refinedShopKind") {
        val raw = """{"confirmed":true,"refinedKind":"king","refinedShopKind":null,"reason":"on throne"}"""
        val r = GeminiVisionConsult.parsePass2(raw, costUsd = 0.005)
        (r is HaikuConsult.VerifyResult.Confirmed) shouldBe true
        (r as HaikuConsult.VerifyResult.Confirmed).refinedShopKind.shouldBeNull()
    }

    test("parses rejected with reason") {
        val raw = """{"confirmed":false,"reason":"no NPC at coords"}"""
        val r = GeminiVisionConsult.parsePass2(raw, costUsd = 0.005)
        (r is HaikuConsult.VerifyResult.Rejected) shouldBe true
        (r as HaikuConsult.VerifyResult.Rejected).reason shouldBe "no NPC at coords"
    }

    test("malformed JSON returns Rejected with malformed reason") {
        val r = GeminiVisionConsult.parsePass2("not json", 0.0)
        (r is HaikuConsult.VerifyResult.Rejected) shouldBe true
        (r as HaikuConsult.VerifyResult.Rejected).reason shouldContain "malformed"
    }

    test("system prompt forbids classifying shopkeeper as inn shop") {
        GeminiVisionConsult.SYSTEM_VERIFY_LANDMARK shouldContain "\"innkeeper\" is its own kind"
    }
})
