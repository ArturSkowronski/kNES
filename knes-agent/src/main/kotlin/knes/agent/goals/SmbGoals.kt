package knes.agent.goals

import knes.agent.runtime.Phase

/**
 * What Mario is allowed to want.
 *
 * A different game asks a different question of the same machinery, and Super Mario Bros
 * asks the one a System-One readout is best at: there is no plan to follow and nothing to
 * reason about, only *which button, now*, several times a second. Final Fantasy hides that
 * behind minutes of planning and composite tools; here the decision **is** the game.
 *
 * Every goal holds buttons for a span of frames rather than tapping them, because in Mario
 * the length of the press is part of the decision — the same A means a hop or a full jump
 * depending on how long it is held. The spans below are the ordinary ones for the game's
 * physics: about an eighth of a second to keep moving, about a quarter to leave the ground.
 *
 * Everything each goal reads comes from `profiles/smb.json`, which is where the addresses
 * are written down; nothing here assumes a byte the profile does not name.
 */
object SmbGoals {

    /** Frames of Right or Left: short enough that a decision still lands before Mario does. */
    const val STEP_FRAMES = 8

    /** Frames of A: long enough to clear a Goomba, short enough to come down again soon. */
    const val JUMP_FRAMES = 18

    /**
     * Turns a goal may spend achieving nothing before it comes off the menu.
     *
     * Higher than Final Fantasy's three. Mario's world moves on its own, so a press that
     * changed nothing is often just a press made mid-air, and giving up that fast would
     * rule out the goal that was about to work.
     */
    const val RETRY_LIMIT = 6

    fun all(): List<Goal> = listOf(
        stallable(
            id = "start_game",
            priority = 0,
            description = "Press START to leave the title screen and begin the level.",
            action = GoalAction("hold", mapOf("buttons" to "START", "frames" to "8")),
            applies = { it.phase == Phase.Boot },
        ),
        stallable(
            id = "run_right",
            priority = 10,
            description = "Run right: hold B to sprint and Right to move, for an eighth of a second.",
            action = GoalAction("hold", mapOf("buttons" to "Right,B", "frames" to "$STEP_FRAMES")),
            applies = { it.playing },
        ),
        stallable(
            id = "walk_right",
            priority = 11,
            description = "Walk right without sprinting — slower, and easier to stop before an edge.",
            action = GoalAction("hold", mapOf("buttons" to "Right", "frames" to "$STEP_FRAMES")),
            applies = { it.playing },
        ),
        stallable(
            id = "jump_right",
            priority = 12,
            description = "Jump forward: hold A and Right together, clearing a gap or an enemy ahead.",
            action = GoalAction("hold", mapOf("buttons" to "Right,A,B", "frames" to "$JUMP_FRAMES")),
            applies = { it.playing && it.grounded },
        ),
        stallable(
            id = "jump_up",
            priority = 13,
            description = "Jump straight up without moving forward — for a block directly overhead.",
            action = GoalAction("hold", mapOf("buttons" to "A", "frames" to "$JUMP_FRAMES")),
            applies = { it.playing && it.grounded },
        ),
        stallable(
            id = "back_off",
            priority = 20,
            description = "Move left, away from whatever is ahead, to make room for a run-up.",
            action = GoalAction("hold", mapOf("buttons" to "Left", "frames" to "$STEP_FRAMES")),
            applies = { it.playing },
        ),
        stallable(
            id = "wait",
            priority = 30,
            description = "Press nothing and let the moment pass — an enemy walks by, a platform comes back.",
            action = GoalAction("hold", mapOf("buttons" to "", "frames" to "$STEP_FRAMES")),
            applies = { it.playing },
        ),
    )

    /**
     * Whether the level is actually running.
     *
     * `gameState` is 0 on the title screen and during the attract-mode demo, which is the
     * only distinction `profiles/smb.json` classifies — so a goal that presses a direction
     * asks for it rather than assuming any phase but Boot means Mario is on his feet.
     */
    private val WorldSnapshot.playing: Boolean
        get() = phase != Phase.Boot && (ram["gameState"] ?: 0) != 0

    /**
     * Whether Mario is standing on something.
     *
     * There is no double jump in this game, so pressing A in mid-air does nothing at all —
     * and a goal that cannot work has no business being on the menu. The first run that
     * played the real cartridge fell down a pit over six turns while the model ranked
     * `walk_right` at 0.50 and `jump_right` second the whole way down; neither would have
     * helped, but only one of them was honest.
     *
     * `playerFloatState` is 0 on the ground. Established by correlation rather than taken
     * on faith: over a 160-turn run, Mario's y held still on 103 of the 106 turns where
     * the byte read 0, and moved on 50 of the 53 where it read 1.
     */
    private val WorldSnapshot.grounded: Boolean
        get() = (ram["playerFloatState"] ?: 0) == 0

    /** Same rule as Final Fantasy's: a goal that stops achieving anything leaves the menu. */
    private fun stallable(
        id: String,
        priority: Int,
        description: String,
        action: GoalAction,
        applies: (WorldSnapshot) -> Boolean,
    ) = SimpleGoal(id, priority, description, action) { world ->
        applies(world) && world.stalledOn(id) < RETRY_LIMIT
    }
}
