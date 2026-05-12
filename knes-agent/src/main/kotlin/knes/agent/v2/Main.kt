package knes.agent.v2

import knes.agent.v2.runtime.V2RunDirectory

fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)
    val run = if (cfg.resumeDir != null) V2RunDirectory.resume(cfg.resumeDir)
             else V2RunDirectory.freshRun()
    System.err.println("[v2.main] run dir: ${run.root}")
}
