package knes.agent.pathfinding

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.OverworldMap
import java.io.File

/**
 * V5.14 — live ROM evidence that the GPP-style "richer no-path feedback"
 * fix actually addresses HANDOFF blocker #4.
 *
 * Pre-V5.14:
 *   findPath (146,158) → (146,150) : found=false partial=false
 *     reason='no path within viewport' reached=(146,158)
 * The agent sees a bare boolean and assumes the tool is broken (per GPP
 * lessons). Root cause: target tile (146,150) is WATER (byte = ~ glyph),
 * the BFS skips it, and the partial-fallback only fires when target is
 * outside the viewport — not when it's inside-but-impassable.
 *
 * Post-V5.14: pathfinder always returns the BFS-closest reachable tile
 * with `targetPassable=false` so the agent can either redirect to
 * (146,151) or accept the closest reachable instead of giving up.
 */
class V514OverworldRicherFeedbackLiveTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("findPath (146,158)→(146,150) returns partial path + targetPassable=false")
        .config(enabled = canRun) {
        val map = OverworldMap.fromRom(File(romPath))
        val pf = ViewportPathfinder(maxSteps = 64)
        val from = 146 to 158
        val to = 146 to 150
        val view = map.readFullMapView(from)
        val res = pf.findPath(from, to, view, FogOfWar())

        println("[v514-live] from=$from to=$to (target tile=${map.classifyAt(to.first, to.second)})")
        println("[v514-live] found=${res.found} partial=${res.partial} steps=${res.steps.size} " +
            "reached=${res.reachedTile} closestReachable=${res.closestReachable} " +
            "targetPassable=${res.targetPassable}")
        println("[v514-live] reason='${res.reason}'")

        withClue("Pre-V5.14: returned bare blocked. Post-V5.14: should be a partial path.") {
            res.found shouldBe true
            res.partial shouldBe true
            res.steps.shouldNotBeEmpty()
        }
        withClue("Target tile (146,150) is WATER on the ROM-decoded map.") {
            res.targetPassable shouldBe false
        }
        withClue("Closest reachable should be the GRASS tile just south of the WATER target.") {
            res.closestReachable.shouldNotBeNull()
            res.closestReachable shouldBe (146 to 151)
        }
    }

    test("findPath (146,158)→(146,140) on ROM map also returns partial progress")
        .config(enabled = canRun) {
        val map = OverworldMap.fromRom(File(romPath))
        val pf = ViewportPathfinder(maxSteps = 64)
        val from = 146 to 158
        val to = 146 to 140
        val view = map.readFullMapView(from)
        val res = pf.findPath(from, to, view, FogOfWar())

        println("[v514-live] from=$from to=$to (target tile=${map.classifyAt(to.first, to.second)})")
        println("[v514-live] found=${res.found} partial=${res.partial} steps=${res.steps.size} " +
            "reached=${res.reachedTile} closestReachable=${res.closestReachable} " +
            "targetPassable=${res.targetPassable}")

        // Pre-V5.14: bare blocked. Post-V5.14: at minimum a partial path
        // bringing us closer than start.
        withClue("Should make some progress toward target rather than bare blocked.") {
            (res.found || res.closestReachable != from) shouldBe true
        }
    }
})
