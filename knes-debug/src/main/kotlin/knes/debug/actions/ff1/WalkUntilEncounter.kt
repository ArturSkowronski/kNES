package knes.debug.actions.ff1

import knes.debug.ActionController
import knes.debug.ActionResult
import knes.debug.GameAction

class WalkUntilEncounter : GameAction {
    override val id = "walk_until_encounter"
    override val description = "Walk randomly on overworld until a battle triggers"
    override val profileId = "ff1"

    companion object {
        private const val SCREEN_STATE_BATTLE = 0x68
        private const val MAX_STEPS = 500
        private const val FRAMES_PER_STEP = 16
        private const val GAP_BETWEEN_STEPS = 2
        private const val STUCK_THRESHOLD = 3

        private val DIRECTIONS = listOf("DOWN", "LEFT", "RIGHT", "UP")

        private val REVERSE = mapOf(
            "UP" to "DOWN", "DOWN" to "UP",
            "LEFT" to "RIGHT", "RIGHT" to "LEFT"
        )

        init {
            GameAction.register(WalkUntilEncounter())
        }

        fun init() {}
    }

    override fun canExecute(state: Map<String, Int>): Boolean {
        return state["screenState"] != SCREEN_STATE_BATTLE
    }

    override fun execute(controller: ActionController): ActionResult {
        var steps = 0
        var directionIndex = 0
        var stuckCount = 0
        var lastWorldX = -1
        var lastWorldY = -1
        var wasOnOverworld = true

        while (steps < MAX_STEPS) {
            val state = controller.readState()

            if (state["screenState"] == SCREEN_STATE_BATTLE) {
                return ActionResult(
                    success = true,
                    message = "Battle triggered after $steps steps",
                    state = state,
                    screenshot = controller.screenshot()
                )
            }

            val worldX = state["worldX"] ?: 0
            val worldY = state["worldY"] ?: 0
            val localX = state["localX"] ?: 0
            val localY = state["localY"] ?: 0
            val onOverworld = localX == 0 && localY == 0

            // Detect entering a location: localX/Y went from 0 to non-zero
            if (wasOnOverworld && !onOverworld) {
                // Entered a town/building — reverse direction to walk back out
                val currentDir = DIRECTIONS[directionIndex % DIRECTIONS.size]
                val reverseDir = REVERSE[currentDir] ?: "DOWN"

                // Walk back out with many steps
                for (i in 0 until 20) {
                    controller.step(listOf(reverseDir), FRAMES_PER_STEP)
                    controller.waitFrames(GAP_BETWEEN_STEPS)
                }

                // Blacklist this direction by advancing to next
                directionIndex++
                stuckCount = 0

                // Re-read state after exiting
                val exitState = controller.readState()
                val exitLocalX = exitState["localX"] ?: 0
                val exitLocalY = exitState["localY"] ?: 0
                wasOnOverworld = exitLocalX == 0 && exitLocalY == 0
                lastWorldX = exitState["worldX"] ?: 0
                lastWorldY = exitState["worldY"] ?: 0
                steps += 20
                continue
            }

            wasOnOverworld = onOverworld

            // Detect stuck on overworld: worldX/Y unchanged
            if (onOverworld) {
                if (worldX == lastWorldX && worldY == lastWorldY) {
                    stuckCount++
                    if (stuckCount >= STUCK_THRESHOLD) {
                        directionIndex++
                        stuckCount = 0
                    }
                } else {
                    stuckCount = 0
                }
            }

            lastWorldX = worldX
            lastWorldY = worldY

            val direction = DIRECTIONS[directionIndex % DIRECTIONS.size]
            controller.step(listOf(direction), FRAMES_PER_STEP)
            controller.waitFrames(GAP_BETWEEN_STEPS)

            steps++
        }

        val finalState = controller.readState()
        return ActionResult(
            success = false,
            message = "No encounter after $MAX_STEPS steps",
            state = finalState,
            screenshot = controller.screenshot()
        )
    }
}
