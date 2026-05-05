package knes.agent.runtime

import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory

/**
 * Phase 2 — Agent uses explorer memory.
 *
 * Renders the persistent `LandmarkMemory` (populated by the explorer phase) as a
 * compact text block injected into both advisor and executor observations. Lets
 * the planner LLM reference towns, castles, dungeons, and known NPCs without
 * having to rediscover them each run.
 *
 * Returns `null` when memory is empty so the caller can skip the section header.
 */
object LandmarkContext {
    fun render(memory: LandmarkMemory): String? {
        val all = memory.all()
        if (all.isEmpty()) return null

        val grouped = all.groupBy { it.kind }
        val ordering = listOf(
            LandmarkKind.TOWN_ENTRY,
            LandmarkKind.CASTLE_ENTRY,
            LandmarkKind.DUNGEON_ENTRY,
            LandmarkKind.NPC_KING,
            LandmarkKind.NPC_SHOPKEEPER,
            LandmarkKind.NPC_GENERIC,
            LandmarkKind.STAIRS_UP,
            LandmarkKind.STAIRS_DOWN,
            LandmarkKind.EXIT_TILE,
            LandmarkKind.UNKNOWN,
        )

        val lines = buildList {
            for (kind in ordering) {
                val entries = grouped[kind] ?: continue
                for (l in entries.sortedBy { it.id }) {
                    add("- ${formatLine(l)}")
                }
            }
        }

        return buildString {
            append("Known landmarks (from prior exploration runs):\n")
            append(lines.joinToString("\n"))
        }
    }

    private fun formatLine(l: Landmark): String = buildString {
        append(l.kind.name)
        when {
            l.worldX != null && l.worldY != null -> {
                append(" at world(${l.worldX},${l.worldY})")
                if (l.mapIdInterior != null) append(" → mapId=${l.mapIdInterior}")
            }
            l.mapId != null -> {
                append(" in mapId=${l.mapId}")
                if (l.localX != null && l.localY != null) append(" local(${l.localX},${l.localY})")
            }
        }
        if (l.visited) append(" [visited]")
        if (l.note.isNotBlank()) append(" — \"${l.note.take(60)}\"")
    }
}
