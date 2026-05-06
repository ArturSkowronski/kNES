package knes.agent.perception

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Helper for crash-safe JSON memory file saves: writes to a sibling `.tmp`
 * file, then atomically renames it over the target. A crash mid-write leaves
 * either the previous valid file (rename hadn't happened) or the new valid
 * file (rename completed) — never a half-written corrupt state.
 *
 * Used by every persistent memory class (Landmark, Blockage, OverworldWarp,
 * Interior, OverworldTerrain, OverworldMemory) so the agent never loses its
 * cumulative state to a SIGINT or process kill.
 */
object AtomicJsonWriter {
    /** Write [content] to [target] atomically. Creates the parent directory if
     *  missing. The temp file is in the same directory as [target] so the
     *  rename can be atomic on the same filesystem. */
    fun write(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
