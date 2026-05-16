package knes.agent.v2

import java.nio.file.Path

data class V2Config(
    val rom: String,
    val profile: String,
    val resumeDir: Path?,
    val fresh: Boolean,
    val maxTurns: Int,
    val cartographerBudgetSeconds: Int,
    val cartographerMaxVisionCalls: Int,
    val cartographerEnabled: Boolean,
    /**
     * If non-null, the agent uses [knes.agent.tools.RemoteEmulatorToolset]
     * pointed at this base URL instead of booting a local in-process NES.
     * Use case: agent plays THROUGH the Compose UI's embedded REST API
     * (default port 6502) so the audience sees the live screen on stage.
     *
     * Activate with `--remote` (defaults to http://localhost:6502) or
     * `--remote=<url>` for a custom address. Null = legacy in-process mode,
     * unchanged.
     *
     * Caveats when set:
     *   - ROM must be loaded by the UI (the UI guards `POST /rom` in shared
     *     mode), so the agent does NOT call `toolset.loadRom`.
     *   - `--resume` is unsupported (no save_state via REST yet).
     *   - savestate-checkpoints every 100 turns are skipped.
     */
    val remoteUrl: String?,
) {
    companion object {
        fun parse(args: Array<String>): V2Config {
            fun arg(prefix: String) = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            val resume = arg("--resume=")?.let(Path::of)
            val fresh = args.contains("--fresh") || resume == null
            // Cartographer is OPT-IN (default OFF). It's expensive (Gemini vision
            // calls per iter) and on dev iteration usually adds noise without
            // value — landmarks for Coneria/Pravoka/etc are preseeded in the
            // LandmarkMemory anyway. Pass --cart to enable for unexplored maps.
            val cartEnabled = args.contains("--cart") || args.contains("--cartographer")

            // Remote mode: `--remote` (default URL) or `--remote=<url>`.
            // Reuses Compose UI's embedded API server so the audience sees
            // the live emulator while the agent decides.
            val remoteUrl = when {
                args.any { it == "--remote" } -> "http://localhost:6502"
                else -> arg("--remote=")
            }

            return V2Config(
                rom = arg("--rom=") ?: "roms/ff.nes",
                profile = arg("--profile=") ?: "ff1",
                resumeDir = resume,
                fresh = fresh,
                maxTurns = arg("--max-turns=")?.toInt() ?: 5000,
                cartographerBudgetSeconds = arg("--cart-seconds=")?.toInt() ?: 600,
                cartographerMaxVisionCalls = arg("--cart-vision-calls=")?.toInt() ?: 60,
                cartographerEnabled = cartEnabled,
                remoteUrl = remoteUrl,
            )
        }
    }
}
