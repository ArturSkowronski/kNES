package knes.agent.perception

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * V5.16 — empirical diagnosis of "input pipeline broken after loadState"
 * (V5.6 commit's anecdotal claim, never root-caused).
 *
 * Method: live boot → take a saveState → tap a direction → saveState
 * baseline → loadState (resume to mid-walk point) → tap same direction
 * → did the party move? Compare RAM coords before/after each step.
 *
 * Hypotheses to disambiguate:
 *   (a) buggy `mapperInternalStateLoad/Save` (MapperDefault.kt:89-99)
 *       shifts the buffer cursor; PPU stateLoad reads from wrong offset
 *       → garbage PPU regs (would show as visual corruption / freeze).
 *   (b) PAPU IRQ stale: papu not serialized, frame timer + IRQ flag stay
 *       at fresh init, CPU may be wedged in IRQ wait.
 *   (c) `frameCount` not reset; advanceFrames(n) target = frameCount + n
 *       still works relatively, so unlikely.
 *   (d) `ApiController.keyStates` stale: not part of savestate. If a
 *       button was held at saveState time, post-loadState it's still
 *       "held" → engine ignores new taps. SessionActionController
 *       `releaseAll()` after each step → unlikely in practice but worth
 *       verifying.
 *
 * Output: `println` snapshot of worldX/Y, controller.keyStates, and
 * mapper.joy1StrobeState before/after each transition. Then assertions
 * on whether movement actually happens after loadState.
 */
class V516LoadStateInputDiagTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("loadState input pipeline diagnosis (live boot)")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("3m")) {

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        check(PressStartUntilOverworld(toolset).invoke().ok)
        toolset.step(buttons = emptyList(), frames = 60)

        fun ramSnap(label: String): Map<String, Int> {
            val ram = toolset.getState().ram
            val keys = session.controller.getHeldButtons()
            val mapper = session.nes.memoryMapper
            val joyStrobe = mapper?.let {
                // Reflective read since strobe state is on MapperDefault
                try {
                    val f = it::class.java.getField("joy1StrobeState")
                    f.getInt(it)
                } catch (_: Exception) { -1 }
            } ?: -1
            println("[v516] $label: world=(${ram["worldX"]},${ram["worldY"]}) " +
                "frame=${ram["frameCount"] ?: "?"} held=$keys joyStrobe=$joyStrobe")
            return ram
        }

        val r0 = ramSnap("after-boot")
        val baseX = r0["worldX"] ?: error("worldX null")
        val baseY = r0["worldY"] ?: error("worldY null")

        // === Step 1: baseline tap, prove input works pre-saveState ===
        toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 12)
        val r1 = ramSnap("after-tap-DOWN-baseline")
        val movedBaseline = (r1["worldX"] != baseX) || (r1["worldY"] != baseY)
        println("[v516] baseline movement: $movedBaseline (was=($baseX,$baseY) now=(${r1["worldX"]},${r1["worldY"]}))")

        withClue("Sanity: input must work BEFORE any saveState/loadState") {
            movedBaseline shouldBe true
        }
        val midX = r1["worldX"]!!
        val midY = r1["worldY"]!!

        // === Step 2: saveState at this mid-walk point ===
        val savedBytes = session.saveState()
        println("[v516] saveState size=${savedBytes.size} bytes")
        ramSnap("after-saveState (no movement)")

        // === Step 3: tap UP, prove input still works between save/load ===
        toolset.tap(button = "UP", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 12)
        val r2 = ramSnap("after-tap-UP-post-save")
        val movedAfterSave = (r2["worldX"] != midX) || (r2["worldY"] != midY)
        println("[v516] post-save movement: $movedAfterSave")
        movedAfterSave shouldBe true
        val postUpX = r2["worldX"]!!
        val postUpY = r2["worldY"]!!

        // === Step 4: loadState — restore to mid-walk point ===
        check(session.loadState(savedBytes)) { "loadState returned false" }
        toolset.step(buttons = emptyList(), frames = 6)  // PPU settle
        val rL = ramSnap("after-loadState (should be back to mid)")

        withClue("loadState should restore worldX/Y to the pre-UP-tap state") {
            rL["worldX"] shouldBe midX
            rL["worldY"] shouldBe midY
        }

        // === Step 5: THE KEY TEST — tap DOWN after loadState. Does input work? ===
        toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 12)
        val r3 = ramSnap("after-tap-DOWN-post-loadState")
        val movedAfterLoad = (r3["worldX"] != midX) || (r3["worldY"] != midY)
        println("[v516] === post-loadState movement: $movedAfterLoad === " +
            "(was=($midX,$midY) now=(${r3["worldX"]},${r3["worldY"]}))")

        // === Step 6: try fix hypothesis (d) — releaseAll BEFORE retry ===
        if (!movedAfterLoad) {
            println("[v516] hypothesis-D test: releaseAll() then re-tap")
            session.controller.releaseAll()
            toolset.step(buttons = emptyList(), frames = 6)
            toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
            toolset.step(buttons = emptyList(), frames = 12)
            val r4 = ramSnap("after-releaseAll+retry")
            val movedWithRelease = (r4["worldX"] != midX) || (r4["worldY"] != midY)
            println("[v516] === movement after releaseAll: $movedWithRelease ===")
        }

        // No hard assertion on the post-load tap — we want the test to
        // PASS so we can read the diagnostic regardless of which path
        // produced movement. The println output is the deliverable.
        println("[v516] DIAGNOSIS:")
        println("[v516]   baseline_input_works=$movedBaseline")
        println("[v516]   post_save_input_works=$movedAfterSave")
        println("[v516]   post_load_input_works=$movedAfterLoad")
    }

    test("loadState input pipeline diagnosis (PERSISTENT FIXTURE FILE — Coneria8 path)")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("3m")) {
        // V5.16 part 2: replicate Coneria8VisualDiffTest's exact loadState
        // sequence — fresh session, no live boot, just loadRom + loadState
        // from the persistent ff1-post-boot.savestate fixture file.

        val fixtureFile = File("src/test/resources/fixtures/ff1-post-boot.savestate")
        if (!fixtureFile.exists()) {
            println("[v516-fix] fixture missing at ${fixtureFile.absolutePath} — skipping")
            return@config
        }

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        // NB: NO PressStartUntilOverworld here — straight from loadRom into loadState.
        check(session.loadState(fixtureFile.readBytes())) { "fixture loadState failed" }
        toolset.step(buttons = emptyList(), frames = 1)

        val r0 = toolset.getState().ram
        val baseX = r0["worldX"] ?: -1
        val baseY = r0["worldY"] ?: -1
        println("[v516-fix] after fixture loadState: world=($baseX,$baseY) " +
            "screenState=0x${(r0["screenState"]?:0).toString(16)} " +
            "mapflags=0x${(r0["mapflags"]?:0).toString(16)}")

        toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 12)
        val r1 = toolset.getState().ram
        val moved = (r1["worldX"] != baseX) || (r1["worldY"] != baseY)
        println("[v516-fix] after tap-DOWN: world=(${r1["worldX"]},${r1["worldY"]}) moved=$moved")

        // Try with longer settle
        toolset.step(buttons = emptyList(), frames = 60)
        toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 24)
        val r2 = toolset.getState().ram
        val moved2 = (r2["worldX"] != baseX) || (r2["worldY"] != baseY)
        println("[v516-fix] after settle+tap: world=(${r2["worldX"]},${r2["worldY"]}) moved=$moved2")

        println("[v516-fix] DIAGNOSIS:")
        println("[v516-fix]   fixture_input_works=$moved")
        println("[v516-fix]   fixture_input_works_with_settle=$moved2")

        // Hypothesis test: CPU/PPU PC state after loadState
        val regs = session.readCpuRegs()
        println("[v516-fix] cpu regs after fixture loadState: " +
            "pc=0x${regs["pc"]?.toString(16)} sp=0x${regs["sp"]?.toString(16)} " +
            "a=0x${regs["a"]?.toString(16)}")

        // Long-form try: many empty cycles before first input — does PPU eventually
        // resync NMI / vblank state once enough frames flow?
        toolset.step(buttons = emptyList(), frames = 240)
        toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 24)
        val r3 = toolset.getState().ram
        val moved3 = (r3["worldX"] != baseX) || (r3["worldY"] != baseY)
        val regs3 = session.readCpuRegs()
        println("[v516-fix] after 240+tap settle: world=(${r3["worldX"]},${r3["worldY"]}) moved=$moved3 " +
            "pc=0x${regs3["pc"]?.toString(16)}")
    }

    test("loadState fix attempt: papu.start() before loadState (no CPU thread)")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("3m")) {
        // Hypothesis (user prompted): the fail mode is start/stop emulator —
        // NES.stateLoad's branch `if (cpu.isRunning) startEmulation()` never
        // fires in EmulatorSession standalone (cpu.isRunning is thread-based,
        // always false here). So PAPU never initializes its IRQ requester
        // and the CPU wedges waiting for an IRQ.
        //
        // Try: explicitly call nes.startEmulation() before loadState.

        val fixtureFile = File("src/test/resources/fixtures/ff1-post-boot.savestate")
        if (!fixtureFile.exists()) {
            println("[v516-startem] fixture missing — skipping"); return@config
        }
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        // KEY hypothesis: papu must be started so its IRQ scheduling exists. We
        // intentionally do NOT call cpu.beginExecution() (that would fork a
        // thread that fights with our sync advanceFrames). Just papu.start().
        session.nes.papu.start()
        // Now load the fixture.
        check(session.loadState(fixtureFile.readBytes()))
        toolset.step(buttons = emptyList(), frames = 6)

        val r0 = toolset.getState().ram
        val baseX = r0["worldX"] ?: -1
        val baseY = r0["worldY"] ?: -1
        println("[v516-startem] after startEm+stopEm+loadState: world=($baseX,$baseY)")
        toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 12)
        val r1 = toolset.getState().ram
        val moved = (r1["worldX"] != baseX) || (r1["worldY"] != baseY)
        println("[v516-startem] after tap: world=(${r1["worldX"]},${r1["worldY"]}) moved=$moved")
        println("[v516-startem] DIAGNOSIS: explicit_startEmulation_fixes_loadState=$moved")
    }

    test("loadState input pipeline diagnosis (FIXTURE FILE + boot-first warmup)")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("3m")) {
        // V5.16 part 3: hypothesis — fixture loadState fails because the CPU
        // / PPU were never "warmed up" via live boot in the current process.
        // Workaround attempt: do PressStartUntilOverworld FIRST, then load
        // the fixture on top. If movement works, we have a recipe for fixing
        // Coneria8 without abandoning fixtures entirely.

        val fixtureFile = File("src/test/resources/fixtures/ff1-post-boot.savestate")
        if (!fixtureFile.exists()) {
            println("[v516-warm] fixture missing — skipping")
            return@config
        }

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")
        // Live boot to "warm up" the emulator (CPU thread state, PPU NMI cycle, etc.)
        check(PressStartUntilOverworld(toolset).invoke().ok)
        toolset.step(buttons = emptyList(), frames = 60)
        // NOW load the persistent fixture on top of warmed state.
        check(session.loadState(fixtureFile.readBytes()))
        toolset.step(buttons = emptyList(), frames = 6)

        val r0 = toolset.getState().ram
        val baseX = r0["worldX"] ?: -1
        val baseY = r0["worldY"] ?: -1
        println("[v516-warm] after warm-boot+fixture loadState: world=($baseX,$baseY)")

        toolset.tap(button = "DOWN", count = 1, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 12)
        val r1 = toolset.getState().ram
        val moved = (r1["worldX"] != baseX) || (r1["worldY"] != baseY)
        println("[v516-warm] after tap-DOWN: world=(${r1["worldX"]},${r1["worldY"]}) moved=$moved")
        println("[v516-warm] DIAGNOSIS: warm_boot_then_fixture_works=$moved")
    }
})
