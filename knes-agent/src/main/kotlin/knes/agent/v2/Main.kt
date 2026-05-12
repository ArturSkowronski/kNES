package knes.agent.v2

import knes.agent.v2.runtime.V2RunDirectory

fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)
    // TODO(C1): redact key fields from this log line once GeminiPro31Client config lands.
    System.err.println("[v2.main] config=$cfg")
    val run = if (cfg.resumeDir != null) V2RunDirectory.resume(cfg.resumeDir)
             else V2RunDirectory.freshRun()
    System.err.println("[v2.main] run dir: ${run.root}")
}
