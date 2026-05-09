package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult

class ShopUiDetectorTest : FunSpec({

    test("ramGate rejects genuine overworld (mapflags.bit0=0)") {
        val ram = mapOf("mapflags" to 0, "currentMapId" to 0, "screenState" to 0)
        val det = ShopUiDetector.ramGate(ram)!!
        det.open shouldBe false
        det.source shouldBe "ram_overworld"
    }

    test("ramGate rejects castle sub-mapId 8") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 8, "screenState" to 0)
        val det = ShopUiDetector.ramGate(ram)!!
        det.open shouldBe false
        det.source shouldBe "ram_castle"
    }

    test("ramGate rejects castle sub-mapId 24 (throne hall)") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 24, "screenState" to 0)
        val det = ShopUiDetector.ramGate(ram)!!
        det.source shouldBe "ram_castle"
    }

    test("ramGate rejects battle screen") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 0, "screenState" to 0x68)
        val det = ShopUiDetector.ramGate(ram)!!
        det.source shouldBe "ram_battle"
    }

    test("ramGate passes town overlay (mapflags.bit0=1, mapId=0)") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 0, "screenState" to 0)
        ShopUiDetector.ramGate(ram) shouldBe null
    }

    test("ramGate passes sub-shop interior (mapflags.bit0=1, mapId>0, not castle)") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 7, "screenState" to 0)
        ShopUiDetector.ramGate(ram) shouldBe null
    }

    test("detect: town overlay + vision=weapon → open=true source=vision_kind=weapon") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 0, "screenState" to 0)
        val vision = FakeHaikuConsult(shopClassifications = listOf(
            HaikuConsult.ShopClassification(kind = "weapon", items = emptyList(), costUsd = 0.005)
        ))
        val det = kotlinx.coroutines.runBlocking {
            ShopUiDetector.detect(ram, "fakebase64", vision)
        }
        det.open shouldBe true
        det.kind shouldBe "weapon"
        det.source shouldContain "vision_kind=weapon"
        det.costUsd shouldBe 0.005
    }

    test("detect: town overlay + vision=unknown → open=false source=vision_unknown") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 0, "screenState" to 0)
        val vision = FakeHaikuConsult(shopClassifications = listOf(
            HaikuConsult.ShopClassification(kind = "unknown", items = emptyList(), costUsd = 0.003)
        ))
        val det = kotlinx.coroutines.runBlocking {
            ShopUiDetector.detect(ram, "fakebase64", vision)
        }
        det.open shouldBe false
        det.source shouldBe "vision_unknown"
    }

    test("detect: castle short-circuits without calling vision") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 8, "screenState" to 0)
        val vision = FakeHaikuConsult(shopClassifications = listOf(
            HaikuConsult.ShopClassification(kind = "weapon", items = emptyList(), costUsd = 0.005)
        ))
        val det = kotlinx.coroutines.runBlocking {
            ShopUiDetector.detect(ram, "fakebase64", vision)
        }
        det.open shouldBe false
        det.source shouldBe "ram_castle"
        vision.shopCalls shouldBe 0
    }

    test("detect: armor shop also accepted") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 0, "screenState" to 0)
        val vision = FakeHaikuConsult(shopClassifications = listOf(
            HaikuConsult.ShopClassification(kind = "armor", items = emptyList(), costUsd = 0.004)
        ))
        val det = kotlinx.coroutines.runBlocking {
            ShopUiDetector.detect(ram, "fakebase64", vision)
        }
        det.open shouldBe true
        det.kind shouldBe "armor"
    }

    test("detect: null vision + RAM gate pass → ram_pass_no_vision (test fast path)") {
        val ram = mapOf("mapflags" to 1, "currentMapId" to 0, "screenState" to 0)
        val det = kotlinx.coroutines.runBlocking {
            ShopUiDetector.detect(ram, null, vision = null)
        }
        det.open shouldBe true
        det.source shouldBe "ram_pass_no_vision"
    }
})
