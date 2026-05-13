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
            return V2Config(
                rom = arg("--rom=") ?: "roms/ff.nes",
                profile = arg("--profile=") ?: "ff1",
                resumeDir = resume,
                fresh = fresh,
                maxTurns = arg("--max-turns=")?.toInt() ?: 5000,
                cartographerBudgetSeconds = arg("--cart-seconds=")?.toInt() ?: 600,
                cartographerMaxVisionCalls = arg("--cart-vision-calls=")?.toInt() ?: 60,
                cartographerEnabled = cartEnabled,
            )
        }
    }
}
