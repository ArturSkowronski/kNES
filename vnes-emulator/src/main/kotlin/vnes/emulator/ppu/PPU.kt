package vnes.emulator.ppu

import vnes.emulator.ByteBuffer
import vnes.emulator.Memory
import vnes.emulator.ROM
import vnes.emulator.Tile
import vnes.emulator.cpu.CPU
import vnes.emulator.mappers.MemoryMapper
import vnes.emulator.ui.GUI
import vnes.emulator.utils.Globals
import vnes.emulator.utils.HiResTimer
import vnes.emulator.utils.NameTable
import vnes.emulator.utils.PaletteTable
import java.util.Arrays
import java.util.Locale
import java.util.Map
import java.util.function.Consumer
import java.util.stream.Collectors
import javax.sound.sampled.SourceDataLine

/*
vNES
Copyright © 2006-2013 Open Emulation Project

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program.  If not, see <http://www.gnu.org/licenses/>.
 */
public class PPU : PPUCycles {
    //    private NES nes;
    private var timer: HiResTimer? = null
    private var gui: GUI? = null
    private var ppuMem: Memory? = null
    private var sprMem: Memory? = null
    private var cpu: CPU? = null

    // Rendering Options:
    private val showSpr0Hit = false
    private var memoryMapper: MemoryMapper? = null
    private var palTable: PaletteTable? = null
    private var cpuMem: Memory? = null
    private var sourceDataLine: SourceDataLine? = null

    fun setShowSoundBuffer(showSoundBuffer: Boolean) {
        this.showSoundBuffer = showSoundBuffer
    }

    private var showSoundBuffer = false
    var isEnablePpuLogging: Boolean = false
    private val clipTVcolumn = true
    private val clipTVrow = false

    // Control Flags Register 1:
    private var f_nmiOnVblank = 0 // NMI on VBlank. 0=disable, 1=enable
    private var f_spriteSize = 0 // Sprite size. 0=8x8, 1=8x16
    private var f_bgPatternTable = 0 // Background Pattern Table address. 0=0x0000,1=0x1000
    private var f_spPatternTable = 0 // Sprite Pattern Table address. 0=0x0000,1=0x1000
    private var f_addrInc = 0 // PPU Address Increment. 0=1,1=32
    private var f_nTblAddress = 0 // Name Table Address. 0=0x2000,1=0x2400,2=0x2800,3=0x2C00

    // Control Flags Register 2:
    private var f_color = 0 // Background color. 0=black, 1=blue, 2=green, 4=red
    private var f_spVisibility = 0 // Sprite visibility. 0=not displayed,1=displayed
    private var f_bgVisibility = 0 // Background visibility. 0=Not Displayed,1=displayed
    private var f_spClipping = 0 // Sprite clipping. 0=Sprites invisible in left 8-pixel column,1=No clipping
    private var f_bgClipping = 0 // Background clipping. 0=BG invisible in left 8-pixel column, 1=No clipping
    private var f_dispType = 0 // Display type. 0=color, 1=monochrome

    // Status flags:
    private val STATUS_VRAMWRITE = 4
    private val STATUS_SLSPRITECOUNT = 5
    private val STATUS_SPRITE0HIT = 6
    private val STATUS_VBLANK = 7

    // VRAM I/O:
    private var vramAddress = 0
    private var vramTmpAddress = 0
    private var vramBufferedReadValue: Short = 0
    private var firstWrite = true // VRAM/Scroll Hi/Lo latch
    private var vramMirrorTable: IntArray? = null // Mirroring Lookup Table.
    private var i = 0

    // SPR-RAM I/O:
    private var sramAddress: Short = 0 // 8-bit only.

    // Counters:
    private var cntFV = 0
    private var cntV = 0
    private var cntH = 0
    private var cntVT = 0
    private var cntHT = 0

    // Registers:
    private var regFV = 0
    private var regV = 0
    private var regH = 0
    private var regVT = 0
    private var regHT = 0
    private var regFH = 0
    private var regS = 0

    // VBlank extension for PAL emulation:
    var vblankAdd: Int = 0
    private var curX = 0
    private var scanline = 0
    private var lastRenderedScanline = 0
    private var mapperIrqCounter = 0

    // Sprite data:
    private var sprX: IntArray = IntArray(64) // X coordinate
    private var sprY: IntArray = IntArray(64) // Y coordinate
    private var sprTile: IntArray = IntArray(64) // Tile Index (into pattern table)
    private var sprCol: IntArray = IntArray(64) // Upper two bits of color
    private var vertFlip: BooleanArray = BooleanArray(64) // Vertical Flip
    private var horiFlip: BooleanArray = BooleanArray(64) // Horizontal Flip
    private var bgPriority: BooleanArray = BooleanArray(64) // Background priority
    private var spr0HitX = 0 // Sprite #0 hit X coordinate
    private var spr0HitY = 0 // Sprite #0 hit Y coordinate
    var hitSpr0: Boolean = false

    // Tiles:
    @JvmField
    var ptTile: Array<Tile>? = null

    // Name table data:
    var ntable1: IntArray = IntArray(4)
    var nameTable: Array<NameTable?> = arrayOfNulls<NameTable>(4)
    var currentMirroring: Int = -1

    // Palette data:
    private val sprPalette = IntArray(16)
    private val imgPalette = IntArray(16)

    // Misc:
    private var scanlineAlreadyRendered = false
    private var requestEndFrame = false
    private var nmiOk = false
    private var nmiCounter = 0
    private var tmp: Short = 0
    private var dummyCycleToggle = false

    // Vars used when updating regs/address:
    private var address = 0
    private var b1 = 0
    private var b2 = 0

    // Variables used when rendering:
    private val attrib = IntArray(32)
    private val bgbuffer = IntArray(256 * 240)
    private val pixrendered = IntArray(256 * 240)
    private val spr0dummybuffer = IntArray(256 * 240)
    private val dummyPixPriTable = IntArray(256 * 240)
    private val oldFrame = IntArray(256 * 240)

    @JvmField
    var buffer: IntArray = IntArray(256 * 240)

    private var tpix: IntArray = IntArray(64)

    val scanlineChanged: BooleanArray = BooleanArray(240)

    var isRequestRenderAll: Boolean = false
    private var validTileData = false
    private var att = 0
    var scantile: Array<Tile?>? = arrayOfNulls<Tile>(32)
    var t: Tile? = null

    // These are temporary variables used in rendering and sound procedures.
    // Their states outside of those procedures can be ignored.
    private var curNt = 0
    private var destIndex = 0
    private var x = 0
    private var y = 0
    private var sx = 0
    private var si = 0
    private var ei = 0
    private var tile = 0
    private var col = 0
    private var baseTile = 0
    private var tscanoffset = 0
    private var srcy1 = 0
    private var srcy2 = 0
    private var bufferSize = 0
    private var available = 0
    private var scale = 0

    override fun setCycles(cycles: Int) {
        this.cycles = cycles
    }

    private var cycles = 0

    // Maps to store pixel color counts for debugging
    private val currentFrameColorCounts: MutableMap<Int?, Int?> = HashMap<Int?, Int?>()
    private val previousFrameColorCounts: MutableMap<Int?, Int?> = HashMap<Int?, Int?>()

    val topColors: MutableList<MutableMap.MutableEntry<Int?, Int?>?>
        /**
         * Returns the top 5 most common colors in the current frame.
         *
         * @return A list of Map.Entry objects containing the color (key) and count (value)
         */
        get() = currentFrameColorCounts.entries
            .stream()
            .sorted(Map.Entry.comparingByValue<Int?, Int?>().reversed())
            .limit(5)
            .collect(Collectors.toList())

    fun init(
        gui: GUI,
        ppuMem: Memory?,
        sprMem: Memory?,
        cpuMem: Memory,
        cpu: CPU,
        memoryMapper: MemoryMapper?,
        sourceDataLine: SourceDataLine?,
        palTable: PaletteTable
    ) {
        this.gui = gui
        this.ppuMem = ppuMem
        this.sprMem = sprMem
        this.cpuMem = cpuMem
        this.cpu = cpu
        this.sourceDataLine = sourceDataLine
        this.memoryMapper = memoryMapper
        this.palTable = palTable

        updateControlReg1(0)
        updateControlReg2(0)

        // Initialize misc vars:
        scanline = 0
        timer = gui.getTimer()

        // Create sprite arrays:
        sprX = IntArray(64)
        sprY = IntArray(64)
        sprTile = IntArray(64)
        sprCol = IntArray(64)
        vertFlip = BooleanArray(64)
        horiFlip = BooleanArray(64)
        bgPriority = BooleanArray(64)

        // Create pattern table tile buffers:
        if (ptTile == null) {
            val tempArray = Array<Tile>(512) { Tile() }
            ptTile = tempArray
        }

        // Create nametable buffers:
        nameTable = arrayOfNulls<NameTable>(4)
        for (i in 0..3) {
            nameTable[i] = NameTable(32, 32, "Nt" + i)
        }

        // Initialize mirroring lookup table:
        vramMirrorTable = IntArray(0x8000)
        for (i in 0..0x7fff) {
            vramMirrorTable!![i] = i
        }

        lastRenderedScanline = -1
        curX = 0

        // Initialize old frame buffer:
        for (i in oldFrame.indices) {
            oldFrame[i] = -1
        }
    }


    // Sets Nametable mirroring.
    fun setMirroring(mirroring: Int) {
        if (mirroring == currentMirroring) {
            return
        }

        currentMirroring = mirroring
        triggerRendering()

        // Remove mirroring:
        if (vramMirrorTable == null) {
            vramMirrorTable = IntArray(0x8000)
        }
        for (i in 0..0x7fff) {
            vramMirrorTable!![i] = i
        }

        // Palette mirroring:
        defineMirrorRegion(0x3f20, 0x3f00, 0x20)
        defineMirrorRegion(0x3f40, 0x3f00, 0x20)
        defineMirrorRegion(0x3f80, 0x3f00, 0x20)
        defineMirrorRegion(0x3fc0, 0x3f00, 0x20)

        // Additional mirroring:
        defineMirrorRegion(0x3000, 0x2000, 0xf00)
        defineMirrorRegion(0x4000, 0x0000, 0x4000)

        if (mirroring == ROM.Companion.HORIZONTAL_MIRRORING) {
            // Horizontal mirroring.


            ntable1[0] = 0
            ntable1[1] = 0
            ntable1[2] = 1
            ntable1[3] = 1

            defineMirrorRegion(0x2400, 0x2000, 0x400)
            defineMirrorRegion(0x2c00, 0x2800, 0x400)
        } else if (mirroring == ROM.Companion.VERTICAL_MIRRORING) {
            // Vertical mirroring.

            ntable1[0] = 0
            ntable1[1] = 1
            ntable1[2] = 0
            ntable1[3] = 1

            defineMirrorRegion(0x2800, 0x2000, 0x400)
            defineMirrorRegion(0x2c00, 0x2400, 0x400)
        } else if (mirroring == ROM.Companion.SINGLESCREEN_MIRRORING) {
            // Single Screen mirroring

            ntable1[0] = 0
            ntable1[1] = 0
            ntable1[2] = 0
            ntable1[3] = 0

            defineMirrorRegion(0x2400, 0x2000, 0x400)
            defineMirrorRegion(0x2800, 0x2000, 0x400)
            defineMirrorRegion(0x2c00, 0x2000, 0x400)
        } else if (mirroring == ROM.Companion.SINGLESCREEN_MIRRORING2) {
            ntable1[0] = 1
            ntable1[1] = 1
            ntable1[2] = 1
            ntable1[3] = 1

            defineMirrorRegion(0x2400, 0x2400, 0x400)
            defineMirrorRegion(0x2800, 0x2400, 0x400)
            defineMirrorRegion(0x2c00, 0x2400, 0x400)
        } else {
            // Assume Four-screen mirroring.

            ntable1[0] = 0
            ntable1[1] = 1
            ntable1[2] = 2
            ntable1[3] = 3
        }
    }


    // Define a mirrored area in the address lookup table.
    // Assumes the regions don't overlap.
    // The 'to' region is the region that is physically in memory.
    private fun defineMirrorRegion(fromStart: Int, toStart: Int, size: Int) {
        for (i in 0 until size) {
            vramMirrorTable!![fromStart + i] = toStart + i
        }
    }

    // Emulates PPU cycles
    override fun emulateCycles() {
        //int n = (!requestEndFrame && curX+cycles<341 && (scanline-20 < spr0HitY || scanline-22 > spr0HitY))?cycles:1;

        while (cycles > 0) {
            if (scanline - 21 == spr0HitY) {
                if ((curX == spr0HitX) && (f_spVisibility == 1)) {
                    // Set sprite 0 hit flag:
                    setStatusFlag(STATUS_SPRITE0HIT, true)
                }
            }

            if (requestEndFrame) {
                nmiCounter--
                if (nmiCounter == 0) {
                    requestEndFrame = false
                    startVBlank()
                }
            }

            curX++
            if (curX == 341) {
                curX = 0
                endScanline()
            }

            cycles--
        }
    }

    fun startVBlank() {
        // Start VBlank period:
        // Do NMI:

        cpu!!.requestIrq(CPU.Companion.IRQ_NMI)

        // Make sure everything is rendered:
        if (lastRenderedScanline < 239) {
            renderFramePartially(
                gui!!.getScreenView().getBuffer(),
                lastRenderedScanline + 1,
                240 - lastRenderedScanline
            )
        }

        /**Generate here debbuging info for the framebuffer. I want you to aggregate pixels per color and show it in the console. I want to show it only if changed between frames */
        // Clear current frame color counts
        currentFrameColorCounts.clear()

        // Get the buffer and count pixels by color
        val frameBuffer = gui!!.getScreenView().getBuffer()
        for (i in frameBuffer.indices) {
            val color = frameBuffer[i]
            currentFrameColorCounts.put(color, currentFrameColorCounts.getOrDefault(color, 0)!! + 1)
        }

        // Get the top 5 colors sorted by color value
        val top5Colors = currentFrameColorCounts.entries
            .stream()
            .sorted(Map.Entry.comparingByKey<Int?, Int?>())
            .limit(5)
            .collect(Collectors.toList())

        // Get the previous top 5 colors
        val prevTop5Colors = previousFrameColorCounts.entries
            .stream()
            .sorted(Map.Entry.comparingByKey<Int?, Int?>())
            .limit(5)
            .collect(Collectors.toList())

        // Check if the top 5 colors have changed
        var top5ColorsChanged = false
        if (top5Colors.size != prevTop5Colors.size) {
            top5ColorsChanged = true
        } else {
            for (i in top5Colors.indices) {
                if (i >= prevTop5Colors.size) {
                    top5ColorsChanged = true
                    break
                }
                val current = top5Colors.get(i)
                val previous = prevTop5Colors.get(i)
                if (current.key != previous.key || current.value != previous.value) {
                    top5ColorsChanged = true
                    break
                }
            }
        }

        // Display top 5 colors only if they changed and logging is enabled
        if (top5ColorsChanged && this.isEnablePpuLogging) {
            println("======================")
            println("[PPU] Top 5 colors in buffer (sorted by color):")
            top5Colors.forEach(Consumer { entry: MutableMap.MutableEntry<Int?, Int?>? ->
                println(
                    "[PPU] 0x" + Integer.toHexString(
                        entry!!.key!!
                    ).uppercase(Locale.getDefault()) + " : " + entry.value
                )
            })
            println("Total unique colors: " + currentFrameColorCounts.size)
        }

        // Update previous frame color counts for next comparison
        previousFrameColorCounts.clear()
        previousFrameColorCounts.putAll(currentFrameColorCounts)

        endFrame()


        // Notify image buffer:
        gui!!.getScreenView().imageReady(false)

        // Reset scanline counter:
        lastRenderedScanline = -1

        startFrame()
    }

    fun endScanline() {
        if (scanline < 19 + vblankAdd) {
            // VINT
            // do nothing.
        } else if (scanline == 19 + vblankAdd) {
            // Dummy scanline.
            // May be variable length:

            if (dummyCycleToggle) {
                // Remove dead cycle at end of scanline,
                // for next scanline:

                curX = 1
                dummyCycleToggle = !dummyCycleToggle
            }
        } else if (scanline == 20 + vblankAdd) {
            // Clear VBlank flag:


            setStatusFlag(STATUS_VBLANK, false)

            // Clear Sprite #0 hit flag:
            setStatusFlag(STATUS_SPRITE0HIT, false)
            hitSpr0 = false
            spr0HitX = -1
            spr0HitY = -1

            if (f_bgVisibility == 1 || f_spVisibility == 1) {
                // Update counters:

                cntFV = regFV
                cntV = regV
                cntH = regH
                cntVT = regVT
                cntHT = regHT

                if (f_bgVisibility == 1) {
                    // Render dummy scanline:
                    renderBgScanline(buffer, 0)
                }
            }

            if (f_bgVisibility == 1 && f_spVisibility == 1) {
                // Check sprite 0 hit for first scanline:

                checkSprite0(0)
            }

            if (f_bgVisibility == 1 || f_spVisibility == 1) {
                // Clock mapper IRQ Counter:
                memoryMapper!!.clockIrqCounter()
            }
        } else if (scanline >= 21 + vblankAdd && scanline <= 260) {
            // Render normally:

            if (f_bgVisibility == 1) {
                if (!scanlineAlreadyRendered) {
                    // update scroll:
                    cntHT = regHT
                    cntH = regH
                    renderBgScanline(bgbuffer, scanline + 1 - 21)
                }
                scanlineAlreadyRendered = false

                // Check for sprite 0 (next scanline):
                if (!hitSpr0 && f_spVisibility == 1) {
                    if (sprX[0] >= -7 && sprX[0] < 256 && sprY[0] + 1 <= (scanline - vblankAdd + 1 - 21) && (sprY[0] + 1 + (if (f_spriteSize == 0) 8 else 16)) >= (scanline - vblankAdd + 1 - 21)) {
                        if (checkSprite0(scanline + vblankAdd + 1 - 21)) { /* System.out.println("found spr0. curscan=" + scanline + " hitscan=" + spr0HitY); */
                            hitSpr0 = true
                        }
                    }
                }
            }

            if (f_bgVisibility == 1 || f_spVisibility == 1) {
                // Clock mapper IRQ Counter:
                memoryMapper!!.clockIrqCounter()
            }
        } else if (scanline == 261 + vblankAdd) {
            // Dead scanline, no rendering.
            // Set VINT:

            setStatusFlag(STATUS_VBLANK, true)
            requestEndFrame = true
            nmiCounter = 9

            // Wrap around:
            scanline = -1 // will be incremented to 0
        }

        scanline++
        regsToAddress()
        cntsToAddress()
    }

    fun startFrame() {
        val buffer = gui!!.getScreenView().getBuffer()

        // Set background color:
        var bgColor = 0

        if (f_dispType == 0) {
            // Color display.
            // f_color determines color emphasis.
            // Use first entry of image palette as BG color.

            bgColor = imgPalette[0]
        } else {
            // Monochrome display.
            // f_color determines the bg color.

            when (f_color) {
                0 -> {
                    // Black
                    bgColor = 0x00000
                }

                1 -> {
                    run {
                        // Green
                        bgColor = 0x00FF00
                    }
                    run {
                        // Blue
                        bgColor = 0xFF0000
                    }
                    run {
                        // Invalid. Use black.
                        bgColor = 0x000000
                    }
                    run {
                        // Red
                        bgColor = 0x0000FF
                    }
                    run {
                        // Invalid. Use black.
                        bgColor = 0x0
                    }
                }

                2 -> {
                    run {
                        bgColor = 0xFF0000
                    }
                    run {
                        bgColor = 0x000000
                    }
                    run {
                        bgColor = 0x0000FF
                    }
                    run {
                        bgColor = 0x0
                    }
                }

                3 -> {
                    run {
                        bgColor = 0x000000
                    }
                    run {
                        bgColor = 0x0000FF
                    }
                    run {
                        bgColor = 0x0
                    }
                }

                4 -> {
                    run {
                        bgColor = 0x0000FF
                    }
                    run {
                        bgColor = 0x0
                    }
                }

                else -> {
                    bgColor = 0x0
                }
            }
        }

        for (i in buffer.indices) {
            buffer[i] = bgColor
        }
        for (i in pixrendered.indices) {
            pixrendered[i] = 65
        }
    }

    fun endFrame() {
        val buffer = gui!!.getScreenView().getBuffer()

        // Count colors in the buffer
        currentFrameColorCounts.clear()
        for (pixel in buffer) {
            currentFrameColorCounts.put(pixel, currentFrameColorCounts.getOrDefault(pixel, 0)!! + 1)
        }

        // Draw spr#0 hit coordinates:
        if (showSpr0Hit) {
            // Spr 0 position:
            if (sprX[0] >= 0 && sprX[0] < 256 && sprY[0] >= 0 && sprY[0] < 240) {
                for (i in 0..255) {
                    buffer[(sprY[0] shl 8) + i] = 0xFF5555
                }
                for (i in 0..239) {
                    buffer[(i shl 8) + sprX[0]] = 0xFF5555
                }
            }
            // Hit position:
            if (spr0HitX >= 0 && spr0HitX < 256 && spr0HitY >= 0 && spr0HitY < 240) {
                for (i in 0..255) {
                    buffer[(spr0HitY shl 8) + i] = 0x55FF55
                }
                for (i in 0..239) {
                    buffer[(i shl 8) + spr0HitX] = 0x55FF55
                }
            }
        }

        // This is a bit lazy..
        // if either the sprites or the background should be clipped,
        // both are clipped after rendering is finished.
        if (clipTVcolumn || f_bgClipping == 0 || f_spClipping == 0) {
            // Clip left 8-pixels column:
            for (y in 0..239) {
                for (x in 0..7) {
                    buffer[(y shl 8) + x] = 0
                }
            }
        }

        if (clipTVcolumn) {
            // Clip right 8-pixels column too:
            for (y in 0..239) {
                for (x in 0..7) {
                    buffer[(y shl 8) + 255 - x] = 0
                }
            }
        }

        // Clip top and bottom 8 pixels:
        if (clipTVrow) {
            for (y in 0..7) {
                for (x in 0..255) {
                    buffer[(y shl 8) + x] = 0
                    buffer[((239 - y) shl 8) + x] = 0
                }
            }
        }

        // Show sound buffer:
        if (showSoundBuffer && sourceDataLine != null) {
            bufferSize = sourceDataLine!!.getBufferSize()
            available = sourceDataLine!!.available()
            scale = bufferSize / 256

            for (y in 0..3) {
                scanlineChanged[y] = true
                for (x in 0..255) {
                    if (x >= (available / scale)) {
                        buffer[y * 256 + x] = 0xFFFFFF
                    } else {
                        buffer[y * 256 + x] = 0
                    }
                }
            }
        }
    }

    fun updateControlReg1(value: Int) {
        triggerRendering()

        f_nmiOnVblank = (value shr 7) and 1
        f_spriteSize = (value shr 5) and 1
        f_bgPatternTable = (value shr 4) and 1
        f_spPatternTable = (value shr 3) and 1
        f_addrInc = (value shr 2) and 1
        f_nTblAddress = value and 3

        regV = (value shr 1) and 1
        regH = value and 1
        regS = (value shr 4) and 1
    }

    fun updateControlReg2(value: Int) {
        triggerRendering()

        f_color = (value shr 5) and 7
        f_spVisibility = (value shr 4) and 1
        f_bgVisibility = (value shr 3) and 1
        f_spClipping = (value shr 2) and 1
        f_bgClipping = (value shr 1) and 1
        f_dispType = value and 1

        if (f_dispType == 0) {
            palTable!!.setEmphasis(f_color)
        }
        updatePalettes()
    }

    fun setStatusFlag(flag: Int, value: Boolean) {
        val n = 1 shl flag
        var memValue = cpuMem!!.load(0x2002).toInt()
        memValue = ((memValue and (255 - n)) or (if (value) n else 0))
        cpuMem!!.write(0x2002, memValue.toShort())
    }


    // CPU Register $2002:
    // Read the Status Register.
    fun readStatusRegister(): Short {
        tmp = cpuMem!!.load(0x2002)

        // Reset scroll & VRAM Address toggle:
        firstWrite = true

        // Clear VBlank flag:
        setStatusFlag(STATUS_VBLANK, false)

        // Fetch status data:
        return tmp
    }


    // CPU Register $2003:
    // Write the SPR-RAM address that is used for sramWrite (Register 0x2004 in CPU memory map)
    fun writeSRAMAddress(address: Short) {
        sramAddress = address
    }


    // CPU Register $2004 (R):
    // Read from SPR-RAM (Sprite RAM).
    // The address should be set first.
    fun sramLoad(): Short {
        val tmp = sprMem!!.load(sramAddress.toInt())
        /*sramAddress++; // Increment address
        sramAddress%=0x100;*/
        return tmp
    }


    // CPU Register $2004 (W):
    // Write to SPR-RAM (Sprite RAM).
    // The address should be set first.
    fun sramWrite(value: Short) {
        sprMem!!.write(sramAddress.toInt(), value)
        spriteRamWriteUpdate(sramAddress.toInt(), value)
        sramAddress++ // Increment address
        sramAddress = (sramAddress % 0x100).toShort()
    }


    // CPU Register $2005:
    // Write to scroll registers.
    // The first write is the vertical offset, the second is the
    // horizontal offset:
    fun scrollWrite(value: Short) {
        triggerRendering()
        if (firstWrite) {
            // First write, horizontal scroll:

            regHT = (value.toInt() shr 3) and 31
            regFH = value.toInt() and 7
        } else {
            // Second write, vertical scroll:

            regFV = value.toInt() and 7
            regVT = (value.toInt() shr 3) and 31
        }
        firstWrite = !firstWrite
    }

    // CPU Register $2006:
    // Sets the adress used when reading/writing from/to VRAM.
    // The first write sets the high byte, the second the low byte.
    fun writeVRAMAddress(address: Int) {
        if (firstWrite) {
            regFV = (address shr 4) and 3
            regV = (address shr 3) and 1
            regH = (address shr 2) and 1
            regVT = (regVT and 7) or ((address and 3) shl 3)
        } else {
            triggerRendering()

            regVT = (regVT and 24) or ((address shr 5) and 7)
            regHT = address and 31

            cntFV = regFV
            cntV = regV
            cntH = regH
            cntVT = regVT
            cntHT = regHT

            checkSprite0(scanline - vblankAdd + 1 - 21)
        }

        firstWrite = !firstWrite

        // Invoke mapper latch:
        cntsToAddress()
        if (vramAddress < 0x2000) {
            memoryMapper!!.latchAccess(vramAddress)
        }
    }

    // CPU Register $2007(R):
    // Read from PPU memory. The address should be set first.
    fun vramLoad(): Short {
        cntsToAddress()
        regsToAddress()

        // If address is in range 0x0000-0x3EFF, return buffered values:
        if (vramAddress <= 0x3EFF) {
            val tmp = vramBufferedReadValue

            // Update buffered value:
            if (vramAddress < 0x2000) {
                vramBufferedReadValue = ppuMem!!.load(vramAddress)
            } else {
                vramBufferedReadValue = mirroredLoad(vramAddress)
            }

            // Mapper latch access:
            if (vramAddress < 0x2000) {
                memoryMapper!!.latchAccess(vramAddress)
            }

            // Increment by either 1 or 32, depending on d2 of Control Register 1:
            vramAddress += (if (f_addrInc == 1) 32 else 1)

            cntsFromAddress()
            regsFromAddress()
            return tmp // Return the previous buffered value.
        }

        // No buffering in this mem range. Read normally.
        val tmp = mirroredLoad(vramAddress)

        // Increment by either 1 or 32, depending on d2 of Control Register 1:
        vramAddress += (if (f_addrInc == 1) 32 else 1)

        cntsFromAddress()
        regsFromAddress()

        return tmp
    }

    // CPU Register $2007(W):
    // Write to PPU memory. The address should be set first.
    fun vramWrite(value: Short) {
        triggerRendering()
        cntsToAddress()
        regsToAddress()

        if (vramAddress >= 0x2000) {
            // Mirroring is used.
            mirroredWrite(vramAddress, value)
        } else {
            // Write normally.

            writeMem(vramAddress, value)

            // Invoke mapper latch:
            memoryMapper!!.latchAccess(vramAddress)
        }

        // Increment by either 1 or 32, depending on d2 of Control Register 1:
        vramAddress += (if (f_addrInc == 1) 32 else 1)
        regsFromAddress()
        cntsFromAddress()
    }

    // CPU Register $4014:
    // Write 256 bytes of main memory
    // into Sprite RAM.
    fun sramDMA(value: Short) {
        val baseAddress = value * 0x100
        var data: Short
        for (i in sramAddress..255) {
            data = cpuMem!!.load(baseAddress + i)
            sprMem!!.write(i, data)
            spriteRamWriteUpdate(i, data)
        }

        cpu!!.haltCycles(513)
    }

    // Updates the scroll registers from a new VRAM address.
    private fun regsFromAddress() {
        address = (vramTmpAddress shr 8) and 0xFF
        regFV = (address shr 4) and 7
        regV = (address shr 3) and 1
        regH = (address shr 2) and 1
        regVT = (regVT and 7) or ((address and 3) shl 3)

        address = vramTmpAddress and 0xFF
        regVT = (regVT and 24) or ((address shr 5) and 7)
        regHT = address and 31
    }

    // Updates the scroll registers from a new VRAM address.
    private fun cntsFromAddress() {
        address = (vramAddress shr 8) and 0xFF
        cntFV = (address shr 4) and 3
        cntV = (address shr 3) and 1
        cntH = (address shr 2) and 1
        cntVT = (cntVT and 7) or ((address and 3) shl 3)

        address = vramAddress and 0xFF
        cntVT = (cntVT and 24) or ((address shr 5) and 7)
        cntHT = address and 31
    }

    private fun regsToAddress() {
        b1 = (regFV and 7) shl 4
        b1 = b1 or ((regV and 1) shl 3)
        b1 = b1 or ((regH and 1) shl 2)
        b1 = b1 or ((regVT shr 3) and 3)

        b2 = (regVT and 7) shl 5
        b2 = b2 or (regHT and 31)

        vramTmpAddress = ((b1 shl 8) or b2) and 0x7FFF
    }

    private fun cntsToAddress() {
        b1 = (cntFV and 7) shl 4
        b1 = b1 or ((cntV and 1) shl 3)
        b1 = b1 or ((cntH and 1) shl 2)
        b1 = b1 or ((cntVT shr 3) and 3)

        b2 = (cntVT and 7) shl 5
        b2 = b2 or (cntHT and 31)

        vramAddress = ((b1 shl 8) or b2) and 0x7FFF
    }

    private fun incTileCounter(count: Int) {
        i = count
        while (i != 0) {
            cntHT++
            if (cntHT == 32) {
                cntHT = 0
                cntVT++
                if (cntVT >= 30) {
                    cntH++
                    if (cntH == 2) {
                        cntH = 0
                        cntV++
                        if (cntV == 2) {
                            cntV = 0
                            cntFV++
                            cntFV = cntFV and 0x7
                        }
                    }
                }
            }
            i--
        }
    }

    // Reads from memory, taking into account
    // mirroring/mapping of address ranges.
    private fun mirroredLoad(address: Int): Short {
        return ppuMem!!.load(vramMirrorTable!![address])
    }

    // Writes to memory, taking into account
    // mirroring/mapping of address ranges.
    private fun mirroredWrite(address: Int, value: Short) {
        if (address >= 0x3f00 && address < 0x3f20) {
            // Palette write mirroring.

            if (address == 0x3F00 || address == 0x3F10) {
                writeMem(0x3F00, value)
                writeMem(0x3F10, value)
            } else if (address == 0x3F04 || address == 0x3F14) {
                writeMem(0x3F04, value)
                writeMem(0x3F14, value)
            } else if (address == 0x3F08 || address == 0x3F18) {
                writeMem(0x3F08, value)
                writeMem(0x3F18, value)
            } else if (address == 0x3F0C || address == 0x3F1C) {
                writeMem(0x3F0C, value)
                writeMem(0x3F1C, value)
            } else {
                writeMem(address, value)
            }
        } else {
            // Use lookup table for mirrored address:

            if (address < vramMirrorTable!!.size) {
                writeMem(vramMirrorTable!![address], value)
            } else {
                if (Globals.debug) {
                    //System.out.println("Invalid VRAM address: "+Misc.hex16(address));
                    cpu!!.setCrashed(true)
                }
            }
        }
    }

    fun triggerRendering() {
        if (scanline - vblankAdd >= 21 && scanline - vblankAdd <= 260) {
            // Render sprites, and combine:

            renderFramePartially(buffer, lastRenderedScanline + 1, scanline - vblankAdd - 21 - lastRenderedScanline)

            // Set last rendered scanline:
            lastRenderedScanline = scanline - vblankAdd - 21
        }
    }

    /**
     * Renders a portion of the frame.
     *
     * @param buffer The buffer to render to
     * @param startScan The starting scanline
     * @param scanCount The number of scanlines to render
     */
    private fun renderFramePartially(buffer: IntArray?, startScan: Int, scanCount: Int) {
        // Check if buffer is null to prevent NullPointerException
        // This can happen if the buffer is not set on the PPU before rendering starts
        var scanCount = scanCount
        if (buffer == null) {
            return
        }

        if (f_spVisibility == 1 && !Globals.disableSprites) {
            renderSpritesPartially(startScan, scanCount, true)
        }

        if (f_bgVisibility == 1) {
            si = startScan shl 8
            ei = (startScan + scanCount) shl 8
            if (ei > 0xF000) {
                ei = 0xF000
            }
            destIndex = si
            while (destIndex < ei) {
                if (pixrendered[destIndex] > 0xFF) {
                    buffer[destIndex] = bgbuffer[destIndex]
                }
                destIndex++
            }
        }

        if (f_spVisibility == 1 && !Globals.disableSprites) {
            renderSpritesPartially(startScan, scanCount, false)
        }

        if (this.isNonHWScalingEnabled && !this.isRequestRenderAll) {
            // Check which scanlines have changed, to try to
            // speed up scaling:

            var j: Int
            var jmax: Int
            if (startScan + scanCount > 240) {
                scanCount = 240 - startScan
            }
            for (i in startScan until startScan + scanCount) {
                scanlineChanged[i] = false
                si = i shl 8
                jmax = si + 256
                j = si
                while (j < jmax) {
                    if (buffer[j] != oldFrame[j]) {
                        scanlineChanged[i] = true
                        break
                    }
                    oldFrame[j] = buffer[j]
                    j++
                }
                System.arraycopy(buffer, j, oldFrame, j, jmax - j)
            }
        }

        validTileData = false
    }

    val isNonHWScalingEnabled: Boolean
        get() = gui!!.getScreenView().scalingEnabled() && !gui!!.getScreenView().useHWScaling()

    private fun renderBgScanline(buffer: IntArray, scan: Int) {
        baseTile = (if (regS == 0) 0 else 256)
        destIndex = (scan shl 8) - regFH
        curNt = ntable1[cntV + cntV + cntH]

        cntHT = regHT
        cntH = regH
        curNt = ntable1[cntV + cntV + cntH]

        if (scan < 240 && (scan - cntFV) >= 0) {
            tscanoffset = cntFV shl 3
            y = scan - cntFV
            tile = 0
            while (tile < 32) {
                if (scan >= 0) {
                    // Fetch tile & attrib data:

                    if (validTileData) {
                        // Get data from array:
                        t = scantile!![tile]
                        tpix = t!!.pix
                        att = attrib[tile]
                    } else {
                        // Fetch data:
                        t = ptTile!![baseTile + nameTable[curNt]!!.getTileIndex(cntHT, cntVT)]
                        tpix = t!!.pix
                        att = nameTable[curNt]!!.getAttrib(cntHT, cntVT).toInt()
                        scantile!![tile] = t!!
                        attrib[tile] = att
                    }

                    // Render tile scanline:
                    sx = 0
                    x = (tile shl 3) - regFH
                    if (x > -8) {
                        if (x < 0) {
                            destIndex -= x
                            sx = -x
                        }
                        if (t!!.opaque[cntFV]) {
                            while (sx < 8) {
                                buffer[destIndex] = imgPalette[tpix[tscanoffset + sx] + att]
                                pixrendered[destIndex] = pixrendered[destIndex] or 256
                                destIndex++
                                sx++
                            }
                        } else {
                            while (sx < 8) {
                                col = tpix[tscanoffset + sx]
                                if (col != 0) {
                                    buffer[destIndex] = imgPalette[col + att]
                                    pixrendered[destIndex] = pixrendered[destIndex] or 256
                                }
                                destIndex++
                                sx++
                            }
                        }
                    }
                }

                // Increase Horizontal Tile Counter:
                cntHT++
                if (cntHT == 32) {
                    cntHT = 0
                    cntH++
                    cntH %= 2
                    curNt = ntable1[(cntV shl 1) + cntH]
                }


                tile++
            }

            // Tile data for one row should now have been fetched,
            // so the data in the array is valid.
            validTileData = true
        }

        // update vertical scroll:
        cntFV++
        if (cntFV == 8) {
            cntFV = 0
            cntVT++
            if (cntVT == 30) {
                cntVT = 0
                cntV++
                cntV %= 2
                curNt = ntable1[(cntV shl 1) + cntH]
            } else if (cntVT == 32) {
                cntVT = 0
            }

            // Invalidate fetched data:
            validTileData = false
        }
    }

    private fun renderSpritesPartially(startscan: Int, scancount: Int, bgPri: Boolean) {
        buffer = gui!!.getScreenView().getBuffer()
        if (f_spVisibility == 1) {
            var sprT1: Int
            var sprT2: Int

            for (i in 0..63) {
                if (bgPriority[i] == bgPri && sprX[i] >= 0 && sprX[i] < 256 && sprY[i] + 8 >= startscan && sprY[i] < startscan + scancount) {
                    // Show sprite.
                    if (f_spriteSize == 0) {
                        // 8x8 sprites

                        srcy1 = 0
                        srcy2 = 8

                        if (sprY[i] < startscan) {
                            srcy1 = startscan - sprY[i] - 1
                        }

                        if (sprY[i] + 8 > startscan + scancount) {
                            srcy2 = startscan + scancount - sprY[i] + 1
                        }

                        if (f_spPatternTable == 0) {
                            ptTile!![sprTile[i]].render(
                                0,
                                srcy1,
                                8,
                                srcy2,
                                sprX[i],
                                sprY[i] + 1,
                                buffer,
                                sprCol[i],
                                sprPalette,
                                horiFlip[i],
                                vertFlip[i],
                                i,
                                pixrendered
                            )
                        } else {
                            ptTile!![sprTile[i] + 256].render(
                                0,
                                srcy1,
                                8,
                                srcy2,
                                sprX[i],
                                sprY[i] + 1,
                                buffer,
                                sprCol[i],
                                sprPalette,
                                horiFlip[i],
                                vertFlip[i],
                                i,
                                pixrendered
                            )
                        }
                    } else {
                        // 8x16 sprites
                        var top = sprTile[i]
                        if ((top and 1) != 0) {
                            top = sprTile[i] - 1 + 256
                        }

                        srcy1 = 0
                        srcy2 = 8

                        if (sprY[i] < startscan) {
                            srcy1 = startscan - sprY[i] - 1
                        }

                        if (sprY[i] + 8 > startscan + scancount) {
                            srcy2 = startscan + scancount - sprY[i]
                        }

                        ptTile!![top + (if (vertFlip[i]) 1 else 0)].render(
                            0,
                            srcy1,
                            8,
                            srcy2,
                            sprX[i],
                            sprY[i] + 1,
                            buffer,
                            sprCol[i],
                            sprPalette,
                            horiFlip[i],
                            vertFlip[i],
                            i,
                            pixrendered
                        )

                        srcy1 = 0
                        srcy2 = 8

                        if (sprY[i] + 8 < startscan) {
                            srcy1 = startscan - (sprY[i] + 8 + 1)
                        }

                        if (sprY[i] + 16 > startscan + scancount) {
                            srcy2 = startscan + scancount - (sprY[i] + 8)
                        }

                        ptTile!![top + (if (vertFlip[i]) 0 else 1)].render(
                            0,
                            srcy1,
                            8,
                            srcy2,
                            sprX[i],
                            sprY[i] + 1 + 8,
                            buffer,
                            sprCol[i],
                            sprPalette,
                            horiFlip[i],
                            vertFlip[i],
                            i,
                            pixrendered
                        )
                    }
                }
            }
        }
    }

    private fun checkSprite0(scan: Int): Boolean {
        spr0HitX = -1
        spr0HitY = -1

        var toffset: Int
        val tIndexAdd = (if (f_spPatternTable == 0) 0 else 256)
        var x: Int
        val y: Int
        var bufferIndex: Int
        val col: Int
        val bgPri: Boolean
        val t: Tile

        x = sprX[0]
        y = sprY[0] + 1


        if (f_spriteSize == 0) {
            // 8x8 sprites.

            // Check range:

            if (y <= scan && y + 8 > scan && x >= -7 && x < 256) {
                // Sprite is in range.
                // Draw scanline:

                t = ptTile!![sprTile[0] + tIndexAdd]
                col = sprCol[0]
                bgPri = bgPriority[0]

                if (vertFlip[0]) {
                    toffset = 7 - (scan - y)
                } else {
                    toffset = scan - y
                }
                toffset *= 8

                bufferIndex = scan * 256 + x
                if (horiFlip[0]) {
                    for (i in 7 downTo 0) {
                        if (x >= 0 && x < 256) {
                            if (bufferIndex >= 0 && bufferIndex < 61440 && pixrendered[bufferIndex] != 0) {
                                if (t.pix[toffset + i] != 0) {
                                    spr0HitX = bufferIndex % 256
                                    spr0HitY = scan
                                    return true
                                }
                            }
                        }
                        x++
                        bufferIndex++
                    }
                } else {
                    for (i in 0..7) {
                        if (x >= 0 && x < 256) {
                            if (bufferIndex >= 0 && bufferIndex < 61440 && pixrendered[bufferIndex] != 0) {
                                if (t.pix[toffset + i] != 0) {
                                    spr0HitX = bufferIndex % 256
                                    spr0HitY = scan
                                    return true
                                }
                            }
                        }
                        x++
                        bufferIndex++
                    }
                }
            }
        } else {
            // 8x16 sprites:

            // Check range:

            if (y <= scan && y + 16 > scan && x >= -7 && x < 256) {
                // Sprite is in range.
                // Draw scanline:

                if (vertFlip[0]) {
                    toffset = 15 - (scan - y)
                } else {
                    toffset = scan - y
                }

                if (toffset < 8) {
                    // first half of sprite.
                    t = ptTile!![sprTile[0] + (if (vertFlip[0]) 1 else 0) + (if ((sprTile[0] and 1) != 0) 255 else 0)]
                } else {
                    // second half of sprite.
                    t = ptTile!![sprTile[0] + (if (vertFlip[0]) 0 else 1) + (if ((sprTile[0] and 1) != 0) 255 else 0)]
                    if (vertFlip[0]) {
                        toffset = 15 - toffset
                    } else {
                        toffset -= 8
                    }
                }
                toffset *= 8
                col = sprCol[0]
                bgPri = bgPriority[0]

                bufferIndex = scan * 256 + x
                if (horiFlip[0]) {
                    for (i in 7 downTo 0) {
                        if (x >= 0 && x < 256) {
                            if (bufferIndex >= 0 && bufferIndex < 61440 && pixrendered[bufferIndex] != 0) {
                                if (t.pix[toffset + i] != 0) {
                                    spr0HitX = bufferIndex % 256
                                    spr0HitY = scan
                                    return true
                                }
                            }
                        }
                        x++
                        bufferIndex++
                    }
                } else {
                    for (i in 0..7) {
                        if (x >= 0 && x < 256) {
                            if (bufferIndex >= 0 && bufferIndex < 61440 && pixrendered[bufferIndex] != 0) {
                                if (t.pix[toffset + i] != 0) {
                                    spr0HitX = bufferIndex % 256
                                    spr0HitY = scan
                                    return true
                                }
                            }
                        }
                        x++
                        bufferIndex++
                    }
                }
            }
        }

        return false
    }

    // This will write to PPU memory, and
    // update internally buffered data
    // appropriately.
    private fun writeMem(address: Int, value: Short) {
        ppuMem!!.write(address, value)

        // Update internally buffered data:
        if (address < 0x2000) {
            ppuMem!!.write(address, value)
            patternWrite(address, value)
        } else if (address >= 0x2000 && address < 0x23c0) {
            nameTableWrite(ntable1[0], address - 0x2000, value)
        } else if (address >= 0x23c0 && address < 0x2400) {
            attribTableWrite(ntable1[0], address - 0x23c0, value)
        } else if (address >= 0x2400 && address < 0x27c0) {
            nameTableWrite(ntable1[1], address - 0x2400, value)
        } else if (address >= 0x27c0 && address < 0x2800) {
            attribTableWrite(ntable1[1], address - 0x27c0, value)
        } else if (address >= 0x2800 && address < 0x2bc0) {
            nameTableWrite(ntable1[2], address - 0x2800, value)
        } else if (address >= 0x2bc0 && address < 0x2c00) {
            attribTableWrite(ntable1[2], address - 0x2bc0, value)
        } else if (address >= 0x2c00 && address < 0x2fc0) {
            nameTableWrite(ntable1[3], address - 0x2c00, value)
        } else if (address >= 0x2fc0 && address < 0x3000) {
            attribTableWrite(ntable1[3], address - 0x2fc0, value)
        } else if (address >= 0x3f00 && address < 0x3f20) {
            updatePalettes()
        }
    }

    // Reads data from $3f00 to $f20
    // into the two buffered palettes.
    fun updatePalettes() {
        for (i in 0..15) {
            if (f_dispType == 0) {
                imgPalette[i] = palTable!!.getEntry(ppuMem!!.load(0x3f00 + i).toInt() and 63)
            } else {
                imgPalette[i] = palTable!!.getEntry(ppuMem!!.load(0x3f00 + i).toInt() and 32)
            }
        }
        for (i in 0..15) {
            if (f_dispType == 0) {
                sprPalette[i] = palTable!!.getEntry(ppuMem!!.load(0x3f10 + i).toInt() and 63)
            } else {
                sprPalette[i] = palTable!!.getEntry(ppuMem!!.load(0x3f10 + i).toInt() and 32)
            }
        }

        //renderPalettes();
    }


    // Updates the internal pattern
    // table buffers with this new byte.
    fun patternWrite(address: Int, value: Short) {
        val tileIndex = address / 16
        val leftOver = address % 16
        if (leftOver < 8) {
            ptTile!![tileIndex].setScanline(leftOver, value, ppuMem!!.load(address + 8))
        } else {
            ptTile!![tileIndex].setScanline(leftOver - 8, ppuMem!!.load(address - 8), value)
        }
    }

    fun patternWrite(address: Int, value: ShortArray, offset: Int, length: Int) {
        var tileIndex: Int
        var leftOver: Int

        for (i in 0 until length) {
            tileIndex = (address + i) shr 4
            leftOver = (address + i) % 16

            if (leftOver < 8) {
                ptTile!![tileIndex].setScanline(leftOver, value[offset + i], ppuMem!!.load(address + 8 + i))
            } else {
                ptTile!![tileIndex].setScanline(leftOver - 8, ppuMem!!.load(address - 8 + i), value[offset + i])
            }
        }
    }

    fun invalidateFrameCache() {
        // Clear the no-update scanline buffer:

        for (i in 0..239) {
            scanlineChanged[i] = true
        }
        Arrays.fill(oldFrame, -1)
        this.isRequestRenderAll = true
    }

    // Updates the internal name table buffers
    // with this new byte.
    fun nameTableWrite(index: Int, address: Int, value: Short) {
        nameTable[index]!!.writeTileIndex(address, value.toInt())

        // Update Sprite #0 hit:
        //updateSpr0Hit();
        checkSprite0(scanline + 1 - vblankAdd - 21)
    }

    // Updates the internal pattern
    // table buffers with this new attribute
    // table byte.
    fun attribTableWrite(index: Int, address: Int, value: Short) {
        nameTable[index]!!.writeAttrib(address, value.toInt())
    }

    // Updates the internally buffered sprite
    // data with this new byte of info.
    fun spriteRamWriteUpdate(address: Int, value: Short) {
        val tIndex = address / 4

        if (tIndex == 0) {
            //updateSpr0Hit();
            checkSprite0(scanline + 1 - vblankAdd - 21)
        }

        if (address % 4 == 0) {
            // Y coordinate

            sprY[tIndex] = value.toInt()
        } else if (address % 4 == 1) {
            // Tile index

            sprTile[tIndex] = value.toInt()
        } else if (address % 4 == 2) {
            // Attributes

            vertFlip[tIndex] = ((value.toInt() and 0x80) != 0)
            horiFlip[tIndex] = ((value.toInt() and 0x40) != 0)
            bgPriority[tIndex] = ((value.toInt() and 0x20) != 0)
            sprCol[tIndex] = (value.toInt() and 3) shl 2
        } else if (address % 4 == 3) {
            // X coordinate

            sprX[tIndex] = value.toInt()
        }
    }

    fun doNMI() {
        // Set VBlank flag:

        setStatusFlag(STATUS_VBLANK, true)
        //nes.getCpu().doNonMaskableInterrupt();
        cpu!!.requestIrq(CPU.Companion.IRQ_NMI)
    }

    fun statusRegsToInt(): Int {
        var ret = 0
        ret = (f_nmiOnVblank) or
                (f_spriteSize shl 1) or
                (f_bgPatternTable shl 2) or
                (f_spPatternTable shl 3) or
                (f_addrInc shl 4) or
                (f_nTblAddress shl 5) or
                (f_color shl 6) or
                (f_spVisibility shl 7) or
                (f_bgVisibility shl 8) or
                (f_spClipping shl 9) or
                (f_bgClipping shl 10) or
                (f_dispType shl 11)

        return ret
    }

    fun statusRegsFromInt(n: Int) {
        f_nmiOnVblank = (n) and 0x1
        f_spriteSize = (n shr 1) and 0x1
        f_bgPatternTable = (n shr 2) and 0x1
        f_spPatternTable = (n shr 3) and 0x1
        f_addrInc = (n shr 4) and 0x1
        f_nTblAddress = (n shr 5) and 0x1

        f_color = (n shr 6) and 0x1
        f_spVisibility = (n shr 7) and 0x1
        f_bgVisibility = (n shr 8) and 0x1
        f_spClipping = (n shr 9) and 0x1
        f_bgClipping = (n shr 10) and 0x1
        f_dispType = (n shr 11) and 0x1
    }

    fun stateLoad(buf: ByteBuffer) {
        // Check version:

        if (buf.readByte().toInt() == 1) {
            // Counters:

            cntFV = buf.readInt()
            cntV = buf.readInt()
            cntH = buf.readInt()
            cntVT = buf.readInt()
            cntHT = buf.readInt()


            // Registers:
            regFV = buf.readInt()
            regV = buf.readInt()
            regH = buf.readInt()
            regVT = buf.readInt()
            regHT = buf.readInt()
            regFH = buf.readInt()
            regS = buf.readInt()


            // VRAM address:
            vramAddress = buf.readInt()
            vramTmpAddress = buf.readInt()


            // Control/Status registers:
            statusRegsFromInt(buf.readInt())


            // VRAM I/O:
            vramBufferedReadValue = buf.readInt().toShort()
            firstWrite = buf.readBoolean()


            //System.out.println("firstWrite: "+firstWrite);


            // Mirroring:
            //currentMirroring = -1;
            //setMirroring(buf.readInt());
            for (i in vramMirrorTable!!.indices) {
                vramMirrorTable!![i] = buf.readInt()
            }


            // SPR-RAM I/O:
            sramAddress = buf.readInt().toShort()

            // Rendering progression:
            curX = buf.readInt()
            scanline = buf.readInt()
            lastRenderedScanline = buf.readInt()


            // Misc:
            requestEndFrame = buf.readBoolean()
            nmiOk = buf.readBoolean()
            dummyCycleToggle = buf.readBoolean()
            nmiCounter = buf.readInt()
            tmp = buf.readInt().toShort()


            // Stuff used during rendering:
            for (i in bgbuffer.indices) {
                bgbuffer[i] = buf.readByte().toInt()
            }
            for (i in pixrendered.indices) {
                pixrendered[i] = buf.readByte().toInt()
            }

            // Name tables:
            for (i in 0..3) {
                ntable1[i] = buf.readByte().toInt()
                nameTable[i]!!.stateLoad(buf)
            }

            // Pattern data:
            for (i in ptTile!!.indices) {
                ptTile!![i].stateLoad(buf)
            }

            // Update internally stored stuff from VRAM memory:
            /*short[] mem = ppuMem.mem;

            // Palettes:
            for(int i=0x3f00;i<0x3f20;i++){
            writeMem(i,mem[i]);
            }
             */
            // Sprite data:
            val sprmem = sprMem!!.mem
            for (i in sprmem!!.indices) {
                spriteRamWriteUpdate(i, sprmem[i])
            }
        }
    }

    fun stateSave(buf: ByteBuffer) {
        // Version:


        buf.putByte(1.toShort())


        // Counters:
        buf.putInt(cntFV)
        buf.putInt(cntV)
        buf.putInt(cntH)
        buf.putInt(cntVT)
        buf.putInt(cntHT)


        // Registers:
        buf.putInt(regFV)
        buf.putInt(regV)
        buf.putInt(regH)
        buf.putInt(regVT)
        buf.putInt(regHT)
        buf.putInt(regFH)
        buf.putInt(regS)


        // VRAM address:
        buf.putInt(vramAddress)
        buf.putInt(vramTmpAddress)


        // Control/Status registers:
        buf.putInt(statusRegsToInt())


        // VRAM I/O:
        buf.putInt(vramBufferedReadValue.toInt())
        //System.out.println("firstWrite: "+firstWrite);
        buf.putBoolean(firstWrite)

        // Mirroring:
        //buf.putInt(currentMirroring);
        for (i in vramMirrorTable!!.indices) {
            buf.putInt(vramMirrorTable!![i])
        }


        // SPR-RAM I/O:
        buf.putInt(sramAddress.toInt())


        // Rendering progression:
        buf.putInt(curX)
        buf.putInt(scanline)
        buf.putInt(lastRenderedScanline)


        // Misc:
        buf.putBoolean(requestEndFrame)
        buf.putBoolean(nmiOk)
        buf.putBoolean(dummyCycleToggle)
        buf.putInt(nmiCounter)
        buf.putInt(tmp.toInt())


        // Stuff used during rendering:
        for (i in bgbuffer.indices) {
            buf.putByte(bgbuffer[i].toShort())
        }
        for (i in pixrendered.indices) {
            buf.putByte(pixrendered[i].toShort())
        }

        // Name tables:
        for (i in 0..3) {
            buf.putByte(ntable1[i].toShort())
            nameTable[i]!!.stateSave(buf)
        }

        // Pattern data:
        for (i in ptTile!!.indices) {
            ptTile!![i].stateSave(buf)
        }
    }

    // Reset PPU:
    fun reset() {
        ppuMem!!.reset()
        sprMem!!.reset()

        vramBufferedReadValue = 0
        sramAddress = 0
        curX = 0
        scanline = 0
        lastRenderedScanline = 0
        spr0HitX = 0
        spr0HitY = 0
        mapperIrqCounter = 0

        currentMirroring = -1

        firstWrite = true
        requestEndFrame = false
        nmiOk = false
        hitSpr0 = false
        dummyCycleToggle = false
        validTileData = false
        nmiCounter = 0
        tmp = 0
        att = 0
        i = 0

        // Control Flags Register 1:
        f_nmiOnVblank = 0 // NMI on VBlank. 0=disable, 1=enable
        f_spriteSize = 0 // Sprite size. 0=8x8, 1=8x16
        f_bgPatternTable = 0 // Background Pattern Table address. 0=0x0000,1=0x1000
        f_spPatternTable = 0 // Sprite Pattern Table address. 0=0x0000,1=0x1000
        f_addrInc = 0 // PPU Address Increment. 0=1,1=32
        f_nTblAddress = 0 // Name Table Address. 0=0x2000,1=0x2400,2=0x2800,3=0x2C00

        // Control Flags Register 2:
        f_color = 0 // Background color. 0=black, 1=blue, 2=green, 4=red
        f_spVisibility = 0 // Sprite visibility. 0=not displayed,1=displayed
        f_bgVisibility = 0 // Background visibility. 0=Not Displayed,1=displayed
        f_spClipping = 0 // Sprite clipping. 0=Sprites invisible in left 8-pixel column,1=No clipping
        f_bgClipping = 0 // Background clipping. 0=BG invisible in left 8-pixel column, 1=No clipping
        f_dispType = 0 // Display type. 0=color, 1=monochrome


        // Counters:
        cntFV = 0
        cntV = 0
        cntH = 0
        cntVT = 0
        cntHT = 0

        // Registers:
        regFV = 0
        regV = 0
        regH = 0
        regVT = 0
        regHT = 0
        regFH = 0
        regS = 0

        Arrays.fill(scanlineChanged, true)
        Arrays.fill(oldFrame, -1)

        // Initialize stuff:
        init(
            gui!!,
            ppuMem,
            sprMem,
            cpuMem!!,
            cpu!!,
            memoryMapper,
            sourceDataLine,
            palTable!!
        )
    }

    fun destroy() {
        ppuMem = null
        sprMem = null
        scantile = null
    }

    fun setMapper(memMapper: MemoryMapper) {
        this.memoryMapper = memMapper
    }
}