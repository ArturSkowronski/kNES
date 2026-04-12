package knes.debug

/**
 * A game-specific automation action that plays like a real NES player:
 * it can only read RAM state (like seeing the screen) and press buttons.
 * No memory writes, no save states, no cheats.
 */
interface GameAction {
    val id: String
    val description: String
    val profileId: String

    fun canExecute(state: Map<String, Int>): Boolean
    fun execute(controller: ActionController): ActionResult

    companion object {
        private val actions: MutableMap<String, MutableList<GameAction>> = mutableMapOf()

        fun register(action: GameAction) {
            actions.getOrPut(action.profileId) { mutableListOf() }.let { list ->
                list.removeAll { it.id == action.id }
                list.add(action)
            }
        }

        fun listForProfile(profileId: String): List<GameAction> {
            return actions[profileId]?.toList() ?: emptyList()
        }

        fun get(profileId: String, actionId: String): GameAction? {
            return actions[profileId]?.find { it.id == actionId }
        }

        fun listAll(): Map<String, List<GameAction>> {
            return actions.mapValues { it.value.toList() }
        }
    }
}

interface ActionController {
    fun readState(): Map<String, Int>
    fun tap(button: String, count: Int = 1, pressFrames: Int = 5, gapFrames: Int = 40)
    fun step(buttons: List<String>, frames: Int)
    fun waitFrames(frames: Int)
    fun screenshot(): String?
}

data class ActionResult(
    val success: Boolean,
    val message: String,
    val state: Map<String, Int> = emptyMap(),
    val screenshot: String? = null
)
