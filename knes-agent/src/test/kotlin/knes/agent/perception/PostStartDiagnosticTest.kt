package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * Diagnostic: what RAM fields change when we tap START / A through the title→party→overworld
 * sequence? bootFlag is useless (sets to 0x4D within 10 frames of cold boot regardless of
 * input). We need a different marker.
 *
 * Strategy: dump the FULL ram diff after each input action. Print only fields that CHANGED.
 */
class PostStartDiagnosticTest : FunSpec({
    test("RAM diff after each input from cold boot") {
        val rom = "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom)
        toolset.applyProfile("ff1")

        var prev: Map<String, Int> = emptyMap()
        fun stamp(label: String) {
            val ram = toolset.getState().ram
            val frame = toolset.getState().frame
            val diff = ram.filter { (k, v) -> prev[k] != v }
            val msg = if (prev.isEmpty()) ram.entries.joinToString { "${it.key}=0x${it.value.toString(16)}" }
                      else diff.entries.joinToString { "${it.key}=0x${it.value.toString(16)} (was 0x${prev[it.key]?.toString(16)})" }
            println("[stamp] frame=$frame label=$label  changed=${diff.size}  $msg")
            prev = ram
        }

        toolset.step(buttons = emptyList(), frames = 240)
        stamp("after 240 idle frames (initial)")

        toolset.tap(button = "START", count = 1, pressFrames = 5, gapFrames = 30)
        stamp("after START tap #1")

        toolset.tap(button = "START", count = 1, pressFrames = 5, gapFrames = 30)
        stamp("after START tap #2")

        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
        stamp("after A tap #1")

        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
        stamp("after A tap #2")

        toolset.tap(button = "A", count = 5, pressFrames = 5, gapFrames = 30)
        stamp("after 5 more A taps")

        toolset.tap(button = "A", count = 20, pressFrames = 5, gapFrames = 30)
        stamp("after 20 more A taps")

        // Try DOWN/RIGHT to see if we're on overworld
        toolset.step(buttons = listOf("RIGHT"), frames = 16)
        stamp("after holding RIGHT 16 frames")
    }
})
