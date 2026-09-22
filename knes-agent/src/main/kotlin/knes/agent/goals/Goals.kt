package knes.agent.goals

/**
 * Which set of goals a game gets.
 *
 * The decision machinery is game-agnostic — a `Choice` over declared options knows nothing
 * about Final Fantasy — but what the options *are* is the one part that cannot be. Each
 * profile brings its own, the same way `profiles/<id>.json` brings its own addresses.
 */
object Goals {

    fun of(profileId: String?): List<Goal> = when (profileId?.lowercase()) {
        "ff1" -> Ff1Goals.all()
        "smb" -> SmbGoals.all()
        else -> emptyList()
    }

    /** Games the goal selector can play. A profile outside this list falls back to the chat model. */
    val SUPPORTED = setOf("ff1", "smb")
}
