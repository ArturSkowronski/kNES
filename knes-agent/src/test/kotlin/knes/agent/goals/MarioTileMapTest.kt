package knes.agent.goals

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.tools.LocalEmulatorToolset
import knes.agent.tools.results.AgentConfig
import knes.agent.tools.results.GameSemantics
import knes.api.EmulatorSession
import java.io.File

/**
 * The map has to agree with the game, or it is worse than no map at all.
 *
 * `profiles/smb.json` describes Super Mario Bros' tile buffer — where it starts, how it is
 * paged, how tall the status bar is — and the agent renders a window of it into the state
 * the decision model reads. Get the alignment wrong by one row and the model is told there
 * is ground where there is air, which is a confident lie rather than a missing fact.
 *
 * So this stands Mario on the floor of World 1-1 and checks the floor is under him.
 *
 * Skipped without `roms/smb.nes`, like every other ROM-dependent test here.
 */
class MarioTileMapTest : FunSpec({

    // Tests run from the module directory; the ROMs live at the repository root.
    val rom = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "roms/smb.nes") }
        .firstOrNull { it.isFile }
        ?: File("roms/smb.nes")

    test("the ground Mario is standing on is under him on the map") {
        if (!rom.exists()) {
            println("roms/smb.nes not present; skipping")
            return@test
        }
        val session = EmulatorSession()
        session.loadRom(rom.path) shouldBe true
        val toolset = LocalEmulatorToolset(session)
        toolset.applyProfile("smb")

        // Through the title screen and a moment into the level, standing still.
        repeat(8) { toolset.step(buttons = listOf("START"), frames = 6); toolset.step(emptyList(), 10) }
        toolset.step(buttons = emptyList(), frames = 120)

        val semantics = GameSemantics.of("smb")
        val config = AgentConfig.of("smb")!!
        val ram = toolset.getState().ram
        val (x, y) = semantics.localPosition(ram) ?: error("smb profile has no position mapping")

        // Rendered directly: the profile keeps the map switched off in the state, and this
        // checks the map itself is right, not whether the agent is currently shown it.
        val lines = config.mapLines({ start, length -> toolset.readRange(start, length) }, x, y)
        println("gameState=${ram["gameState"]} floatState=${ram["playerFloatState"]} at $x,$y")
        lines.forEach(::println)

        lines.isEmpty() shouldBe false
        // The profile puts the player four rows down and two columns in.
        val playerRow = 4
        val playerColumn = 2
        lines[playerRow][playerColumn] shouldBe 'M'

        // Mario starts World 1-1 standing on the floor, which runs the width of the screen.
        // Whatever row the floor lands on, it must be below him and it must be continuous.
        val below = lines.drop(playerRow + 1)
        val floor = below.firstOrNull { row -> row.count { it == '#' } >= row.length - 1 }
        floor shouldBe below.first()
    }
})
