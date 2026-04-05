package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.assertions.throwables.shouldThrow
import knes.emulator.input.InputHandler

class NesEmulatorSessionTest : FunSpec({

    test("initial state: no ROM loaded, frame 0") {
        val session = NesEmulatorSession()
        session.romLoaded shouldBe false
        session.frameCount shouldBe 0
    }

    test("all buttons released initially") {
        val session = NesEmulatorSession()
        session.getHeldButtons() shouldBe emptyList()
        for (i in 0 until InputHandler.NUM_KEYS) {
            session.nes.cpu // just verify NES is initialized
        }
    }

    test("setButtons holds specified buttons") {
        val session = NesEmulatorSession()
        session.setButtons(listOf("A", "RIGHT"))
        session.getHeldButtons() shouldContainExactlyInAnyOrder listOf("A", "RIGHT")
    }

    test("setButtons releases previous buttons") {
        val session = NesEmulatorSession()
        session.setButtons(listOf("A", "B"))
        session.setButtons(listOf("UP"))
        session.getHeldButtons() shouldContainExactlyInAnyOrder listOf("UP")
    }

    test("pressButton and releaseButton") {
        val session = NesEmulatorSession()
        session.pressButton("START")
        session.getHeldButtons() shouldContainExactlyInAnyOrder listOf("START")
        session.releaseButton("START")
        session.getHeldButtons() shouldBe emptyList()
    }

    test("releaseAll clears all buttons") {
        val session = NesEmulatorSession()
        session.setButtons(listOf("A", "B", "UP", "LEFT"))
        session.releaseAll()
        session.getHeldButtons() shouldBe emptyList()
    }

    test("button names are case-insensitive") {
        val session = NesEmulatorSession()
        session.setButtons(listOf("a", "Right", "START"))
        session.getHeldButtons() shouldContainExactlyInAnyOrder listOf("A", "RIGHT", "START")
    }

    test("unknown button throws") {
        val session = NesEmulatorSession()
        shouldThrow<IllegalArgumentException> {
            session.pressButton("TURBO")
        }
    }

    test("applyProfile returns false for unknown profile") {
        val session = NesEmulatorSession()
        session.applyProfile("nonexistent") shouldBe false
    }

    test("applyProfile succeeds for builtin profiles") {
        val session = NesEmulatorSession()
        session.applyProfile("smb") shouldBe true
        session.applyProfile("ff1") shouldBe true
    }

    test("getWatchedState returns values after profile applied") {
        val session = NesEmulatorSession()
        session.applyProfile("smb")
        val state = session.getWatchedState()
        state.containsKey("playerX") shouldBe true
        state.containsKey("lives") shouldBe true
    }

    test("loadRom returns false for invalid path") {
        val session = NesEmulatorSession()
        session.loadRom("/nonexistent/rom.nes") shouldBe false
        session.romLoaded shouldBe false
    }

    test("getScreenBase64 returns non-empty string") {
        val session = NesEmulatorSession()
        val base64 = session.getScreenBase64()
        base64.shouldNotBeEmpty()
    }

    test("reset clears state") {
        val session = NesEmulatorSession()
        session.setButtons(listOf("A", "B"))
        session.reset()
        session.getHeldButtons() shouldBe emptyList()
        session.frameCount shouldBe 0
    }
})
