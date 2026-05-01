package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ScreenshotPolicyTest : FunSpec({

    test("attaches on first turn") {
        ScreenshotPolicy().shouldAttach(previous = null, current = FfPhase.TitleOrMenu) shouldBe true
    }

    test("attaches on phase change") {
        val policy = ScreenshotPolicy()
        val previous = FfPhase.Overworld(x = 100, y = 200)
        val current = FfPhase.Battle(enemyId = 5, enemyHp = 100, enemyDead = false)

        policy.shouldAttach(previous = previous, current = current) shouldBe true
    }

    test("skips when same class with different field values") {
        val policy = ScreenshotPolicy()
        val previous = FfPhase.Battle(enemyId = 0x7C, enemyHp = 100, enemyDead = false)
        val current = FfPhase.Battle(enemyId = 0x7C, enemyHp = 80, enemyDead = false)

        policy.shouldAttach(previous = previous, current = current) shouldBe false
    }
})
