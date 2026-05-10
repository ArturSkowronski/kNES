package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.save.LandmarksSnapshot
import knes.agent.tools.save.SaveFormatCodec
import knes.agent.tools.save.VisitedMinimap
import knes.api.EmulatorSession
import java.io.File
import java.nio.file.Files

/**
 * Integration test for KnesSave codec: save bytes → encode JSON → write/read temp file →
 * decode bytes → load into a FRESH EmulatorSession → confirm the second session's
 * saveState produces byte-identical output.
 *
 * Mirrors the gating pattern from
 * knes-agent/src/test/kotlin/knes/agent/perception/SaveStateRoundTripTest.kt:
 * hardcoded ROM path + canRun gate so CI without the ROM cleanly skips.
 */
class SaveSessionRoundTripTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("save then load via codec restores identical emulator bytes")
        .config(enabled = canRun) {
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        toolset.step(buttons = emptyList(), frames = 60)
        val preBytes = session.saveState()

        val saveFile = Files.createTempFile("rt-", ".knes-save.json").toFile().apply { deleteOnExit() }
        val save = SaveFormatCodec.encode(
            emulatorStateBytes = preBytes,
            rom = "ff1.nes",
            intent = "integration test",
            recentMoves = emptyList(),
            decisionLog = emptyList(),
            landmarks = LandmarksSnapshot(),
            visitedMinimap = VisitedMinimap(bitsBase64 = ""),
        )
        saveFile.writeText(SaveFormatCodec.toJson(save))

        // Fresh session — simulates "reset → load".
        val session2 = EmulatorSession()
        val toolset2 = EmulatorToolset(session2)
        check(toolset2.loadRom(romPath).ok)

        val parsed = SaveFormatCodec.fromJson(saveFile.readText())
        val decodedBytes = SaveFormatCodec.decodeEmulatorBytes(parsed)
        decodedBytes.toList() shouldBe preBytes.toList()    // base64 byte fidelity

        val ok = session2.loadState(decodedBytes)
        ok shouldBe true

        val postBytes = session2.saveState()
        postBytes.toList() shouldBe preBytes.toList()       // loaded state produces identical save

        parsed.rom shouldBe "ff1.nes"
        parsed.currentIntent shouldBe "integration test"
    }
})
