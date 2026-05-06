package knes.agent.runtime

import knes.agent.perception.AtomicJsonWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class OutfitStateFile(
    val version: Int = 1,
    val savestateHash: String = "",
    val weaponsBoughtAt: String = "",
    val charsEquipped: List<Int> = emptyList(),
    val shopsClassified: List<String> = emptyList(),
    val totalGoldSpent: Int = 0,
)

class OutfitState(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var state: OutfitStateFile = OutfitStateFile()

    init { load() }

    val shopsClassified: List<String> get() = state.shopsClassified

    private fun load() {
        if (!file.exists()) return
        try {
            state = json.decodeFromString(OutfitStateFile.serializer(), file.readText())
        } catch (e: Exception) {
            System.err.println("[outfit-state] failed to load ${file.path}: ${e.message}")
            state = OutfitStateFile()
        }
    }

    fun weaponsBoughtFor(currentHash: String): Boolean =
        state.savestateHash == currentHash && state.charsEquipped.isNotEmpty()

    fun markBought(savestateHash: String, equipped: List<Int>, goldSpent: Int, shops: List<String>) {
        state = OutfitStateFile(
            savestateHash = savestateHash,
            weaponsBoughtAt = java.time.Instant.now().toString(),
            charsEquipped = equipped,
            shopsClassified = shops,
            totalGoldSpent = goldSpent,
        )
        AtomicJsonWriter.write(file, json.encodeToString(OutfitStateFile.serializer(), state))
    }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-outfit-state.json")
    }
}
