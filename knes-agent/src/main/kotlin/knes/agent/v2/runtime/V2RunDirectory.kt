package knes.agent.v2.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class V2RunDirectory(val root: Path) {
    val campaignJson: Path = root.resolve("campaign.json")
    val currentPlanJson: Path = root.resolve("current_plan.json")
    val landmarksJson: Path = root.resolve("landmarks.json")
    val warpsJson: Path = root.resolve("warps.json")
    val blockagesJson: Path = root.resolve("blockages.json")
    val overworldMapJson: Path = root.resolve("overworld-map.json")
    val cartographerFlagsJson: Path = root.resolve("cartographer-flags.json")
    val reviewJsonl: Path = root.resolve("review.jsonl")
    val decisionsDir: Path = root.resolve("decisions")
    val snapshotsDir: Path = root.resolve("snapshots")
    val interiorMapsDir: Path = root.resolve("interior-maps")
    val savestateDir: Path = root.resolve("savestate-checkpoints")

    fun ensure() {
        root.createDirectories()
        decisionsDir.createDirectories()
        snapshotsDir.createDirectories()
        interiorMapsDir.createDirectories()
        savestateDir.createDirectories()
    }

    fun turnDecisionFile(turn: Int): Path =
        decisionsDir.resolve("turn-%05d.json".format(turn))

    fun turnSnapshot(turn: Int): Path =
        snapshotsDir.resolve("turn-%05d.png".format(turn))

    fun savestate(turn: Int): Path =
        savestateDir.resolve("T%d.nss".format(turn))

    companion object {
        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
        private fun runsRoot(): Path =
            Path.of(System.getProperty("user.home"), ".knes", "runs")

        fun freshRun(): V2RunDirectory {
            val ts = LocalDateTime.now().format(TS_FMT)
            val dir = runsRoot().resolve("$ts-v2")
            val rd = V2RunDirectory(dir).also { it.ensure() }
            updateLatestSymlink(dir)
            return rd
        }

        fun resume(path: Path): V2RunDirectory {
            require(path.exists()) { "resume dir does not exist: $path" }
            return V2RunDirectory(path)
        }

        private fun updateLatestSymlink(target: Path) {
            val link = runsRoot().resolve("latest-v2")
            try {
                link.deleteIfExists()
                Files.createSymbolicLink(link, target)
            } catch (e: Throwable) {
                System.err.println("[v2.run-dir] WARN: symlink update failed: ${e.message}")
            }
        }
    }
}
