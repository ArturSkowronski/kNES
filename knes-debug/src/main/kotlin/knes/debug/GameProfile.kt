package knes.debug

/**
 * Game-specific memory map profile.
 * Maps human-readable variable names to NES RAM addresses.
 * Used by the Compose UI monitor, REST API, and any debug tooling.
 *
 * Profiles are loaded from JSON files in resources/profiles/ at startup.
 * Custom profiles can be registered at runtime via [register].
 */
data class AddressEntry(
    val address: Int,
    val description: String = ""
)

data class GameProfile(
    val name: String,
    val id: String,
    val description: String = "",
    val addresses: Map<String, AddressEntry>
) {
    fun toWatchMap(): Map<String, Int> = addresses.mapValues { it.value.address }

    companion object {
        private val profiles = mutableMapOf<String, GameProfile>()

        init {
            loadBuiltinProfiles()
        }

        private fun loadBuiltinProfiles() {
            for (file in listOf("smb.json", "ff1.json")) {
                try {
                    val text = GameProfile::class.java.classLoader
                        .getResourceAsStream("profiles/$file")
                        ?.bufferedReader()?.readText() ?: continue
                    val profile = parseJson(text)
                    if (profile != null) profiles[profile.id] = profile
                } catch (e: Exception) {
                    System.err.println("Failed to load profile $file: ${e.message}")
                }
            }
        }

        private fun parseJson(json: String): GameProfile? {
            val name = extractString(json, "name") ?: return null
            val id = extractString(json, "id") ?: return null
            val description = extractString(json, "description") ?: ""

            val addrStart = json.indexOf("\"addresses\"")
            if (addrStart == -1) return null
            val blockStart = json.indexOf('{', addrStart + 11)
            val blockEnd = findMatchingBrace(json, blockStart)
            if (blockStart == -1 || blockEnd == -1) return null

            val addrBlock = json.substring(blockStart + 1, blockEnd)
            val addresses = mutableMapOf<String, AddressEntry>()

            val entryPattern = Regex(""""(\w+)"\s*:\s*\{([^}]*)\}""")
            for (match in entryPattern.findAll(addrBlock)) {
                val varName = match.groupValues[1]
                val entryBody = match.groupValues[2]
                val addr = extractString(entryBody, "address") ?: continue
                val desc = extractString(entryBody, "description") ?: ""
                val addrInt = addr.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: continue
                addresses[varName] = AddressEntry(addrInt, desc)
            }

            return GameProfile(name, id, description, addresses)
        }

        private fun extractString(json: String, key: String): String? {
            return Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
        }

        private fun findMatchingBrace(s: String, start: Int): Int {
            if (start < 0 || start >= s.length || s[start] != '{') return -1
            var depth = 0
            for (i in start until s.length) {
                when (s[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return i }
                }
            }
            return -1
        }

        fun get(id: String): GameProfile? = profiles[id]
        fun list(): List<GameProfile> = profiles.values.toList()
        fun register(profile: GameProfile) { profiles[profile.id] = profile }
    }
}
