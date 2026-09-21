package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import knes.debug.GameProfile

class EmulatorSessionTest : FunSpec({

    test("a standalone session starts with no ROM at frame 0") {
        val session = EmulatorSession()
        session.romLoaded shouldBe false
        session.frameCount shouldBe 0
        session.shared shouldBe false
    }

    test("loadRom reports failure for a path that does not exist") {
        val session = EmulatorSession()
        session.loadRom("/nonexistent/rom.nes") shouldBe false
        session.romLoaded shouldBe false
    }

    test("the screen can be read before a ROM is loaded") {
        EmulatorSession().getScreenBase64().shouldNotBeEmpty()
    }

    test("reset clears the frame count and releases held buttons") {
        val session = EmulatorSession()
        session.controller.setButtons(listOf("A", "B"))
        session.reset()
        session.controller.getHeldButtons() shouldBe emptyList()
        session.frameCount shouldBe 0
    }

    test("watched state reports every address of an applied profile") {
        val session = EmulatorSession()
        session.setWatchedAddresses(GameProfile.get("smb")!!.toWatchMap())
        val state = session.getWatchedState()
        state.containsKey("playerX") shouldBe true
        state.containsKey("lives") shouldBe true
        state.keys shouldBe GameProfile.get("smb")!!.toWatchMap().keys
    }

    test("watched state is empty until addresses are set") {
        EmulatorSession().getWatchedState() shouldBe emptyMap()
    }

    test("saveState refuses to run without a ROM") {
        val session = EmulatorSession()
        runCatching { session.saveState() }.isFailure shouldBe true
    }
})
