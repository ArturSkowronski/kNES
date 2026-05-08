package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FrameChangeDetectorTest : FunSpec({
    fun sprite(slot: Int, tileId: Int, x: Int, y: Int) =
        FrameChangeDetector.SpriteSlot(slot, tileId, x, y)

    test("first frame always triggers") {
        val det = FrameChangeDetector()
        val sprites = setOf(sprite(0, 0xA0, 120, 112))
        det.shouldScan(currOam = sprites, currPixels = ByteArray(0)) shouldBe true
    }

    test("party-only motion does not trigger") {
        val det = FrameChangeDetector()
        val a = setOf(sprite(0, 0xA0, 120, 112), sprite(1, 0xA1, 128, 112))
        val b = setOf(sprite(0, 0xA0, 124, 112), sprite(1, 0xA1, 132, 112))
        det.shouldScan(a, ByteArray(0))   // first frame, sets baseline
        det.shouldScan(b, ByteArray(0)) shouldBe false
    }

    test("new sprite slot triggers") {
        val det = FrameChangeDetector()
        val a = setOf(sprite(0, 0xA0, 120, 112))
        val b = a + sprite(8, 0xC4, 80, 80)   // new NPC sprite
        det.shouldScan(a, ByteArray(0))
        det.shouldScan(b, ByteArray(0)) shouldBe true
    }

    test("oam-null falls back to pixel hash") {
        val det = FrameChangeDetector()
        val pixA = ByteArray(256 * 240) { (it % 16).toByte() }
        val pixB = pixA.copyOf().also { it[5000] = 99 }
        det.shouldScan(currOam = null, currPixels = pixA)   // baseline
        det.shouldScan(null, pixB) shouldBe true
    }

    test("oam-null sticks to pixel-hash for the rest of session") {
        val det = FrameChangeDetector()
        det.shouldScan(currOam = null, currPixels = ByteArray(256 * 240))
        det.mode shouldBe FrameChangeDetector.Mode.PIXEL_HASH
        det.shouldScan(currOam = setOf(sprite(0, 0xA0, 0, 0)), currPixels = ByteArray(256 * 240))
        det.mode shouldBe FrameChangeDetector.Mode.PIXEL_HASH
    }
})
