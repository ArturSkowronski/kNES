package knes.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AddressEntry(
    val address: String,
    val description: String = ""
)

@Serializable
data class GameProfile(
    val name: String,
    val id: String,
    val description: String = "",
    val addresses: Map<String, AddressEntry>
) {
    fun toWatchMap(): Map<String, Int> {
        return addresses.mapValues { (_, entry) ->
            entry.address.removePrefix("0x").removePrefix("0X").toInt(16)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val builtinProfiles = mutableMapOf<String, GameProfile>()

        init {
            loadBuiltinProfiles()
        }

        private fun loadBuiltinProfiles() {
            val profileFiles = listOf("smb.json", "ff1.json")
            for (file in profileFiles) {
                try {
                    val text = GameProfile::class.java.classLoader
                        .getResourceAsStream("profiles/$file")
                        ?.bufferedReader()?.readText() ?: continue
                    val profile = json.decodeFromString<GameProfile>(text)
                    builtinProfiles[profile.id] = profile
                } catch (e: Exception) {
                    System.err.println("Failed to load profile $file: ${e.message}")
                }
            }
        }

        fun get(id: String): GameProfile? = builtinProfiles[id]

        fun list(): List<GameProfile> = builtinProfiles.values.toList()

        fun register(profile: GameProfile) {
            builtinProfiles[profile.id] = profile
        }
    }
}
