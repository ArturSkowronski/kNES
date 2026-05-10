package knes.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 2026-05-09 cont 3 — coding-agent-style action notebook persisted alongside the
 * savestate. The agent's "where I came from" memory.
 *
 * Why: when a savestate is loaded, the engine state is restored but the agent's
 * narrative history is lost. Without that history, post-buy exit becomes a
 * blind-search problem (cont-3 #1-#6 burned ~$5 validating this). With it, the
 * agent can reverse-walk its own in-trajectory deterministically.
 *
 * Lifecycle:
 *   1. Skills (boot walk-in, BuyAtShop, EquipWeapon, ExitInterior) call
 *      [record] for each meaningful action, capturing optional `moveDir`,
 *      `smPlayerPre/Post`, and a one-line `summary`.
 *   2. When the agent dumps a savestate to e.g. `/tmp/spec5-post-buy.savestate`,
 *      it ALSO calls [save] to write the sister `*.actions.json`.
 *   3. On a subsequent `KNES_FF1_LOAD_SAVESTATE` startup, [load] reads the
 *      sister file. The restored scratchpad is injected into AgentSession and
 *      becomes prompt context (via [renderForLLM]) and the source of truth for
 *      reverse-walk via [reverseTrajectoryFromTownEntry].
 *
 * Storage format: simple JSON array of entries, ordered by record-time. ~few KB
 * for a typical buy session. Human-readable for debugging.
 */
@Serializable
data class ScratchpadEntry(
    /** Monotonic record index (0,1,2,...) — not the agent's turn counter. */
    val seq: Int,
    /** Wall-clock millis since session start, for rough timing context. */
    val tMs: Long,
    /** Coarse category: "boot", "walk", "tap", "buy", "equip", "exit", "summary", "phase". */
    val kind: String,
    /** One-line LLM-readable description. */
    val summary: String,
    /** For "tap" kind: cardinal name (UP/DOWN/LEFT/RIGHT) or button (A/B/Start). */
    val dir: String? = null,
    /** smPlayer (X,Y) before this action. */
    val smPre: List<Int>? = null,
    /** smPlayer (X,Y) after this action. null if not applicable. */
    val smPost: List<Int>? = null,
    /** Did smPlayer actually change? (false = stuck/blocked/dialog). */
    val moved: Boolean? = null,
    /** mapflags after action (for transition detection). */
    val mapflagsPost: Int? = null,
    /** Free-form context: e.g. "char3 BOUGHT (Wooden Nunchuck, 10G)". */
    val note: String? = null,
)

class AgentScratchpad private constructor(
    private val sessionStartMs: Long,
) {
    private val entries = mutableListOf<ScratchpadEntry>()
    private var seqCounter = 0

    fun record(
        kind: String,
        summary: String,
        dir: String? = null,
        smPre: Pair<Int, Int>? = null,
        smPost: Pair<Int, Int>? = null,
        mapflagsPost: Int? = null,
        note: String? = null,
    ) {
        val moved = if (smPre != null && smPost != null) smPre != smPost else null
        entries.add(ScratchpadEntry(
            seq = seqCounter++,
            tMs = System.currentTimeMillis() - sessionStartMs,
            kind = kind,
            summary = summary,
            dir = dir,
            smPre = smPre?.let { listOf(it.first, it.second) },
            smPost = smPost?.let { listOf(it.first, it.second) },
            moved = moved,
            mapflagsPost = mapflagsPost,
            note = note,
        ))
    }

    fun all(): List<ScratchpadEntry> = entries.toList()

    /**
     * LLM-prompt-friendly text dump. Compact, chronological, stable format.
     * Designed to drop into a system prompt or user message verbatim.
     */
    fun renderForLLM(headerNote: String = ""): String = buildString {
        appendLine("=== AGENT SCRATCHPAD (${entries.size} entries) ===")
        if (headerNote.isNotBlank()) appendLine(headerNote)
        for (e in entries) {
            val moveTag = if (e.dir != null) {
                val moveResult = when (e.moved) {
                    true -> "→${e.smPost?.joinToString(",")}"
                    false -> "[blocked]"
                    null -> ""
                }
                " ${e.dir}${if (e.smPre != null) "@(${e.smPre.joinToString(",")})" else ""}$moveResult"
            } else ""
            val mfTag = e.mapflagsPost?.let { " mapflags=$it" } ?: ""
            val noteTag = e.note?.let { " — $it" } ?: ""
            appendLine("  [${e.seq}] +${e.tMs / 1000}s ${e.kind}:${moveTag}$mfTag ${e.summary}$noteTag")
        }
        appendLine("=== END SCRATCHPAD ===")
    }

    /**
     * Returns the cardinal-direction sequence the agent used during the
     * "town walk-in" — i.e. all "tap" entries with `kind="tap"` and
     * dir in {UP,DOWN,LEFT,RIGHT} that successfully moved smPlayer (moved=true)
     * BETWEEN the most recent `kind="phase"` entry whose summary contains
     * "town_entry" and the most recent `kind="phase"` entry whose summary
     * contains "shop_reached".
     *
     * Reversed (UP↔DOWN, LEFT↔RIGHT) gives a known-good exit path.
     */
    /**
     * Returns the smPlayer (X,Y) tile where the FIRST cardinal-tap of the
     * most recent walk-in started — i.e. the town/overworld warp tile where
     * the engine placed the party when it transitioned IN. This is the
     * exact tile to return to in order to exit (reverse the warp).
     *
     * Returns null if no walk-in trajectory is recorded.
     */
    fun walkInEntryTile(): Pair<Int, Int>? {
        // Iterate from the LATEST walk-in pair backwards, skipping pairs that
        // contain no actual taps (savestate-loaded skips that just emit two
        // phase markers without a real walk).
        val phaseIndices = entries.withIndex().filter { it.value.kind == "phase" }
        val pairs = mutableListOf<Pair<Int, Int>>()  // (entryIdx, shopIdx)
        var pendingEntry: Int? = null
        for ((idx, e) in phaseIndices) {
            if (e.summary.contains("town_entry", ignoreCase = true)) pendingEntry = idx
            else if (e.summary.contains("shop_reached", ignoreCase = true) && pendingEntry != null) {
                pairs.add(pendingEntry!! to idx)
                pendingEntry = null
            }
        }
        // Latest pair with actual cardinal taps wins.
        for ((entryIdx, shopIdx) in pairs.reversed()) {
            val firstTap = entries.subList(entryIdx, shopIdx)
                .firstOrNull { it.kind == "tap" && it.smPre != null && it.moved == true }
            if (firstTap != null) {
                val sm = firstTap.smPre!!
                return sm[0] to sm[1]
            }
        }
        return null
    }

    fun reverseTrajectoryFromTownEntry(): List<String> {
        val reverseMap = mapOf("UP" to "DOWN", "DOWN" to "UP", "LEFT" to "RIGHT", "RIGHT" to "LEFT")
        // Find the latest walk-in pair with actual cardinal taps (skip empty
        // savestate-load skip pairs).
        val phaseIndices = entries.withIndex().filter { it.value.kind == "phase" }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var pendingEntry: Int? = null
        for ((idx, e) in phaseIndices) {
            if (e.summary.contains("town_entry", ignoreCase = true)) pendingEntry = idx
            else if (e.summary.contains("shop_reached", ignoreCase = true) && pendingEntry != null) {
                pairs.add(pendingEntry!! to idx); pendingEntry = null
            }
        }
        for ((entryIdx, shopIdx) in pairs.reversed()) {
            val taps = entries.subList(entryIdx, shopIdx)
                .filter { it.kind == "tap" && it.moved == true && it.dir in reverseMap.keys }
            if (taps.isNotEmpty()) {
                return taps.mapNotNull { reverseMap[it.dir] }.reversed()
            }
        }
        return emptyList()
    }

    fun save(path: File) {
        path.writeText(json.encodeToString(entries))
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun newSession(): AgentScratchpad =
            AgentScratchpad(sessionStartMs = System.currentTimeMillis())

        /**
         * Loads a scratchpad from disk if present. The loaded scratchpad's
         * `tMs` field is preserved (rebased relative to original session) but
         * subsequent [record] calls will use the new session's clock — entries
         * are append-only so the discontinuity is visible but not problematic.
         *
         * Returns null if the file doesn't exist; throws on parse error so
         * caller knows the file was corrupted rather than missing.
         */
        fun loadSister(savestatePath: File): AgentScratchpad? {
            val sister = sisterPathFor(savestatePath)
            if (!sister.exists()) return null
            val loaded = json.decodeFromString<List<ScratchpadEntry>>(sister.readText())
            val pad = AgentScratchpad(sessionStartMs = System.currentTimeMillis())
            pad.entries.addAll(loaded)
            pad.seqCounter = (loaded.maxOfOrNull { it.seq } ?: -1) + 1
            return pad
        }

        /** Convention: `<savestate>.actions.json` next to the savestate. */
        fun sisterPathFor(savestatePath: File): File =
            File(savestatePath.parentFile, savestatePath.nameWithoutExtension + ".actions.json")
    }
}
