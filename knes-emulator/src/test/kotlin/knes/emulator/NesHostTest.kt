package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.HiResTimer

class NesHostTest : FunSpec({
    test("NES can be constructed from a host port without depending on GUI") {
        val joy1 = object : InputHandler {
            override fun getKeyState(padKey: Int): Short = 0
        }
        val host = object : NesHost {
            override fun getJoy1(): InputHandler = joy1
            override fun getJoy2(): InputHandler? = null
            override fun getTimer(): HiResTimer = HiResTimer()
            override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
            override fun sendErrorMsg(message: String) {}
            override fun sendDebugMessage(message: String) {}
            override fun destroy() {}
        }

        val nes = NES(host)

        nes.inputHandler shouldBe joy1
        nes.inputHandler2 shouldBe null
    }

    test("legacy GUI remains supported through an adapter") {
        val joy1 = object : InputHandler {
            override fun getKeyState(padKey: Int): Short = 0
        }
        val gui = object : GUI {
            override fun getJoy1(): InputHandler = joy1
            override fun getJoy2(): InputHandler? = null
            override fun getTimer(): HiResTimer = HiResTimer()
            override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
            override fun sendErrorMsg(message: String) {}
            override fun sendDebugMessage(message: String) {}
            override fun destroy() {}
        }

        val nes = NES(gui)

        nes.inputHandler shouldBe joy1
    }
})
