package knes.debug.actions

import knes.debug.actions.ff1.BattleFightAll
import knes.debug.actions.ff1.WalkUntilEncounter

object ActionRegistry {
    private val loaded = mutableSetOf<String>()

    fun ensureLoaded(profileId: String) {
        if (profileId in loaded) return
        loaded.add(profileId)

        when (profileId) {
            "ff1" -> loadFF1Actions()
        }
    }

    private fun loadFF1Actions() {
        BattleFightAll.init()
        WalkUntilEncounter.init()
    }
}
