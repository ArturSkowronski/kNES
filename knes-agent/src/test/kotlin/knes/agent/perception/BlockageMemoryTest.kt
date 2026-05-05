package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.Duration

class BlockageMemoryTest : FunSpec({
    test("record + recentFailures + runStartDirections round-trip") {
        val tmp = Files.createTempFile("blockages", ".json").toFile().apply { deleteOnExit() }
        var now = Instant.parse("2026-05-05T12:00:00Z")
        val mem = BlockageMemory(file = tmp, clock = Clock.fixed(now, ZoneOffset.UTC))

        mem.record(runId = "run-1", from = 145 to 153, attemptedTo = "148,151",
            result = "BFS no path within viewport")
        mem.record(runId = "run-2", from = 16 to 17, attemptedTo = "exit",
            result = "exitInterior maxSteps reached, mapId=8")
        mem.recordRunStartDirection("run-1", "N")
        mem.recordRunStartDirection("run-2", "N")

        mem.recentFailures(within = Duration.ofMinutes(10)) shouldHaveSize 2
        mem.pathTriedRecentDirections(k = 3) shouldContain "N"

        mem.save()
        val reload = BlockageMemory(file = tmp)
        reload.recentFailures(within = Duration.ofDays(365)) shouldHaveSize 2
        reload.pathTriedRecentDirections(k = 3) shouldContain "N"
    }

    test("recentlyFailedTargets returns attemptedTo strings within window") {
        val tmp = Files.createTempFile("blockages", ".json").toFile().apply { deleteOnExit() }
        val now = Instant.parse("2026-05-05T12:00:00Z")
        val mem = BlockageMemory(file = tmp, clock = Clock.fixed(now, ZoneOffset.UTC))
        mem.record("r", 0 to 0, "148,151", "x")
        mem.recentlyFailedTargets(Duration.ofMinutes(10)) shouldContain "148,151"
    }
})
