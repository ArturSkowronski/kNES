package knes.agent.runtime

/**
 * Top-level strategic action chosen by the Advisor LLM after each battle in
 * grind mode. See spec §3.3.
 */
enum class StrategicDecision {
    GRIND,
    REST,
    BRIDGE;

    companion object {
        private val TOKEN_REGEX = Regex("""\b(GRIND|REST|BRIDGE)\b""", RegexOption.IGNORE_CASE)

        /** Returns the first decision token found in [text], or null on no match. */
        fun parse(text: String): StrategicDecision? {
            val match = TOKEN_REGEX.find(text) ?: return null
            return valueOf(match.value.uppercase())
        }
    }
}
