package knes.agent.v2

fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)
    // TODO(C1): redact key fields from this log line once GeminiPro31Client config lands.
    System.err.println("[v2.main] config=$cfg")
    System.err.println("[v2.main] not yet implemented")
}
