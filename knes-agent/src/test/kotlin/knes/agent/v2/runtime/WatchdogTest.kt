package knes.agent.v2.runtime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WatchdogTest : StringSpec({
    "Overworld threshold is 5, MenuStuck is 3" {
        val w = Watchdog()
        // Five identical RAM hashes + zero skill progress in Overworld → signal
        repeat(5) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.Overworld) shouldBe true
        w.reset()
        // Three is enough for MenuStuck
        repeat(3) { w.observe(Phase.MenuStuck, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.MenuStuck) shouldBe true
    }

    "skill progress resets counter" {
        val w = Watchdog()
        repeat(4) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.observe(Phase.Overworld, ramHash = 42, skillProgress = true)
        w.stuckSignal(Phase.Overworld) shouldBe false
        w.counter() shouldBe 0
    }

    "Dialog whitelist does not tick counter even with static RAM" {
        val w = Watchdog()
        repeat(20) { w.observe(Phase.Dialog, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.Dialog) shouldBe false
        w.counter() shouldBe 0
    }

    "RAM change resets counter" {
        val w = Watchdog()
        repeat(4) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.observe(Phase.Overworld, ramHash = 99, skillProgress = false)
        w.counter() shouldBe 0
    }
})
