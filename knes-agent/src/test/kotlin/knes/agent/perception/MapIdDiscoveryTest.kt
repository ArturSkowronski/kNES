package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * V5.3 — diagnostic: locate canonical currentMapId byte by RAM diff.
 *
 * Foundation question: profile says currentMapId is at \$0048 (V2.4 heuristic),
 * but datacrystal FF1 RAM map does not list \$0048 in zero page. Five sessions
 * (V2.4 → V2.6.5) hit interior nav bugs consistent with \$0048 not being the
 * real map-id byte. Vision-trace says agent enters "Castle courtyard" when
 * mapId=8 is loaded — suggesting our mapId interpretation is wrong.
 *
 * Method:
 *   1. Load post-boot fixture (party at overworld 146,158).
 *   2. Dump zero page \$00..\$FF  → state A.
 *   3. Raw-step into known interior trigger at (145,152) per V2.6.0 evidence.
 *   4. Dump zero page → state B.
 *   5. Diff A ↔ B; filter known-noise addresses (worldX/Y, localX/Y,
 *      locationType, encounter counter, PRNG seed, scroll/move counters,
 *      button input). Remaining bytes are candidates for currentMapId.
 *   6. Cross-check: also dump \$29,\$2A,\$68,\$69 before/after a single
 *      indoor tap — whichever pair changes is the canonical indoor coord.
 *
 * Output: stdout report of candidates with before/after values. No fixes
 * applied — this is pure observation.
 */
class MapIdDiscoveryTest : FunSpec({
    val romPath = "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val canRun = File(romPath).exists()

    test("diff zero page overworld vs interior to locate canonical currentMapId byte")
        .config(enabled = canRun, timeout = kotlin.time.Duration.parse("2m")) {

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(romPath).ok)
        toolset.applyProfile("ff1")

        // Live boot — fixture-load has known controller-state gap (per V5.2 commit comment
        // in ConeriaTownEmpiricalDiscoveryTest.kt:45). Slower (~10s) but reliable input.
        check(PressStartUntilOverworld(toolset).invoke().ok) { "PressStartUntilOverworld failed" }
        toolset.step(buttons = emptyList(), frames = 60)

        val ramOverworld = toolset.getState().ram
        println("[discovery] overworld state: world=(${ramOverworld["worldX"]},${ramOverworld["worldY"]}) " +
            "locType=0x${(ramOverworld["locationType"] ?: 0).toString(16)}")
        check((ramOverworld["locationType"] ?: -1) == 0) { "expected overworld (locType=0), got ${ramOverworld["locationType"]}" }

        // Snapshot A: full zero page on overworld.
        val zeroPageBefore = IntArray(256) { addr -> session.readMemory(addr) }
        println("[discovery] dumped \$00-\$FF zero page on overworld (frame=${session.frameCount})")

        // Walk from spawn (146,158) → (146,152) [N×6] → (145,152) [W×1] → tap UP to trigger interior.
        // Per V2.6 evidence (OverworldDumpTest.kt:91): stepping N from (145,152) transports
        // party into Coneria Town interior via invisible teleport tile at (145,151).
        suspend fun stepDir(name: String, n: Int) {
            repeat(n) {
                toolset.tap(button = name, count = 2, pressFrames = 6, gapFrames = 14)
                toolset.step(buttons = emptyList(), frames = 8)
            }
        }

        stepDir("UP", 6)
        var r = toolset.getState().ram
        println("[discovery] after UP×6: world=(${r["worldX"]},${r["worldY"]}) " +
            "locType=0x${(r["locationType"] ?: 0).toString(16)}")
        stepDir("LEFT", 1)
        r = toolset.getState().ram
        println("[discovery] after LEFT×1: world=(${r["worldX"]},${r["worldY"]}) " +
            "locType=0x${(r["locationType"] ?: 0).toString(16)}")
        // Trigger tap.
        stepDir("UP", 1)
        // Allow interior to settle.
        toolset.step(buttons = emptyList(), frames = 30)

        val ramInterior = toolset.getState().ram
        val locType = ramInterior["locationType"] ?: 0
        println("[discovery] after trigger UP: world=(${ramInterior["worldX"]},${ramInterior["worldY"]}) " +
            "local=(${ramInterior["localX"]},${ramInterior["localY"]}) " +
            "locType=0x${locType.toString(16)} currentMapId(\$48)=${ramInterior["currentMapId"]}")

        // FF1 has THREE location modes (revealed by this test):
        //   - Overworld: locType=0, world=valid, local=(0,0)
        //   - Town:      locType=0, world=frozen, local=non-zero    ← also indoor!
        //   - Castle:    locType=0xD1, world=frozen, local=non-zero
        // We detect "transition" by ANY of: locType != 0 OR local non-zero.
        val finalRam = toolset.getState().ram
        val finalLoc = finalRam["locationType"] ?: 0
        val finalLx = finalRam["localX"] ?: 0
        val finalLy = finalRam["localY"] ?: 0
        check(finalLoc != 0 || finalLx != 0 || finalLy != 0) {
            "could not transition off pure overworld — diagnostic blocked. " +
                "world=(${finalRam["worldX"]},${finalRam["worldY"]}) local=($finalLx,$finalLy) locType=0x${finalLoc.toString(16)}"
        }
        val locationKind = when {
            finalLoc != 0 -> "INDOOR (castle/dungeon)"
            else -> "TOWN (locType=0 but local non-zero)"
        }
        println("[discovery] location kind: $locationKind")

        // Snapshot B: full zero page in interior.
        val zeroPageAfter = IntArray(256) { addr -> session.readMemory(addr) }
        println("[discovery] dumped \$00-\$FF zero page in interior (frame=${session.frameCount})")

        // Save fixture B for future tests.
        val interiorFixture = File("src/test/resources/fixtures/ff1-coneria-interior-discovery.savestate")
        interiorFixture.parentFile.mkdirs()
        interiorFixture.writeBytes(session.saveState())
        println("[discovery] saved interior fixture: ${interiorFixture.path} (${interiorFixture.length()} bytes)")

        // Diff with noise filter. Datacrystal-known volatile bytes:
        //   $0020-$0026 = button input + temp data
        //   $0027-$002A = world/local X/Y
        //   $002D       = movement direction flag
        //   $0034-$0035 = scroll state + timer
        //   $0040       = horizontal movement counter
        //   $00B0-$00DF = music driver state
        //   $00F5-$00F7 = encounter counter + battle selection
        //   $000D       = locationType (will flip)
        //   $0000-$000F = misc temp (incl $000E-$000F = VRAM door tile addr)
        //   $0010-$001F = 16 bytes temp data
        val knownNoise = (0x20..0x2A).toSet() + setOf(0x000D, 0x002D) +
            (0x0034..0x0035).toSet() + setOf(0x0040) +
            (0x00B0..0x00DF).toSet() + (0x00F5..0x00F7).toSet() +
            (0x0000..0x001F).toSet()  // pre-emptively filter zero-page temp area

        println()
        println("=== ZERO PAGE DIFF (overworld → interior) ===")
        println("Filtering known noise: \$00-\$1F, \$20-\$2A, \$2D, \$34-\$35, \$40, \$B0-\$DF, \$F5-\$F7, \$0D")
        println()
        println("ALL CHANGED BYTES (no filter):")
        var changedTotal = 0
        for (addr in 0..0xFF) {
            if (zeroPageBefore[addr] != zeroPageAfter[addr]) {
                changedTotal++
                val noise = if (addr in knownNoise) " [noise]" else ""
                println("  \$%04X: 0x%02X → 0x%02X (Δ=%+d)%s".format(
                    addr, zeroPageBefore[addr], zeroPageAfter[addr],
                    zeroPageAfter[addr] - zeroPageBefore[addr], noise))
            }
        }
        println("Total changed: $changedTotal")

        println()
        println("CANDIDATES FOR currentMapId (changed AND not noise):")
        val candidates = (0..0xFF).filter { addr ->
            zeroPageBefore[addr] != zeroPageAfter[addr] && addr !in knownNoise
        }
        for (addr in candidates) {
            println("  \$%04X: 0x%02X → 0x%02X".format(addr, zeroPageBefore[addr], zeroPageAfter[addr]))
        }
        println("Candidate count: ${candidates.size}")

        // Highlight $0048 specifically (current profile heuristic).
        println()
        println("=== CURRENT HEURISTIC \$0048 ===")
        println("  before=0x%02X after=0x%02X (changed=%s)".format(
            zeroPageBefore[0x48], zeroPageAfter[0x48], zeroPageBefore[0x48] != zeroPageAfter[0x48]))

        // Datacrystal claims $0068-$0069 = "indoor position coordinates" — verify.
        println()
        println("=== INDOOR-COORD HYPOTHESIS (\$29/\$2A vs \$68/\$69) ===")
        println("  before: \$29=0x%02X \$2A=0x%02X \$68=0x%02X \$69=0x%02X".format(
            zeroPageBefore[0x29], zeroPageBefore[0x2A], zeroPageBefore[0x68], zeroPageBefore[0x69]))
        println("  after:  \$29=0x%02X \$2A=0x%02X \$68=0x%02X \$69=0x%02X".format(
            zeroPageAfter[0x29], zeroPageAfter[0x2A], zeroPageAfter[0x68], zeroPageAfter[0x69]))

        // Walk one step inside, see which coord pair changes.
        val coordSnap1 = intArrayOf(
            session.readMemory(0x29), session.readMemory(0x2A),
            session.readMemory(0x68), session.readMemory(0x69),
        )
        toolset.tap(button = "RIGHT", count = 2, pressFrames = 6, gapFrames = 14)
        toolset.step(buttons = emptyList(), frames = 16)
        val coordSnap2 = intArrayOf(
            session.readMemory(0x29), session.readMemory(0x2A),
            session.readMemory(0x68), session.readMemory(0x69),
        )
        println()
        println("=== INDOOR TAP RIGHT (which coord pair tracks party?) ===")
        println("  \$29: 0x%02X → 0x%02X (Δ=%+d)".format(coordSnap1[0], coordSnap2[0], coordSnap2[0] - coordSnap1[0]))
        println("  \$2A: 0x%02X → 0x%02X (Δ=%+d)".format(coordSnap1[1], coordSnap2[1], coordSnap2[1] - coordSnap1[1]))
        println("  \$68: 0x%02X → 0x%02X (Δ=%+d)".format(coordSnap1[2], coordSnap2[2], coordSnap2[2] - coordSnap1[2]))
        println("  \$69: 0x%02X → 0x%02X (Δ=%+d)".format(coordSnap1[3], coordSnap2[3], coordSnap2[3] - coordSnap1[3]))
        println("Expected: x-pair changes by +1 (one step right), y-pair unchanged.")

        // Persist report for future reference.
        val outDir = File("../docs/superpowers/notes/2026-05-04-mapid-discovery").also { it.mkdirs() }
        val report = StringBuilder()
        report.appendLine("# MapId Discovery RAM-Diff (2026-05-04)")
        report.appendLine()
        report.appendLine("Method: load post-boot fixture → walk to (145,152) → tap UP to enter interior.")
        report.appendLine()
        report.appendLine("## Final state")
        report.appendLine("- world=(${finalRam["worldX"]},${finalRam["worldY"]})")
        report.appendLine("- local=(${finalRam["localX"]},${finalRam["localY"]})")
        report.appendLine("- locationType=0x${finalLoc.toString(16)}")
        report.appendLine("- \$0048 (currentMapId profile)=${finalRam["currentMapId"]}")
        report.appendLine()
        report.appendLine("## Total changed bytes: $changedTotal")
        report.appendLine("## Candidates (post-noise-filter): ${candidates.size}")
        report.appendLine()
        report.appendLine("| Address | Before | After | Likely meaning |")
        report.appendLine("|---|---|---|---|")
        for (addr in candidates) {
            report.appendLine("| \$%04X | 0x%02X | 0x%02X | (investigate) |".format(addr, zeroPageBefore[addr], zeroPageAfter[addr]))
        }
        report.appendLine()
        report.appendLine("## Indoor coord probe")
        report.appendLine("- \$29 Δ=${coordSnap2[0] - coordSnap1[0]}, \$2A Δ=${coordSnap2[1] - coordSnap1[1]}")
        report.appendLine("- \$68 Δ=${coordSnap2[2] - coordSnap1[2]}, \$69 Δ=${coordSnap2[3] - coordSnap1[3]}")
        File(outDir, "report.md").writeText(report.toString())
        println()
        println("[discovery] saved report → ${File(outDir, "report.md").canonicalPath}")
    }
})
