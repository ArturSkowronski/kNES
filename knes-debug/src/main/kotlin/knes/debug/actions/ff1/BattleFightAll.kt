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
        private const val SCREEN_STATE_POST_BATTLE = 0x63
        private const val MAX_ROUNDS = 30
        private const val MAX_POST_BATTLE_TAPS = 30
        private const val STATUS_DEAD_BIT = 1

        init {
            GameAction.register(BattleFightAll())
        }

        fun init() {}
    }

    override fun canExecute(state: Map<String, Int>): Boolean {
        val ss = state["screenState"] ?: return false
        return ss == SCREEN_STATE_BATTLE || ss == SCREEN_STATE_POST_BATTLE
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

        // Dismiss PostBattle results screen(s): multi-stage XP / level-up / gold.
        // Tap A one screen at a time with a frame buffer between, until the engine
        // transitions out of both Battle and PostBattle phases.
        var postTaps = 0
        while (postTaps < MAX_POST_BATTLE_TAPS) {
            val s = controller.readState()
            val ss = s["screenState"] ?: 0
            if (ss != SCREEN_STATE_BATTLE && ss != SCREEN_STATE_POST_BATTLE) break
            controller.tap("A", count = 1, pressFrames = 5, gapFrames = 30)
            controller.waitFrames(30)
            postTaps++
        }

        val finalState = controller.readState()
        val finalSs = finalState["screenState"] ?: 0
        val cleared = finalSs != SCREEN_STATE_BATTLE && finalSs != SCREEN_STATE_POST_BATTLE

        return ActionResult(
            success = cleared,
            message = if (cleared) "Battle complete in $rounds rounds, dismissed PostBattle in $postTaps taps"
                      else "Battle/PostBattle not cleared after $MAX_ROUNDS rounds + $postTaps post-taps",
            state = finalState,
            screenshot = controller.screenshot()
        )
    }
}
