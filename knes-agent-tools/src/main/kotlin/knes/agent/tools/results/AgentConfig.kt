package knes.agent.tools.results

import knes.debug.GoalRule
import knes.debug.ProfileAgent

/**
 * What the agent may want in this game, as the runtime sees it.
 *
 * A neutral view of the profile's `"agent"` section, so the agent module never has to name
 * a type from the profile loader — the same arrangement [GameSemantics] already has for
 * RAM interpretation. Everything here came out of `profiles/<id>.json`; nothing in it is
 * written in Kotlin.
 */
class AgentConfig private constructor(private val agent: ProfileAgent) {

    /** What the decision model is asked, once per turn. Per game, because the words matter. */
    val question: String get() = agent.question

    /** The frame a pixel-reading model should look at, as `WxH`, or null to leave it alone. */
    val frame: String? get() = agent.frame

    /** The goals this game declares, in the order the profile lists them. */
    val goals: List<GoalSpec> by lazy { agent.goals.map(::GoalSpec) }

    /** The state lines this snapshot can fill in; a field the game lacks says nothing. */
    fun stateLines(ram: Map<String, Int>): List<String> = agent.stateLines(ram)

    /** Whether this game keeps a map, and wants it in the state. */
    val hasMap: Boolean get() = agent.map?.inState == true

    /**
     * A small ASCII picture of what is around the player, with the hazards marked.
     *
     * `#` is something solid, `.` is open air, `M` is the player and `E` is an enemy. The
     * player sits at a fixed spot in the window so the picture reads the same from one turn
     * to the next, and the window reaches further ahead than behind, because that is where
     * the decision is.
     *
     * Empty when the profile declares no map, or when the position is unknown. [read] takes
     * a start address and a length and hands back that many bytes.
     */
    fun mapLines(read: (Int, Int) -> List<Int>, x: Int, y: Int): List<String> {
        val map = agent.map ?: return emptyList()
        val buffer = runCatching { read(map.baseAddress, map.pages * map.rows * map.cols) }
            .getOrElse { return emptyList() }

        val grid = MutableList(map.height) { MutableList(map.width) { map.empty } }
        for (row in 0 until map.height) {
            for (column in 0 until map.width) {
                val sampleX = x + (column - map.left) * map.tile
                val sampleY = y + (row - map.up) * map.tile
                val address = map.addressOf(sampleX, sampleY) ?: continue
                val index = address - map.baseAddress
                if (index in buffer.indices && buffer[index] != 0) grid[row][column] = map.solid
            }
        }

        agent.sprites?.let { sprites ->
            for (slot in 0 until sprites.count) {
                val active = read(sprites.activeAddress(slot), 1).firstOrNull() ?: 0
                val kind = read(sprites.kindAddress(slot), 1).firstOrNull() ?: 0
                if (active == 0 || kind == 0) continue
                val enemyX = (read(sprites.xHighAddress(slot), 1).firstOrNull() ?: 0) * 256 +
                    (read(sprites.xLowAddress(slot), 1).firstOrNull() ?: 0)
                val enemyY = read(sprites.yAddress(slot), 1).firstOrNull() ?: 0
                val column = map.left + Math.round((enemyX - x) / map.tile.toFloat())
                val row = map.up + Math.round((enemyY - y) / map.tile.toFloat())
                if (row in 0 until map.height && column in 0 until map.width) {
                    grid[row][column] = map.enemy
                }
            }
        }
        grid[map.up][map.left] = map.player
        return grid.map { it.joinToString("") }
    }

    companion object {
        /** Null when the profile declares no agent section, which is not an error. */
        fun of(profileId: String?): AgentConfig? =
            profileId?.let { ProfileAgent.get(it) }?.let(::AgentConfig)
    }
}

/**
 * One declared goal, with its conditions already reduced to a question the runtime can ask.
 *
 * The runtime adds the two things a profile cannot express because they need history: the
 * stall rule, and whether the Advisor's current plan step is dispatchable.
 */
class GoalSpec internal constructor(private val rule: GoalRule) {
    val id: String get() = rule.id
    val priority: Int get() = rule.priority
    val description: String get() = rule.description
    val tool: String get() = rule.tool
    val args: Map<String, String> get() = rule.args

    /** Turns this may spend changing nothing before it comes off the menu. */
    val retryLimit: Int get() = rule.retryLimit

    /** Also offer it after this many turns with nothing moving, whatever the phase says. */
    val alsoWhenStuckFor: Int? get() = rule.alsoWhenStuckFor

    /** Whether this goal dispatches whatever the Advisor's plan says next. */
    val followsPlan: Boolean get() = rule.tool == GoalRule.PLAN_STEP

    /** Phase and RAM conditions only; the runtime owns everything that needs history. */
    fun applies(phase: String, ram: Map<String, Int>): Boolean = rule.applies(phase, ram)

    /** Whether a plan step naming [tool] is allowed in [phase]. */
    fun guardAllows(tool: String, phase: String): Boolean = rule.guardAllows(tool, phase)
}
