package knes.agent.runtime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WatchdogTest : StringSpec({
    "Overworld threshold is 5, MenuStuck is 3" {
        val w = Watchdog()
        repeat(5) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.Overworld) shouldBe true
        w.reset()
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

    "a whitelisted phase does not tick the counter even with static RAM" {
        // No phase is whitelisted by default — nothing the profile can currently report
        // is a legitimate waiting state. The mechanism still has to work for when one is.
        val w = Watchdog(staticWhitelist = setOf(Phase.MenuStuck))
        repeat(20) { w.observe(Phase.MenuStuck, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.MenuStuck) shouldBe false
        w.counter() shouldBe 0
    }

    "the default whitelist is empty, so a static phase does tick" {
        val w = Watchdog()
        repeat(3) { w.observe(Phase.MenuStuck, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.MenuStuck) shouldBe true
    }

    "RAM change resets counter" {
        val w = Watchdog()
        repeat(4) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.observe(Phase.Overworld, ramHash = 99, skillProgress = false)
        w.counter() shouldBe 0
    }
})
