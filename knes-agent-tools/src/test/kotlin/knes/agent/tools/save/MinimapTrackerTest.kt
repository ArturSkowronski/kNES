package knes.agent.tools.save

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MinimapTrackerTest : FunSpec({
    test("empty tracker round-trips via base64") {
        val tracker = MinimapTracker()
        val snap = tracker.toSnapshot()
        val restored = MinimapTracker.fromSnapshot(snap)
        restored.width shouldBe 32
        restored.height shouldBe 32
        restored.isVisited(0, 0) shouldBe false
    }

    test("markVisited and isVisited are consistent across base64 round-trip") {
        val tracker = MinimapTracker()
        tracker.markVisited(3, 7)
        tracker.markVisited(31, 31)
        val restored = MinimapTracker.fromSnapshot(tracker.toSnapshot())
        restored.isVisited(3, 7) shouldBe true
        restored.isVisited(31, 31) shouldBe true
        restored.isVisited(0, 0) shouldBe false
    }

    test("out-of-bounds coords are ignored, not crashing") {
        val tracker = MinimapTracker()
        tracker.markVisited(-1, 0)
        tracker.markVisited(0, 32)
        tracker.markVisited(32, 0)
        val restored = MinimapTracker.fromSnapshot(tracker.toSnapshot())
        restored.isVisited(0, 0) shouldBe false
    }
})
