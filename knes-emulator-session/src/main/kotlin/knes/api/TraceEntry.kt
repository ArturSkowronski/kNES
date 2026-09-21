package knes.api

/**
 * One executed instruction, as the session reports it.
 *
 * The emulator's own trace type stays inside knes-emulator; this is the narrow session
 * API's view of it, so the API, MCP and agent layers do not have to depend on the core.
 */
data class TraceEntry(val pc: Int, val opcode: Int, val cycles: Int)
