package knes.agent.goals

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.runtime.Phase

class WorldSnapshotTest : FunSpec({

    test("the RAM digest the turn loop already builds is read straight back") {
        val ram = WorldSnapshot.parseRam("smPlayerX=18,smPlayerY=14,gold=400,menuCursor=0")
        ram["smPlayerX"] shouldBe 18
        ram["gold"] shouldBe 400
        ram.size shouldBe 4
    }

    test("a field that is not an integer is dropped, not guessed at as zero") {
        val ram = WorldSnapshot.parseRam("smPlayerX=18,mode=town,broken,worldY=")
        ram shouldBe mapOf("smPlayerX" to 18)
    }

    test("negative values survive — some watched fields are signed") {
        WorldSnapshot.parseRam("delta=-3")["delta"] shouldBe -3
    }

    test("a missing position reads as the origin rather than throwing mid-turn") {
        val world = WorldSnapshot(1, Phase.Town, emptyMap(), null, "m", emptyList())
        world.sm shouldBe (0 to 0)
    }

    test("a game with no overworld simply has none, rather than reporting the origin") {
        // Only Final Fantasy watches worldX/worldY. Mario reporting (0,0) would read as a
        // real position on a map he is not on.
        WorldSnapshot(1, Phase.Overworld, mapOf("playerX" to 40), null, "m", emptyList()).world shouldBe null
        WorldSnapshot(1, Phase.Town, mapOf("worldX" to 147, "worldY" to 155), null, "m", emptyList())
            .world shouldBe (147 to 155)
    }

    test("the profile's own position wins over guessing at field names") {
        val world = WorldSnapshot(
            1, Phase.Overworld, mapOf("smPlayerX" to 9, "smPlayerY" to 9),
            null, "m", emptyList(), position = 40 to 176,
        )
        world.sm shouldBe (40 to 176)
    }

    test("recentFailures counts the turns that moved the game no further") {
        val world = WorldSnapshot(
            1, Phase.Town, emptyMap(), null, "m",
            listOf(TurnEffect("Ok", true), TurnEffect("Fail", false), TurnEffect("Reject", false), TurnEffect("Ok", true)),
        )
        world.recentFailures shouldBe 2
    }
})

private fun moved(vararg outcomes: Pair<String, Boolean>) = WorldSnapshot(
    1, Phase.Town, emptyMap(), null, "m", outcomes.map { TurnEffect(it.first, it.second) },
)

class TurnEffectTest : FunSpec({

    test("the count is the unbroken run up to now, not the total") {
        moved("Fail" to false, "Fail" to false, "Ok" to true, "Fail" to false).turnsWithoutProgress shouldBe 1
        moved("Ok" to true, "Fail" to false, "Fail" to false, "Fail" to false).turnsWithoutProgress shouldBe 3
    }

    test("a turn that reached somewhere new resets it") {
        moved("Fail" to false, "Fail" to false, "Ok" to true).turnsWithoutProgress shouldBe 0
    }

    test("an Ok that reached nowhere new is not progress — the whole reason this counts effect") {
        moved("Ok" to false, "Ok" to false).turnsWithoutProgress shouldBe 2
    }

    test("a Reject counts too — it moved the game no further than a Fail did") {
        moved("Reject" to false, "Reject" to false).turnsWithoutProgress shouldBe 2
    }

    test("no history at all is no failures") {
        WorldSnapshot(1, Phase.Town, emptyMap(), null, "m", emptyList()).turnsWithoutProgress shouldBe 0
    }

    test("the state reads out whether the turn reached anywhere, so the model can see nothing is happening") {
        TurnEffect("Ok", newGround = false).toString() shouldBe "Ok (nowhere new)"
        TurnEffect("Ok", newGround = true).toString() shouldBe "Ok (somewhere new)"
        TurnEffect("Fail", newGround = false).toString() shouldBe "Fail"
    }
})
