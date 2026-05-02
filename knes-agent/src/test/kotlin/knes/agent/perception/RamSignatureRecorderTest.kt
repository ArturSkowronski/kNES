package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File
import java.nio.file.Files

class RamSignatureRecorderTest : FunSpec({
    test("record RAM signatures for V2 phases") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) {
            throw IllegalStateException("ROM not found: $rom")
        }

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        val loaded = toolset.loadRom(rom)
        if (!loaded.ok) {
            throw IllegalStateException("Failed to load ROM: ${loaded.message}")
        }
        val profiled = toolset.applyProfile("ff1")
        if (!profiled.ok) {
            throw IllegalStateException("Failed to apply profile: ${profiled.message}")
        }

        val out = StringBuilder()
        fun snapshot(label: String) {
            val ram = toolset.getState().ram
            out.appendLine("== $label ==")
            ram.toSortedMap().forEach { (k, v) -> out.appendLine("  $k = 0x${v.toString(16).padStart(2, '0')} ($v)") }
            out.appendLine()
        }

        // Phase: TitleOrMenu (just after boot)
        toolset.step(buttons = emptyList(), frames = 240)  // let title settle
        snapshot("TitleOrMenu_initial")

        // Tap START once → reach NewGameMenu (or somewhere close)
        toolset.tap(button = "START", count = 1, pressFrames = 5, gapFrames = 30)
        snapshot("AfterFirstStartTap")

        // Tap START again → reach NameEntry (probably)
        toolset.tap(button = "START", count = 1, pressFrames = 5, gapFrames = 30)
        snapshot("AfterSecondStartTap")

        // Tap A a few times to traverse class-select / name-entry confirms
        toolset.tap(button = "A", count = 4, pressFrames = 5, gapFrames = 30)
        snapshot("After4ATaps")

        // Continue tapping A to push through whatever screens remain (~20 taps)
        toolset.tap(button = "A", count = 20, pressFrames = 5, gapFrames = 30)
        snapshot("After24ATaps")

        // Final state — likely Overworld with bootFlag = 0x4D
        snapshot("FinalState")

        // Find the root project directory (parent of knes-agent)
        val userDir = System.getProperty("user.dir")
        val rootDir = if (userDir.endsWith("knes-agent")) {
            File(userDir).parentFile
        } else {
            File(userDir)
        }

        val outFile = File(rootDir, "docs/superpowers/notes/2026-05-02-ff1-ram-signatures.md")
        outFile.parentFile?.mkdirs()
        Files.writeString(outFile.toPath(),
            "# FF1 RAM signatures (recorded ${java.time.Instant.now()})\n\n" + out)
    }
})
