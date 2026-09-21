package knes.api

import knes.emulator.RomIdentity

/**
 * A deterministic input script.
 *
 * Text, not JSON: a replay is something a person reads in a diff and a bisect points at,
 * and the API's request shapes should be free to change without invalidating recorded
 * runs.
 *
 * ```
 * knes-replay 1
 * rom 1A2B3C4D 0 2 1 0
 * 30 -
 * 5 A
 * 10 RIGHT,A
 * ```
 *
 * The `rom` line is the [RomIdentity] the script was recorded against, so replaying it
 * on the wrong cartridge is refused rather than producing nonsense. A script recorded
 * without a ROM loaded omits the line.
 */
data class Replay(
    val rom: RomIdentity? = null,
    val entries: List<ReplayEntry> = emptyList(),
) {
    /** Total frames the script covers. */
    val frames: Int get() = entries.sumOf { it.frames }

    fun encode(): String = buildString {
        appendLine("$HEADER $VERSION")
        rom?.let {
            appendLine("rom %08X %d %d %d %d".format(it.contentHash, it.mapperId, it.prgBanks, it.chrBanks, it.mirroring))
        }
        for (entry in entries) {
            appendLine("${entry.frames} ${if (entry.buttons.isEmpty()) NO_BUTTONS else entry.buttons.joinToString(",")}")
        }
    }

    companion object {
        const val HEADER = "knes-replay"
        const val VERSION = 1
        private const val NO_BUTTONS = "-"

        fun parse(text: String): Replay {
            val lines = text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toList()
            require(lines.isNotEmpty()) { "empty replay" }

            val header = lines.first().split(" ")
            require(header.size == 2 && header[0] == HEADER) { "not a replay: '${lines.first()}'" }
            require(header[1].toIntOrNull() == VERSION) { "unsupported replay version: '${header[1]}'" }

            var rom: RomIdentity? = null
            val entries = mutableListOf<ReplayEntry>()
            for (line in lines.drop(1)) {
                val parts = line.split(" ")
                if (parts[0] == "rom") {
                    require(parts.size == 6) { "malformed rom line: '$line'" }
                    rom = RomIdentity(
                        contentHash = parts[1].toLong(16).toInt(),
                        mapperId = parts[2].toInt(),
                        prgBanks = parts[3].toInt(),
                        chrBanks = parts[4].toInt(),
                        mirroring = parts[5].toInt(),
                    )
                    continue
                }
                require(parts.size == 2) { "malformed entry: '$line'" }
                val frames = parts[0].toIntOrNull() ?: throw IllegalArgumentException("bad frame count: '$line'")
                require(frames > 0) { "frame count must be positive: '$line'" }
                val buttons = if (parts[1] == NO_BUTTONS) emptyList() else parts[1].split(",").filter { it.isNotEmpty() }
                entries += ReplayEntry(frames, buttons)
            }
            return Replay(rom, entries)
        }
    }
}

/** Hold [buttons] for [frames] frames. */
data class ReplayEntry(val frames: Int, val buttons: List<String> = emptyList())

/**
 * A replay was played against a machine it was not recorded on.
 *
 * Same reasoning as [knes.emulator.SavestateMismatchException]: silently replaying
 * someone else's input script produces a plausible-looking but meaningless run.
 */
class ReplayMismatchException(message: String) : IllegalStateException(message)

/**
 * Play [replay] from the current state, advancing frame by frame.
 *
 * Refuses a replay recorded against a different ROM. Returns the frame the session
 * reached, so a caller can assert two runs landed in the same place.
 */
fun EmulatorSession.play(replay: Replay): Int {
    val recorded = replay.rom
    val current = nes.romIdentity
    if (recorded != null && current != null && recorded != current) {
        throw ReplayMismatchException("replay was recorded on a different ROM: replay=$recorded, loaded=$current")
    }
    for (entry in replay.entries) {
        controller.setButtons(entry.buttons)
        advanceFrames(entry.frames)
    }
    controller.releaseAll()
    return frameCount
}
