package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import knes.emulator.utils.HiResTimer
import java.io.File

/**
 * The free-running path: `startEmulation` hands the loop to a CPU thread, which runs
 * until `stopEmulation` asks it to stop.
 *
 * This is the half of the emulator the headless tests never touch — everything else
 * drives it a step at a time. Task C3c moves the loop out of the CPU, so this is the
 * behaviour that has to survive it, written down before the change rather than after.
 */
private fun host(onFrame: () -> Unit = {}) = object : NesHost {
    override fun sendErrorMsg(message: String) {}
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0x40
    }
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) { onFrame() }
}

private fun loadedNes(host: NesHost = host()): NES {
    val nes = NES(host, NesConfig.HEADLESS)
    check(nes.loadRom(File("src/test/resources/nestest.nes").absolutePath))
    return nes
}

/** Waits for [condition], failing rather than hanging if it never holds. */
private fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(5)
    }
    return false
}

class FreeRunningExecutionTest : FunSpec({

    test("startEmulation runs the CPU on its own thread and produces frames") {
        val nes = loadedNes()
        try {
            nes.startEmulation()

            eventually { nes.frameCount > 0 } shouldBe true
            nes.isRunning shouldBe true
        } finally {
            nes.stopEmulation()
        }
    }

    test("stopEmulation stops it, and it stays stopped") {
        val nes = loadedNes()
        nes.startEmulation()
        check(eventually { nes.frameCount > 0 }) { "emulation never started" }

        nes.stopEmulation()
        val settled = nes.frameCount
        Thread.sleep(50)

        nes.isRunning shouldBe false
        nes.frameCount shouldBe settled
    }

    test("the frame callback fires on the emulation thread, not the caller's") {
        val callerThread = Thread.currentThread()
        var frameThread: Thread? = null
        val nes = loadedNes(host { frameThread = Thread.currentThread() })

        try {
            nes.startEmulation()
            eventually { frameThread != null } shouldBe true
        } finally {
            nes.stopEmulation()
        }

        (frameThread !== callerThread) shouldBe true
    }

    test("starting twice does not leave two loops running") {
        val nes = loadedNes()
        try {
            nes.startEmulation()
            check(eventually { nes.frameCount > 0 }) { "emulation never started" }
            nes.startEmulation()

            nes.stopEmulation()
            val settled = nes.frameCount
            Thread.sleep(50)

            // A second loop would keep counting after the first was told to stop.
            nes.frameCount shouldBe settled
        } finally {
            nes.stopEmulation()
        }
    }

    test("stopping something that was never started is harmless") {
        val nes = loadedNes()

        nes.stopEmulation()

        nes.isRunning shouldBe false
        nes.frameCount shouldBe 0L
    }

    test("a free-running emulator can be stopped and started again") {
        val nes = loadedNes()
        try {
            nes.startEmulation()
            check(eventually { nes.frameCount > 0 }) { "emulation never started" }
            nes.stopEmulation()
            val afterFirst = nes.frameCount

            nes.startEmulation()

            eventually { nes.frameCount > afterFirst } shouldBe true
            nes.frameCount shouldBeGreaterThan afterFirst
        } finally {
            nes.stopEmulation()
        }
    }

    test("saving state stops the loop and resumes it") {
        // stateSave/stateLoad stop emulation around the transfer and restart it. That
        // handshake is easy to break when the loop moves, so it is pinned here.
        val nes = loadedNes()
        try {
            nes.startEmulation()
            check(eventually { nes.frameCount > 0 }) { "emulation never started" }

            val buffer = ByteBuffer(256 * 1024, ByteBuffer.BO_LITTLE_ENDIAN)
            nes.stateSave(buffer)

            nes.isRunning shouldBe true
            val afterSave = nes.frameCount
            eventually { nes.frameCount > afterSave } shouldBe true
        } finally {
            nes.stopEmulation()
        }
    }
})
