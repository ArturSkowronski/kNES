package knes.emulator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import knes.emulator.input.InputHandler
import knes.emulator.utils.HiResTimer
import java.io.File

private fun host() = object : NesHost {
    override fun sendErrorMsg(message: String) {}
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0x40
    }
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
}

private fun loadedNes(): NES {
    val nes = NES(host(), NesConfig.HEADLESS)
    check(nes.loadRom(File("src/test/resources/nestest.nes").path))
    return nes
}

private fun NES.save(): ByteBuffer {
    val buf = ByteBuffer(256 * 1024, ByteBuffer.BO_LITTLE_ENDIAN)
    stateSave(buf)
    buf.resizeToCurrentPos()
    return ByteBuffer(buf.getBytes(), ByteBuffer.BO_LITTLE_ENDIAN)
}

class SavestateTest : FunSpec({

    test("loading a ROM records an identity") {
        val identity = loadedNes().romIdentity!!
        identity.prgBanks shouldNotBe 0
        identity.contentHash shouldNotBe 0
    }

    test("identity is stable across loads of the same ROM") {
        loadedNes().romIdentity shouldBe loadedNes().romIdentity
    }

    test("a state round-trips into the same machine") {
        val nes = loadedNes()
        repeat(3) { nes.stepFrame() }
        val savedPc = nes.cpu.REG_PC_NEW
        val savedRam = nes.cpuMemory.mem.copyOf()
        val saved = nes.save()

        repeat(20) { nes.stepFrame() }

        nes.stateLoad(saved) shouldBe true
        nes.cpu.REG_PC_NEW shouldBe savedPc
        nes.cpuMemory.mem.toList() shouldBe savedRam.toList()
    }

    test("the round-trip test would notice if nothing were restored") {
        // Guards the test above: twenty frames must actually move the machine, or
        // "restored correctly" and "never changed" look identical.
        val nes = loadedNes()
        repeat(3) { nes.stepFrame() }
        val before = nes.cpuMemory.mem.copyOf()

        repeat(20) { nes.stepFrame() }

        nes.cpuMemory.mem.toList() shouldNotBe before.toList()
    }

    test("a state carries the magic and version of the current format") {
        val bytes = loadedNes().save()
        bytes.readStringAscii(4) shouldBe Savestate.MAGIC
        bytes.readInt() shouldBe Savestate.VERSION
    }

    test("a state from another ROM is refused loudly, not restored") {
        val nes = loadedNes()
        val saved = nes.save()

        // Rewrite the stored content hash so it describes a different cartridge.
        saved.goTo(4 + 4 + 4 + 4 + 4 + 4)
        saved.putInt(0x0BADC0DE)
        saved.goTo(0)

        val error = shouldThrow<SavestateMismatchException> { nes.stateLoad(saved) }
        error.message!! shouldContain "different ROM"
    }

    test("an unknown version is rejected rather than misread") {
        val nes = loadedNes()
        val saved = nes.save()
        saved.goTo(4)
        saved.putInt(99)
        saved.goTo(0)

        nes.stateLoad(saved) shouldBe false
    }

    test("the original positional format still loads") {
        val nes = loadedNes()
        val legacy = ByteBuffer(256 * 1024, ByteBuffer.BO_LITTLE_ENDIAN)
        legacy.putByte(Savestate.LEGACY_VERSION.toShort())
        nes.cpuMemory.stateSave(legacy)
        nes.ppuMemory.stateSave(legacy)
        nes.sprMemory.stateSave(legacy)
        nes.cpu.stateSave(legacy)
        nes.memoryMapper?.stateSave(legacy)
        nes.ppu.stateSave(legacy)
        legacy.resizeToCurrentPos()

        nes.stateLoad(ByteBuffer(legacy.getBytes(), ByteBuffer.BO_LITTLE_ENDIAN)) shouldBe true
    }

    test("garbage is rejected, not treated as a legacy state") {
        val nes = loadedNes()
        val junk = ByteBuffer(64, ByteBuffer.BO_LITTLE_ENDIAN)
        junk.putByte(0x7F.toShort())
        junk.resizeToCurrentPos()

        nes.stateLoad(ByteBuffer(junk.getBytes(), ByteBuffer.BO_LITTLE_ENDIAN)) shouldBe false
    }
})
