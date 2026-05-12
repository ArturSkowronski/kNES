package knes.agent.v2.runtime

import knes.agent.tools.EmulatorToolset
import java.nio.file.Files
import java.util.Base64

class SnapshotDumper(
    private val toolset: EmulatorToolset,
    private val run: V2RunDirectory,
) {
    /** Dump per-iter screenshot. Idempotent — overwrites if same turn called twice. */
    fun dump(turn: Int): String {
        val b64 = toolset.getScreen().base64
        val bytes = Base64.getDecoder().decode(b64)
        val out = run.turnSnapshot(turn)
        Files.write(out, bytes)
        return run.root.relativize(out).toString()
    }

    /** Pre-campaign Cartographer iterations get their own file prefix. */
    fun dumpCartographer(iter: Int): String {
        val b64 = toolset.getScreen().base64
        val bytes = Base64.getDecoder().decode(b64)
        val out = run.snapshotsDir.resolve("cart-%05d.png".format(iter))
        Files.write(out, bytes)
        return run.root.relativize(out).toString()
    }
}
