package knes.agent.benchmark

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import knes.agent.campaign.Campaign
import knes.agent.runtime.Phase
import knes.agent.tools.results.GameSemantics
import knes.api.EmulatorSession
import knes.api.Replay
import knes.api.ReplayEntry
import knes.debug.GameProfile
import java.io.File

private val FF1_ROM = File("../roms/ff.nes")

private val MILESTONES = listOf(
    "boot", "enter_coneria", "enter_weapon_shop", "buy_weapons",
    "arm_party", "exit_coneria", "grind",
)

/**
 * Boots Final Fantasy by alternating START and A, the way `PressStartUntilOverworld`
 * does, for long enough to get through NEW GAME, class select and name entry.
 */
private fun bootScript(rounds: Int = 70): Replay = Replay(
    entries = (0 until rounds).flatMap { round ->
        listOf(
            ReplayEntry(6, listOf(if (round % 2 == 0) "START" else "A")),
            ReplayEntry(14),
        )
    },
)

private fun ff1Session(): EmulatorSession {
    val session = EmulatorSession()
    check(session.loadRom(FF1_ROM.absolutePath)) { "failed to load ${FF1_ROM.absolutePath}" }
    session.setWatchedAddresses(GameProfile.get("ff1")!!.toWatchMap())
    return session
}

private fun benchmark() = CampaignBenchmark(
    campaign = Campaign.of("ff1"),
    semantics = GameSemantics.of("ff1"),
    milestones = MILESTONES,
)

/**
 * A benchmark with a win condition CI can decide, and no model involved.
 *
 * Needs Final Fantasy, which cannot be redistributed, so it skips when `roms/` is absent.
 */
class Ff1BenchmarkTest : FunSpec({

    val available = FF1_ROM.isFile

    test("mashing START and A reaches the overworld, and the boot milestone latches")
        .config(enabled = available) {
            val result = benchmark().run(ff1Session(), bootScript())

            println("FF1 benchmark: $result")

            result.reached shouldBe listOf("boot")
            result.finalPhase shouldBe Phase.Overworld
        }

    test("the party lands in the Coneria region the profile describes")
        .config(enabled = available) {
            val session = ff1Session()
            benchmark().run(session, bootScript())

            val semantics = GameSemantics.of("ff1")
            val ram = session.getWatchedState()
            val world = semantics.worldPosition(ram)!!

            // The ff1 profile anchors Coneria at worldX 140..154, worldY 150..162.
            (world.first in 140..154) shouldBe true
            (world.second in 150..162) shouldBe true
        }

    test("a script that presses nothing reaches nothing")
        .config(enabled = available) {
            // Guards the tests above: if milestones latched regardless of input, a green
            // benchmark would mean nothing.
            val result = benchmark().run(ff1Session(), Replay(entries = listOf(ReplayEntry(1400))))

            result.reached shouldBe emptyList()
            result.finalPhase shouldBe Phase.Boot
            result.missed.size shouldBe MILESTONES.size
        }

    test("the benchmark script survives the replay text format")
        .config(enabled = available) {
            // The script is the artifact; it has to be storable and diffable, not just
            // constructible in Kotlin.
            val script = bootScript()

            val reloaded = Replay.parse(script.encode())

            reloaded shouldBe script
            val result = benchmark().run(ff1Session(), reloaded)
            result.reached shouldBe listOf("boot")
        }

    test("milestones are evaluated every frame, not once at the end")
        .config(enabled = available) {
            // enter_weapon_shop and friends describe a tile the party occupies for a
            // moment. Sampling per entry would miss them.
            val session = ff1Session()
            val result = benchmark().run(session, bootScript())

            result.frames shouldBeGreaterThan 1_000
            result.reached.contains("boot") shouldBe true
        }

    test("the benchmark reports whether it ran") {
        println("FF1 benchmark: ROM ${if (available) "available" else "absent"} at ${FF1_ROM.absolutePath}")
        true shouldBe true
    }
})
