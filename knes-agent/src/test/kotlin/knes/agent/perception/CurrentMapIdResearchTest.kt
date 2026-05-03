package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class CurrentMapIdResearchTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("locate currentMapId byte by RAM diff").config(enabled = romPresent) {
        val outDir = File("build/research/current-map-id").also { it.mkdirs() }
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")

        toolset.step(buttons = emptyList(), frames = 240)
        toolset.tap(button = "START", count = 2, pressFrames = 5, gapFrames = 30)
        repeat(60) {
            val ram = toolset.getState().ram
            if ((ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0) return@repeat
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
        }
        toolset.step(buttons = emptyList(), frames = 60)

        fun ramSnapshot(): IntArray {
            val arr = IntArray(0x800)
            for (i in 0 until 0x800) arr[i] = session.readMemory(i)
            return arr
        }

        val ramA = toolset.getState().ram
        println("STATE A (castle?): locationType=0x${(ramA["locationType"]?:0).toString(16)} localX=${ramA["localX"]} localY=${ramA["localY"]} worldX=${ramA["worldX"]} worldY=${ramA["worldY"]}")
        val stateA = ramSnapshot()
        File(outDir, "A-castle.txt").writeText(stateA.dumpAsHex())

        repeat(30) {
            toolset.step(buttons = listOf("DOWN"), frames = 16)
            val ram = toolset.getState().ram
            if ((ram["locationType"] ?: 0) == 0 && (ram["localX"] ?: 0) == 0 &&
                (ram["localY"] ?: 0) == 0) return@repeat
        }
        val ramB = toolset.getState().ram
        println("STATE B (overworld?): locationType=0x${(ramB["locationType"]?:0).toString(16)} localX=${ramB["localX"]} localY=${ramB["localY"]} worldX=${ramB["worldX"]} worldY=${ramB["worldY"]}")
        val stateB = ramSnapshot()
        File(outDir, "B-overworld.txt").writeText(stateB.dumpAsHex())

        repeat(20) {
            toolset.step(buttons = listOf("UP"), frames = 16)
            val ram = toolset.getState().ram
            if ((ram["localX"] ?: 0) != 0 || (ram["localY"] ?: 0) != 0) return@repeat
        }
        val ramC = toolset.getState().ram
        println("STATE C (town?): locationType=0x${(ramC["locationType"]?:0).toString(16)} localX=${ramC["localX"]} localY=${ramC["localY"]} worldX=${ramC["worldX"]} worldY=${ramC["worldY"]}")
        val stateC = ramSnapshot()
        File(outDir, "C-town.txt").writeText(stateC.dumpAsHex())

        // States observed: A=overworld pos1, B=overworld pos2, C=interior(town).
        // Therefore currentMapId byte should: A == B (both overworld, value 0)
        // AND B != C (interior has non-zero map id).
        val candidates = (0 until 0x800).filter { i ->
            stateA[i] == stateB[i] && stateB[i] != stateC[i]
        }
        val report = StringBuilder()
        report.appendLine("# Candidate currentMapId bytes (A==B, B!=C)")
        report.appendLine("# A=overworld(${stateA[0x27]},${stateA[0x28]}) B=overworld(${stateB[0x27]},${stateB[0x28]}) C=interior(localX=${stateC[0x29]},localY=${stateC[0x2A]})")
        for (i in candidates) {
            report.appendLine(String.format(
                "  \$%04X: A=0x%02X  B=0x%02X  C=0x%02X",
                i, stateA[i], stateB[i], stateC[i]
            ))
        }
        File(outDir, "candidates.txt").writeText(report.toString())
        println("Candidates written to ${outDir.absolutePath}/candidates.txt")
        println("Top candidates:")
        candidates.take(30).forEach {
            println(String.format("  \$%04X: A=0x%02X B=0x%02X C=0x%02X",
                it, stateA[it], stateB[it], stateC[it]))
        }
    }
})

private fun IntArray.dumpAsHex(): String = buildString {
    for (i in indices) {
        if (i % 16 == 0) append(String.format("%04X: ", i))
        append(String.format("%02X ", this@dumpAsHex[i]))
        if (i % 16 == 15) append('\n')
    }
}
