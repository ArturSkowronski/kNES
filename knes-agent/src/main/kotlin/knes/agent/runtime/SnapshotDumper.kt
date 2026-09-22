package knes.agent.runtime

import knes.agent.tools.EmulatorToolset
import java.nio.file.Files
import java.util.Base64

class SnapshotDumper(
    private val toolset: EmulatorToolset,
    private val run: RunDirectory,
) {
    /** Dump per-iter screenshot. Idempotent — overwrites if same turn called twice. */
    /**
     * [b64] lets a caller that already grabbed the frame hand it over.
     *
     * The turn loop grabs one anyway for the agents to look at, and grabbing a second is
     * a whole extra frame encode for a picture that is identical to the first.
     */
    fun dump(turn: Int, b64: String? = null): String {
        val frame = b64 ?: toolset.getScreen().base64
        val bytes = Base64.getDecoder().decode(frame)
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
