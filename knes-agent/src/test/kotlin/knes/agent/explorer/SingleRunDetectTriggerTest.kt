package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import knes.agent.perception.FfPhase

/**
 * Per-run novelty for `NewInteriorEntered`. The pre-fix gate was
 * `!interiorMemory.hasMapBeenSeen(mapId)` — persistent across sessions, so
 * once a map was entered in any run, the trigger never fired again. With 3
 * pre-seeded maps, Haiku was never called and Phase 1.5 stayed unvalidated.
 *
 * Fix: gate on `mapId !in novelMapIdsThisRun`. Caller adds to the set on
 * first fire — subsequent steps in the SAME run for the same map don't
 * re-trigger, so we don't spam Haiku, but each run gets one classification
 * per map.
 */
class SingleRunDetectTriggerTest : FunSpec({

    test("first interior entry fires NewInteriorEntered") {
        val novel = mutableSetOf<Int>()
        val trigger = SingleRun.detectTrigger(
            phase = FfPhase.Indoors(mapId = 1, localX = 0, localY = 0),
            ram = emptyMap(),
            novelMapIdsThisRun = novel,
        )
        trigger.shouldBeInstanceOf<Trigger.NewInteriorEntered>()
        (trigger as Trigger.NewInteriorEntered).mapId shouldBe 1
    }

    test("re-entry to same map within run does not re-trigger") {
        val novel = mutableSetOf(1)  // already seen this run
        val trigger = SingleRun.detectTrigger(
            phase = FfPhase.Indoors(mapId = 1, localX = 0, localY = 0),
            ram = emptyMap(),
            novelMapIdsThisRun = novel,
        )
        trigger shouldBe null
    }

    test("different map in same run still triggers (bounded per-map)") {
        val novel = mutableSetOf(1)  // mapId=1 already fired
        val trigger = SingleRun.detectTrigger(
            phase = FfPhase.Indoors(mapId = 8, localX = 0, localY = 0),
            ram = emptyMap(),
            novelMapIdsThisRun = novel,
        )
        trigger.shouldBeInstanceOf<Trigger.NewInteriorEntered>()
        (trigger as Trigger.NewInteriorEntered).mapId shouldBe 8
    }

    test("ignores cross-session interiorMemory — fires even for previously-seen maps") {
        // The whole point of the fix: a map seen in any prior session must still
        // trigger once per current run. We model that by passing an empty novel set
        // — the function MUST NOT look at interiorMemory.hasMapBeenSeen anymore.
        val trigger = SingleRun.detectTrigger(
            phase = FfPhase.Indoors(mapId = 1, localX = 0, localY = 0),
            ram = emptyMap(),
            novelMapIdsThisRun = emptySet(),
        )
        trigger.shouldBeInstanceOf<Trigger.NewInteriorEntered>()
    }

    test("Battle phase always triggers BattleEntered (independent of novelty set)") {
        val trigger = SingleRun.detectTrigger(
            phase = FfPhase.Battle(enemyId = 0, enemyHp = 0, enemyDead = false),
            ram = emptyMap(),
            novelMapIdsThisRun = setOf(1, 8),
        )
        trigger shouldBe Trigger.BattleEntered
    }

    test("Overworld phase yields no trigger") {
        val trigger = SingleRun.detectTrigger(
            phase = FfPhase.Overworld(x = 146, y = 158),
            ram = emptyMap(),
            novelMapIdsThisRun = emptySet(),
        )
        trigger shouldBe null
    }
})
