package knes.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import knes.emulator.RomIdentity
import java.io.File

private fun session(): EmulatorSession {
    val session = EmulatorSession()
    val rom = File("src/test/resources/nestest.nes")
    check(rom.exists()) { "missing fixture: ${rom.absolutePath}" }
    check(session.loadRom(rom.absolutePath)) { "failed to load nestest.nes" }
    return session
}

/** Everything a run can be judged on: where it ended and what it wrote. */
private fun EmulatorSession.fingerprint(): Pair<Int, List<Int>> =
    frameCount to (0 until 0x0800).map { readMemory(it) }

class ReplayTest : FunSpec({

    test("a replay round-trips through its text form") {
        val replay = Replay(
            rom = RomIdentity(mapperId = 0, prgBanks = 2, chrBanks = 1, mirroring = 0, contentHash = 0x1A2B3C4D),
            entries = listOf(
                ReplayEntry(30),
                ReplayEntry(5, listOf("A")),
                ReplayEntry(10, listOf("RIGHT", "A")),
            ),
        )

        Replay.parse(replay.encode()) shouldBe replay
    }

    test("the text form is readable and diffable") {
        val text = Replay(entries = listOf(ReplayEntry(30), ReplayEntry(5, listOf("A")))).encode()

        text shouldContain "knes-replay 1"
        text shouldContain "30 -"
        text shouldContain "5 A"
    }

    test("comments and blank lines are ignored") {
        val replay = Replay.parse(
            """
            knes-replay 1
            # boot past the title
            30 -

            5 START   # press through
            """.trimIndent()
        )

        replay.entries shouldBe listOf(ReplayEntry(30), ReplayEntry(5, listOf("START")))
        replay.frames shouldBe 35
    }

    test("malformed scripts are rejected with the offending line") {
        shouldThrow<IllegalArgumentException> { Replay.parse("nope 1\n10 -") }
        shouldThrow<IllegalArgumentException> { Replay.parse("knes-replay 99\n10 -") }
        shouldThrow<IllegalArgumentException> { Replay.parse("knes-replay 1\n10") }
        shouldThrow<IllegalArgumentException> { Replay.parse("knes-replay 1\n0 A") }
        shouldThrow<IllegalArgumentException> { Replay.parse("") }
    }

    test("the same replay from the same start lands in the same place") {
        val script = Replay.parse("knes-replay 1\n10 -\n5 A\n10 START")

        val first = session().also { it.play(script) }.fingerprint()
        val second = session().also { it.play(script) }.fingerprint()

        first shouldBe second
    }

    test("different input produces a different run, so the determinism test can fail") {
        // Guards the test above: if input made no difference, "deterministic" would be
        // indistinguishable from "nothing happens".
        val quiet = session().also { it.play(Replay.parse("knes-replay 1\n25 -")) }.fingerprint()
        val busy = session().also { it.play(Replay.parse("knes-replay 1\n25 START")) }.fingerprint()

        quiet.first shouldBe busy.first
        quiet shouldNotBe busy
    }

    test("save, replay, restore, replay again — both runs agree") {
        val script = Replay.parse("knes-replay 1\n8 -\n4 A\n8 START")

        val session = session()
        session.play(Replay.parse("knes-replay 1\n5 -"))
        val checkpoint = session.saveState()

        val fromLive = session.also { it.play(script) }.fingerprint()

        session.loadState(checkpoint) shouldBe true
        val fromRestored = session.also { it.play(script) }.fingerprint()

        fromRestored.second shouldBe fromLive.second
    }

    test("a replay recorded on another ROM is refused") {
        val script = Replay(
            rom = RomIdentity(mapperId = 7, prgBanks = 9, chrBanks = 9, mirroring = 1, contentHash = 0xDEAD),
            entries = listOf(ReplayEntry(5)),
        )

        val error = shouldThrow<ReplayMismatchException> { session().play(script) }
        error.message!! shouldContain "different ROM"
    }

    test("a replay without a rom line plays anywhere") {
        session().play(Replay.parse("knes-replay 1\n5 -")) shouldBe 5
    }
})
