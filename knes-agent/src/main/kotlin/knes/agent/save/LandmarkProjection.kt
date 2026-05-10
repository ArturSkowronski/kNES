package knes.agent.save

import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.save.LandmarkRef
import knes.agent.tools.save.LandmarksSnapshot

fun LandmarkMemory.toSnapshot(): LandmarksSnapshot {
    val grouped = all().groupBy { it.bucket() }
    return LandmarksSnapshot(
        kings  = grouped["kings"].orEmpty().map { it.toRef() },
        shops  = grouped["shops"].orEmpty().map { it.toRef() },
        inns   = grouped["inns"].orEmpty().map { it.toRef() },
        bridges = grouped["bridges"].orEmpty().map { it.toRef() },
        other  = grouped["other"].orEmpty().map { it.toRef() },
    )
}

fun LandmarkMemory.applySnapshot(snap: LandmarksSnapshot) {
    fun add(refs: List<LandmarkRef>, kind: LandmarkKind, prefix: String) {
        for ((idx, r) in refs.withIndex()) {
            record(Landmark(
                id = "$prefix-${r.mapId}-${r.x}-${r.y}-$idx",
                kind = kind,
                mapId = r.mapId, localX = r.x, localY = r.y,
                note = r.label,
            ))
        }
    }
    add(snap.kings, LandmarkKind.NPC_KING, "king")
    add(snap.shops, LandmarkKind.NPC_SHOPKEEPER, "shop")
    add(snap.inns, LandmarkKind.NPC_INNKEEPER, "inn")
    add(snap.bridges, LandmarkKind.UNKNOWN, "bridge")
    add(snap.other, LandmarkKind.UNKNOWN, "other")
}

private fun Landmark.bucket(): String = when (kind) {
    LandmarkKind.NPC_KING -> "kings"
    LandmarkKind.NPC_SHOPKEEPER -> "shops"
    LandmarkKind.NPC_INNKEEPER -> "inns"
    else -> "other"
}

private fun Landmark.toRef(): LandmarkRef = LandmarkRef(
    mapId = mapId ?: -1,
    x = localX ?: worldX ?: 0,
    y = localY ?: worldY ?: 0,
    label = note.ifBlank { id },
)
