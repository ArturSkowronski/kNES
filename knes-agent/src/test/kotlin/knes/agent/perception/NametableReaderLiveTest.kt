package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.api.EmulatorSession
import java.io.File

class NametableReaderLiveTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val romPresent = File(romPath).exists()

    test("reads a 16x16 viewport without crashing").config(enabled = romPresent) {
        val session = EmulatorSession()
        session.loadRom(romPath)
        session.advanceFrames(60)
        val classifier = TileClassifier.loadFromResources("ff1-overworld")
        val reader = NametableReader(session, classifier)
        val vp = reader.readViewport(partyWorldXY = 0 to 0)
        check(vp.width == ViewportMap.SIZE)
        check(vp.height == ViewportMap.SIZE)
    }
})
