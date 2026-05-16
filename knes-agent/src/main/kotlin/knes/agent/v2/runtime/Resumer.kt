package knes.agent.v2.runtime

import knes.api.EmulatorSession
import java.nio.file.Files

class Resumer(
    private val session: EmulatorSession,
    private val run: V2RunDirectory,
    private val memory: V2Memory,
) {
    fun resume() {
        val lastTurn = memory.campaign.lastTurn
        if (lastTurn == 0) {
            Log.main("resume: lastTurn=0 — nothing to resume; running fresh")
            return
        }
        val checkpointTurn = (lastTurn / 100) * 100
        val checkpoint = run.savestate(checkpointTurn)
        if (!checkpoint.toFile().exists()) {
            Log.warn("resume: no checkpoint at T$checkpointTurn — starting from boot. Decision replay not implemented; advisor will plan from observed RAM.")
            return
        }
        // PPU pre-warm (per v1 Main pattern)
        session.advanceFrames(120)
        val ok = session.loadState(Files.readAllBytes(checkpoint))
        require(ok) { "loadState failed for $checkpoint" }
        session.advanceFrames(120)
        Log.ok("resume: restored T$checkpointTurn (last_turn=$lastTurn). Replay T$checkpointTurn→T$lastTurn not implemented — Advisor will reconcile via campaign.json.")
        // TODO(D2-followup): replay button events from decisions/turn-(checkpointTurn+1).json..turn-lastTurn.json
    }
}
