package knes.agent.v2

import java.nio.file.Path

/** CLI config for runV2. API keys (ANTHROPIC_API_KEY, GEMINI_API_KEY) are read from env in Main, not parsed here. */
data class V2Config(
    val rom: String,
    val profile: String,
    val resumeDir: Path?,
    val fresh: Boolean,
    val maxTurns: Int,
    val cartographerBudgetSeconds: Int,
    val cartographerMaxVisionCalls: Int,
) {
    companion object {
        fun parse(args: Array<String>): V2Config {
            fun arg(prefix: String) = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            val resume = arg("--resume=")?.let(Path::of)
            val fresh = args.contains("--fresh") || resume == null
            return V2Config(
                rom = arg("--rom=") ?: "roms/ff.nes",
                profile = arg("--profile=") ?: "ff1",
                resumeDir = resume,
                fresh = fresh,
                maxTurns = arg("--max-turns=")?.toInt() ?: 5000,
                cartographerBudgetSeconds = arg("--cart-seconds=")?.toInt() ?: 600,
                cartographerMaxVisionCalls = arg("--cart-vision-calls=")?.toInt() ?: 60,
            )
        }
    }
}
