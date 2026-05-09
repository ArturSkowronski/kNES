package knes.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.api.EmulatorSession
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Diagnostic test for MMC1 + PPU savestate restore on FF1.
 *
 * Loads the FF1 ROM, then restores `/tmp/spec5-shop-entered.savestate`
 * (a savestate produced by Run A at boot_savestate_dumped point),
 * advances a small number of frames so the PPU re-renders, and dumps
 * the resulting framebuffer to `/tmp/spec5-postload-debug.png` for
 * visual inspection.
 *
 * The trace from Run B showed RAM-side mapId/mapflags/smPlayer all
 * matching the saved shop-entry state, but the screenshot the
 * vision-advisor saw was a title menu / character select. This test
 * narrows whether the PPU's pre-frame state (CHR-RAM, nametables,
 * pattern-tile decode cache, palette, ntable1 mirroring) is being
 * restored correctly across save/load.
 *
 * Skipped silently when the savestate fixture file isn't present.
 */
class SavestateRoundtripDebug : FunSpec({

    test("Main.kt-style flow: loadRom → applyProfile → loadState → screenshot") {
        val romFile = File("../roms/ff.nes").takeIf { it.exists() } ?: File("roms/ff.nes")
        val saveFile = File("/tmp/spec5-shop-entered.savestate")
        if (!romFile.exists() || !saveFile.exists()) {
            println("[mainflow] missing ROM or savestate; skipping"); return@test
        }
        val session = EmulatorSession()
        session.loadRom(romFile.absolutePath) shouldBe true
        // applyProfile equivalent: set watched addresses (cosmetic)
        session.setWatchedAddresses(mapOf("currentMapId" to 0x0048, "mapflags" to 0x002D, "smPlayerX" to 0x0068, "smPlayerY" to 0x0069))
        // Test 1: NO advanceFrames pre-load (Main.kt behaviour) — does loadState stick?
        // Test 2: tiny pre-load — does initialization step matter?
        // We toggle below.
        val preWarmFrames = System.getenv("PRE_WARM_FRAMES")?.toIntOrNull() ?: 0
        if (preWarmFrames > 0) session.advanceFrames(preWarmFrames)
        val ok = session.loadState(saveFile.readBytes())
        println("[mainflow] loadState ok=$ok")
        ok shouldBe true
        // Now Main passes control to AgentSession; first toolset.step pumps frames.
        // Simulate: 120 frame advance, then screenshot.
        session.advanceFrames(120)
        val png = session.getScreenPng()
        File("/tmp/spec5-mainflow-postload-120f.png").writeBytes(png)
        val mapId = session.readMemory(0x0048)
        val char1Str = session.readMemory(0x6110)
        println("[mainflow] post-load+120f: mapId=$mapId char1_str=$char1Str")
    }

    test("local save→load→save round-trip should be identity") {
        val romFile = File("../roms/ff.nes").takeIf { it.exists() }
            ?: File("roms/ff.nes")
        if (!romFile.exists()) {
            println("[savestate-debug] no ROM at $romFile; skipping"); return@test
        }
        val s1 = EmulatorSession()
        s1.loadRom(romFile.absolutePath) shouldBe true
        s1.advanceFrames(200) // get into intro/some scene
        val saved1 = s1.saveState()

        val s2 = EmulatorSession()
        s2.loadRom(romFile.absolutePath) shouldBe true
        s2.loadState(saved1) shouldBe true
        val saved2 = s2.saveState()

        println("[roundtrip] saved1=${saved1.size} saved2=${saved2.size}")
        var firstDiff = -1
        var diffs = 0
        val cmp = minOf(saved1.size, saved2.size)
        for (i in 0 until cmp) {
            if (saved1[i] != saved2[i]) {
                if (firstDiff == -1) firstDiff = i
                diffs++
            }
        }
        println("[roundtrip] firstDiff=$firstDiff totalDiffs=$diffs sizeMatch=${saved1.size == saved2.size}")
        saved1.size shouldBe saved2.size
        diffs shouldBe 0
    }

    test("load FF1 savestate and dump screenshot") {
        val romPath = "${System.getProperty("user.dir").trimEnd('/')}/../roms/ff.nes"
        val romFile = File(romPath).takeIf { it.exists() }
            ?: File("roms/ff.nes").takeIf { it.exists() }
            ?: File("../roms/ff.nes")
        if (!romFile.exists()) {
            println("[savestate-debug] no ROM at $romFile; skipping")
            return@test
        }
        val saveFile = File("/tmp/spec5-shop-entered.savestate")
        if (!saveFile.exists()) {
            println("[savestate-debug] no savestate at /tmp/spec5-shop-entered.savestate; skipping")
            return@test
        }

        val session = EmulatorSession()
        session.loadRom(romFile.absolutePath) shouldBe true

        // Pump enough frames to get past FF1 boot copyright screen.
        session.advanceFrames(120)
        val preLoadPng = session.getScreenPng()
        File("/tmp/spec5-preload-debug.png").writeBytes(preLoadPng)
        println("[savestate-debug] pre-load: frameCount=${session.frameCount}")

        // Restore savestate
        val bytes = saveFile.readBytes()
        val ok = session.loadState(bytes)
        println("[savestate-debug] loadState ok=$ok bytes=${bytes.size} frameCount=${session.frameCount}")
        ok shouldBe true
        val regsImmediate = session.readCpuRegs()
        val mapIdImm = session.readMemory(0x0048)
        val mapflagsImm = session.readMemory(0x002D)
        val smXImm = session.readMemory(0x0068)
        val smYImm = session.readMemory(0x0069)
        println("[savestate-debug] IMMEDIATE post-load: pc=0x${regsImmediate["pc"]?.toString(16)} sp=0x${regsImmediate["sp"]?.toString(16)} mapId=$mapIdImm mapflags=0x${mapflagsImm.toString(16)} smPlayer=($smXImm,$smYImm)")

        // Immediate screenshot — what's in readyBuffer right after load
        val immediatePng = session.getScreenPng()
        File("/tmp/spec5-postload-immediate.png").writeBytes(immediatePng)

        session.advanceFrames(1)
        val after1 = session.getScreenPng()
        File("/tmp/spec5-postload-1f.png").writeBytes(after1)
        println("[savestate-debug] +1 frame: frameCount=${session.frameCount}")

        session.advanceFrames(9)
        val after10 = session.getScreenPng()
        File("/tmp/spec5-postload-10f.png").writeBytes(after10)
        println("[savestate-debug] +10 frames: frameCount=${session.frameCount}")

        session.advanceFrames(60)
        val after70 = session.getScreenPng()
        File("/tmp/spec5-postload-70f.png").writeBytes(after70)
        println("[savestate-debug] +70 frames: frameCount=${session.frameCount}")

        // FF1 RAM (Disch): currentMapId @ $0048, mapflags @ $002D, smPlayer X @ $0068, Y @ $0069.
        val mapId = session.readMemory(0x0048)
        val mapflags = session.readMemory(0x002D)
        val smX = session.readMemory(0x0068)
        val smY = session.readMemory(0x0069)
        val char1Str = session.readMemory(0x6110)
        println("[savestate-debug] post-load RAM: mapId=$mapId mapflags=0x${mapflags.toString(16)} smPlayer=($smX,$smY) char1_str=$char1Str")
        // CPU PC + bank state
        val regs = session.readCpuRegs()
        println("[savestate-debug] post-load CPU: pc=0x${regs["pc"]?.toString(16)} sp=0x${regs["sp"]?.toString(16)}")

        // Sample nametable[0] tile indices — should look like a shop dialog if state restored.
        val sb = StringBuilder()
        sb.append("nametable[0] sampling (rows 8-12 of 32x30):\n")
        for (y in 8..12) {
            for (x in 0..15) {
                val t = session.readNametableTile(0, x, y)
                sb.append("%02x ".format(t))
            }
            sb.append("\n")
        }
        println(sb)
        // Sample CHR-RAM bytes ($0000-$001F) — should be non-zero for any rendered scene
        val ppuMemSample = (0..31).joinToString(" ") { i ->
            "%02x".format(session.nes.ppuMemory.load(i).toInt() and 0xFF)
        }
        println("[savestate-debug] CHR-RAM[0-31]: $ppuMemSample")
        val ppuPalSample = (0x3F00..0x3F1F).joinToString(" ") { i ->
            "%02x".format(session.nes.ppuMemory.load(i).toInt() and 0xFF)
        }
        println("[savestate-debug] palette[0x3F00-0x3F1F]: $ppuPalSample")

        // Round-trip diagnostic: re-save state immediately after load, compare bytes.
        // If mismatch → state-load discards/transforms data; if match → load is faithful
        // but rendered output diverges due to PPU runtime regen.
        val session2 = EmulatorSession()
        session2.loadRom(romFile.absolutePath) shouldBe true
        session2.advanceFrames(120)
        session2.loadState(bytes) shouldBe true
        // re-save WITHOUT any frame advance:
        val resaved = session2.saveState()
        println("[savestate-debug] re-saved bytes=${resaved.size} (orig=${bytes.size})")
        var firstDiff = -1
        var diffCount = 0
        val cmpLen = minOf(resaved.size, bytes.size)
        for (i in 0 until cmpLen) {
            if (resaved[i] != bytes[i]) {
                if (firstDiff == -1) firstDiff = i
                diffCount++
            }
        }
        println("[savestate-debug] roundtrip diff: firstDiff=$firstDiff totalDiffBytes=$diffCount of $cmpLen")
        // Check segment offsets to localise where divergence is
        if (firstDiff >= 0) {
            // 64KB cpuMemory + ppuMemory (32KB) + sprMemory + cpu (~30B) + mapper + ppu
            val cpuMemEnd = 1 + 64 * 1024
            val ppuMemEnd = cpuMemEnd + 32 * 1024
            val region = when {
                firstDiff < 1 -> "version-byte"
                firstDiff < cpuMemEnd -> "cpuMemory @ \$${(firstDiff - 1).toString(16)}"
                firstDiff < ppuMemEnd -> "ppuMemory @ \$${(firstDiff - cpuMemEnd).toString(16)}"
                else -> "later (sprMem/cpu/mapper/ppu)"
            }
            println("[savestate-debug] firstDiff in region: $region")
        }
        // Log diff bytes around firstDiff for raw inspection
        if (firstDiff >= 0) {
            val s = maxOf(0, firstDiff - 8)
            val e = minOf(cmpLen, firstDiff + 24)
            val origSlice = (s until e).joinToString(" ") { "%02x".format(bytes[it].toInt() and 0xFF) }
            val newSlice = (s until e).joinToString(" ") { "%02x".format(resaved[it].toInt() and 0xFF) }
            println("[savestate-debug] diff window orig: $origSlice")
            println("[savestate-debug] diff window resv: $newSlice")
        }
        // Also: find every distinct contiguous diff region
        val regions = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < cmpLen) {
            if (resaved[i] != bytes[i]) {
                val start = i
                while (i < cmpLen && resaved[i] != bytes[i]) i++
                regions += start to i
            } else {
                i++
            }
        }
        println("[savestate-debug] # contiguous diff regions: ${regions.size}")
        for ((s, e) in regions.take(8)) {
            println("[savestate-debug]   region [$s..$e) len=${e - s}")
        }
        println("[savestate-debug] images: /tmp/spec5-preload-debug.png, /tmp/spec5-postload-immediate.png, /tmp/spec5-postload-5f.png, /tmp/spec5-postload-35f.png")
    }
})
