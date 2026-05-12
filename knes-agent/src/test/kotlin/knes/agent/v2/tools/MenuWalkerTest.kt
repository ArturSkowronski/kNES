package knes.agent.v2.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly

class MenuWalkerTest : StringSpec({
    val w = MenuWalker()

    "main/equip/char1/weapon/0 emits expected sequence" {
        w.parse("main/equip/char1/weapon/0") shouldContainExactly listOf(
            MenuTap("B"),
            MenuTap("DOWN", 2),
            MenuTap("A"),
            MenuTap("A"),
            MenuTap("A"),
        )
    }

    "main/equip/char3/weapon/1 navigates to char3 and slot 1" {
        val seq = w.parse("main/equip/char3/weapon/1")
        seq[3] shouldBe MenuTap("DOWN", 2)
        seq[4] shouldBe MenuTap("A")
        seq[5] shouldBe MenuTap("DOWN", 1)
    }

    "shop/buy/0/char1 emits buy+item0+char1" {
        w.parse("shop/buy/0/char1") shouldContainExactly listOf(
            MenuTap("A"),
            MenuTap("A"),
            MenuTap("A"),
        )
    }

    "invalid root throws" {
        runCatching { w.parse("nonsense/x") }.isFailure shouldBe true
    }

    "char out of range throws" {
        runCatching { w.parse("main/equip/char9/weapon/0") }.isFailure shouldBe true
    }
})
