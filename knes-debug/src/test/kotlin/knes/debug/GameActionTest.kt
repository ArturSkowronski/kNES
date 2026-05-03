package knes.debug

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import knes.debug.actions.ActionRegistry
import knes.debug.actions.ff1.BattleFightAll

class GameActionTest : FunSpec({

    test("ActionResult captures success with message and state") {
        val result = ActionResult(
            success = true,
            message = "Battle won in 3 rounds",
            state = mapOf("char1_hpLow" to 25, "goldLow" to 200),
            screenshot = null
        )
        result.success shouldBe true
        result.message shouldBe "Battle won in 3 rounds"
        result.state["char1_hpLow"] shouldBe 25
    }

    test("ActionResult captures failure") {
        val result = ActionResult(
            success = false,
            message = "Not in battle",
            state = mapOf("screenState" to 0)
        )
        result.success shouldBe false
    }

    test("register and retrieve actions by profile ID") {
        val action = object : GameAction {
            override val id = "test_action"
            override val description = "A test action"
            override val profileId = "test_profile"
            override fun canExecute(state: Map<String, Int>) = true
            override fun execute(controller: ActionController): ActionResult {
                return ActionResult(true, "done", controller.readState())
            }
        }

        GameAction.register(action)
        val actions = GameAction.listForProfile("test_profile")
        actions.size shouldBe 1
        actions[0].id shouldBe "test_action"
    }

    test("get specific action by profile and action ID") {
        val action = GameAction.get("test_profile", "test_action")
        action shouldNotBe null
        action!!.id shouldBe "test_action"
    }

    test("list returns empty for unknown profile") {
        val actions = GameAction.listForProfile("nonexistent")
        actions.size shouldBe 0
    }

    test("FF1 BattleFightAll: canExecute checks screenState") {
        val action = BattleFightAll()
        action.canExecute(mapOf("screenState" to 0x68)) shouldBe true   // battle
        action.canExecute(mapOf("screenState" to 0x63)) shouldBe true   // PostBattle (V2.4.3: skill dismisses PostBattle modal)
        action.canExecute(mapOf("screenState" to 0x00)) shouldBe false
        action.canExecute(emptyMap()) shouldBe false
    }

    test("FF1 BattleFightAll: registered under ff1 profile") {
        BattleFightAll.init()
        val actions = GameAction.listForProfile("ff1")
        val battleAction = actions.find { it.id == "battle_fight_all" }
        battleAction shouldNotBe null
        battleAction!!.profileId shouldBe "ff1"
    }

    test("ActionRegistry.ensureLoaded triggers FF1 action registration") {
        ActionRegistry.ensureLoaded("ff1")
        val actions = GameAction.listForProfile("ff1")
        actions.any { it.id == "battle_fight_all" } shouldBe true
    }

    test("ActionRegistry.ensureLoaded is safe for unknown profiles") {
        ActionRegistry.ensureLoaded("unknown_game")
    }

    test("BattleFightAll executes correctly with mock controller") {
        var tapCount = 0
        var waitCount = 0
        var stateCallCount = 0

        val mockController = object : ActionController {
            // V2.4.3: skill now dismisses both Battle (0x68) and PostBattle (0x63).
            // Mock progression: 3 battle states → some PostBattle states → cleared (0x00).
            override fun readState(): Map<String, Int> {
                stateCallCount++
                return when {
                    stateCallCount <= 3 -> mapOf(
                        "screenState" to 0x68,
                        "char1_status" to 0,
                        "char2_status" to 0,
                        "char3_status" to 0,
                        "char4_status" to 0
                    )
                    stateCallCount <= 6 -> mapOf("screenState" to 0x63)  // PostBattle modal taps
                    else -> mapOf("screenState" to 0x00)                  // cleared to overworld
                }
            }

            override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int) {
                tapCount += count
            }

            override fun step(buttons: List<String>, frames: Int) {}
            override fun waitFrames(frames: Int) { waitCount++ }
            override fun screenshot(): String? = null
        }

        val action = BattleFightAll()
        val result = action.execute(mockController)

        result.success shouldBe true
        result.message shouldContain "Battle complete"
        tapCount shouldBeGreaterThan 0
        waitCount shouldBeGreaterThan 0
    }
})
