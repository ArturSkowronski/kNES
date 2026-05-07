package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.skills.SkillResult
import java.nio.file.Files
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind

class BridgeTickTest : FunSpec({

    fun emptyLandmarks() = LandmarkMemory(file =
        Files.createTempFile("bt-", ".json").toFile().apply { deleteOnExit() })

    test("discover returns Discovered => tofEntranceTile is cached, return Continue") {
        val landmarks = emptyLandmarks()
        val tick = BridgeTick(
            discover = {
                landmarks.recordIfNew(Landmark(id = "temple-of-fiends-entry",
                    kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137, note = ""))
                SkillResult(true, "Discovered (211,137)")
            },
            walk = { _, _ -> SkillResult(false, "should not be called") },
            landmarks = landmarks,
        )
        tick.tofEntranceTile shouldBe null
        val r = tick.run(ram = mapOf("worldX" to 150, "worldY" to 150))
        r shouldBe BridgeTick.TickOutcome.Continue
        tick.tofEntranceTile shouldBe (211 to 137)
    }

    test("3 NotVisible discovers => BailToLlm and sticky") {
        val landmarks = emptyLandmarks()
        val tick = BridgeTick(
            discover = { SkillResult(true, "NotVisible") },
            walk = { _, _ -> SkillResult(false, "should not be called") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 150, "worldY" to 150)
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm   // sticky
    }

    test("ClassifyFailed counts toward discover cap (combined with NotVisible)") {
        val landmarks = emptyLandmarks()
        val responses = listOf("ClassifyFailed: oops", "NotVisible", "ClassifyFailed: again")
        var idx = 0
        val tick = BridgeTick(
            discover = { SkillResult(true, responses[idx++]) },
            walk = { _, _ -> SkillResult(false, "n/a") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 100, "worldY" to 100)
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm
    }

    test("adjacent (Manhattan <= 1) returns Reached") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val tick = BridgeTick(
            discover = { SkillResult(false, "should not be called") },
            walk = { _, _ -> SkillResult(false, "should not be called") },
            landmarks = landmarks,
        )
        // worldX=210, worldY=137: Manhattan = 1
        tick.run(mapOf("worldX" to 210, "worldY" to 137)) shouldBe BridgeTick.TickOutcome.Reached
    }

    test("walk returns 'encounter triggered' does NOT increment stall") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val tick = BridgeTick(
            discover = { SkillResult(false, "n/a") },
            walk = { _, _ -> SkillResult(true, "encounter triggered after 3 steps") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 180, "worldY" to 137)
        repeat(10) { tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue }
        tick.bailed shouldBe false
    }

    test("walk failure 5x consecutive => BailToLlm") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val tick = BridgeTick(
            discover = { SkillResult(false, "n/a") },
            walk = { _, _ -> SkillResult(false, "did not reach (211,137) in 64 steps") },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 180, "worldY" to 137)
        repeat(4) { tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue }
        tick.run(ram) shouldBe BridgeTick.TickOutcome.BailToLlm
    }

    test("successful walk message resets walkStallCount") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = ""))
        val responses = ArrayDeque(listOf<SkillResult>(
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),
            SkillResult(true, "reached (200,137) in 8 steps"),
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),
            SkillResult(false, "did not reach"),
        ))
        val tick = BridgeTick(
            discover = { SkillResult(false, "n/a") },
            walk = { _, _ -> responses.removeFirst() },
            landmarks = landmarks,
        )
        val ram = mapOf("worldX" to 180, "worldY" to 137)
        repeat(7) { tick.run(ram) shouldBe BridgeTick.TickOutcome.Continue }
        tick.bailed shouldBe false
    }

    test("hydrates tofEntranceTile from landmarks at construction") {
        val landmarks = emptyLandmarks()
        landmarks.record(Landmark(id = "temple-of-fiends-entry",
            kind = LandmarkKind.TEMPLE_ENTRY, worldX = 211, worldY = 137,
            note = "pre-existing"))
        val tick = BridgeTick(
            discover = { SkillResult(false, "should not be called") },
            walk = { _, _ -> SkillResult(true, "moved") },
            landmarks = landmarks,
        )
        tick.tofEntranceTile shouldBe (211 to 137)
    }
})
