package knes.emulator

/**
 * Enough of a ROM's identity to tell whether a savestate belongs to it.
 *
 * Not a full checksum of the file — the header fields plus a hash of the program banks
 * are what distinguish one cartridge from another, and they are cheap to compute while
 * the ROM is already being parsed.
 */
data class RomIdentity(
    val mapperId: Int,
    val prgBanks: Int,
    val chrBanks: Int,
    val mirroring: Int,
    val contentHash: Int,
) {
    companion object {
        /** FNV-1a over the program banks. Stable across runs, unlike [Object.hashCode]. */
        fun hashPrg(banks: Array<ShortArray>): Int {
            var hash = -0x7EE3623B // 2166136261
            for (bank in banks) {
                for (byte in bank) {
                    hash = (hash xor (byte.toInt() and 0xFF)) * 16777619
                }
            }
            return hash
        }
    }
}

/**
 * A savestate was loaded against a machine it does not describe.
 *
 * Loud on purpose: the previous format recorded nothing about the ROM, so loading a
 * state from a different cartridge quietly produced a corrupt machine instead of an
 * error.
 */
class SavestateMismatchException(message: String) : IllegalStateException(message)

/** Savestate wire format constants. */
object Savestate {
    const val MAGIC: String = "KNES"
    const val VERSION: Int = 2

    /** The original format: a single version byte followed by positional component dumps. */
    const val LEGACY_VERSION: Int = 1

    const val CHUNK_CPU_RAM = "cpuram"
    const val CHUNK_PPU_RAM = "ppuram"
    const val CHUNK_SPR_RAM = "sprram"
    const val CHUNK_CPU = "cpu"
    const val CHUNK_MAPPER = "mapper"
    const val CHUNK_PPU = "ppu"
}
