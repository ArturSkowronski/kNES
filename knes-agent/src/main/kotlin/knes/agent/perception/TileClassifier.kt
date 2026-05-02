package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TileClassificationTable(
    val version: Int,
    val rom: String,
    val byType: Map<String, List<Int>>,
)

class TileClassifier(private val table: TileClassificationTable) {
    private val idToType: Map<Int, TileType> = table.byType.flatMap { (typeName, ids) ->
        val type = runCatching { TileType.valueOf(typeName) }.getOrDefault(TileType.UNKNOWN)
        ids.map { it to type }
    }.toMap()

    fun classify(tileId: Int): TileType = idToType[tileId] ?: TileType.UNKNOWN

    fun knownIdsForType(type: TileType): List<Int> =
        idToType.entries.filter { it.value == type }.map { it.key }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads `tile-classifications/<name>.json` from resources. On failure
         *  returns a degraded classifier (all-UNKNOWN). */
        fun loadFromResources(name: String): TileClassifier {
            val path = "/tile-classifications/$name.json"
            val stream = TileClassifier::class.java.getResourceAsStream(path)
            if (stream == null) {
                System.err.println("WARN: tile classification table $path not found; using all-UNKNOWN")
                return TileClassifier(TileClassificationTable(0, "missing", emptyMap()))
            }
            return try {
                val text = stream.bufferedReader().use { it.readText() }
                TileClassifier(json.decodeFromString(TileClassificationTable.serializer(), text))
            } catch (t: Throwable) {
                System.err.println("WARN: tile classification parse error: ${t.message}; using all-UNKNOWN")
                TileClassifier(TileClassificationTable(0, "broken", emptyMap()))
            }
        }
    }
}
