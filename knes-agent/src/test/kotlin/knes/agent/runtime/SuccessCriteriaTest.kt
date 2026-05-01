package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.FfPhase

class SuccessCriteriaTest : FunSpec({
    test("victory when garland HP 0 and slot dead") {
        SuccessCriteria.evaluate(FfPhase.Battle(enemyId = GARLAND_ID, enemyHp = 0, enemyDead = true)) shouldBe Outcome.Victory
    }
    test("not victory when wrong enemy") {
        SuccessCriteria.evaluate(FfPhase.Battle(enemyId = 0x01, enemyHp = 0, enemyDead = true)) shouldBe Outcome.InProgress
    }
    test("defeat on party wipe") {
        SuccessCriteria.evaluate(FfPhase.PartyDefeated) shouldBe Outcome.PartyDefeated
    }
})
