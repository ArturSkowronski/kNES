package knes.debug.actions.ff1

import knes.debug.ActionController
import knes.debug.ActionResult
import knes.debug.GameAction

class BattleFightAll : GameAction {
    override val id = "battle_fight_all"
    override val description = "All alive characters use FIGHT until battle ends"
    override val profileId = "ff1"

    companion object {
        private const val SCREEN_STATE_BATTLE = 0x68
        private const val MAX_ROUNDS = 30
        private const val STATUS_DEAD_BIT = 1

        init {
            GameAction.register(BattleFightAll())
        }

        fun init() {}
    }

    override fun canExecute(state: Map<String, Int>): Boolean {
        return state["screenState"] == SCREEN_STATE_BATTLE
    }

    override fun execute(controller: ActionController): ActionResult {
        var rounds = 0

        while (rounds < MAX_ROUNDS) {
            val state = controller.readState()
            if (state["screenState"] != SCREEN_STATE_BATTLE) break

            for (i in 1..4) {
                val status = state["char${i}_status"] ?: 0
                if (status and STATUS_DEAD_BIT != 0) continue
                controller.tap("A", count = 1, pressFrames = 5, gapFrames = 40)
                controller.tap("A", count = 1, pressFrames = 5, gapFrames = 40)
            }

            controller.waitFrames(300)
            controller.tap("A", count = 4, pressFrames = 5, gapFrames = 40)
            rounds++
        }

        controller.tap("A", count = 10, pressFrames = 5, gapFrames = 40)
        controller.waitFrames(60)

        val finalState = controller.readState()
        val won = finalState["screenState"] != SCREEN_STATE_BATTLE

        return ActionResult(
            success = won,
            message = if (won) "Battle complete in $rounds rounds" else "Battle not finished after $MAX_ROUNDS rounds",
            state = finalState,
            screenshot = controller.screenshot()
        )
    }
}
