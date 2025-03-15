package vnes.emulator

import vnes.emulator.utils.Misc
import java.io.File
import java.io.FileWriter

class Tile {
    // Tile data:
    @JvmField
    var pix: IntArray
    var fbIndex: Int = 0
    var tIndex: Int = 0
    var x: Int = 0
    var y: Int = 0
    var w: Int = 0
    var h: Int = 0
    var incX: Int = 0
    var incY: Int = 0
    var palIndex: Int = 0
    var tpri: Int = 0
    var c: Int = 0
    var initialized: Boolean = false
    @JvmField
    var opaque: BooleanArray = BooleanArray(8)

    init {
        pix = IntArray(64)
    }

    fun setBuffer(scanline: ShortArray) {
        y = 0
        while (y < 8) {
            setScanline(y, scanline[y], scanline[y + 8])
            y++
        }
    }

    fun setScanline(sline: Int, b1: Short, b2: Short) {
        initialized = true
        tIndex = sline shl 3
        x = 0
        while (x < 8) {
            pix[tIndex + x] = ((b1.toInt() shr (7 - x)) and 1) + (((b2.toInt() shr (7 - x)) and 1) shl 1)
            if (pix[tIndex + x] == 0) {
                opaque[sline] = false
            }
            x++
        }
    }

    fun renderSimple(dx: Int, dy: Int, fBuffer: IntArray, palAdd: Int, palette: IntArray) {
        tIndex = 0
        fbIndex = (dy shl 8) + dx
        y = 8
        while (y != 0) {
            x = 8
            while (x != 0) {
                palIndex = pix[tIndex]
                if (palIndex != 0) {
                    fBuffer[fbIndex] = palette[palIndex + palAdd]
                }
                fbIndex++
                tIndex++
                x--
            }
            fbIndex -= 8
            fbIndex += 256
            y--
        }
    }

    fun renderSmall(dx: Int, dy: Int, buffer: IntArray, palAdd: Int, palette: IntArray) {
        tIndex = 0
        fbIndex = (dy shl 8) + dx
        y = 0
        while (y < 4) {
            x = 0
            while (x < 4) {
                c = (palette[pix[tIndex] + palAdd] shr 2) and 0x003F3F3F
                c += (palette[pix[tIndex + 1] + palAdd] shr 2) and 0x003F3F3F
                c += (palette[pix[tIndex + 8] + palAdd] shr 2) and 0x003F3F3F
                c += (palette[pix[tIndex + 9] + palAdd] shr 2) and 0x003F3F3F
                buffer[fbIndex] = c
                fbIndex++
                tIndex += 2
                x++
            }
            tIndex += 8
            fbIndex += 252
            y++
        }
    }

    fun render(
        srcx1: Int,
        srcy1: Int,
        srcx2: Int,
        srcy2: Int,
        dx: Int,
        dy: Int,
        fBuffer: IntArray,
        palAdd: Int,
        palette: IntArray,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        pri: Int,
        priTable: IntArray
    ) {
        var srcx1 = srcx1
        var srcy1 = srcy1
        var srcx2 = srcx2
        var srcy2 = srcy2
        if (dx < -7 || dx >= 256 || dy < -7 || dy >= 240) {
            return
        }

        w = srcx2 - srcx1
        h = srcy2 - srcy1

        if (dx < 0) {
            srcx1 -= dx
        }
        if (dx + srcx2 >= 256) {
            srcx2 = 256 - dx
        }

        if (dy < 0) {
            srcy1 -= dy
        }
        if (dy + srcy2 >= 240) {
            srcy2 = 240 - dy
        }

        if (!flipHorizontal && !flipVertical) {
            fbIndex = (dy shl 8) + dx
            tIndex = 0
            y = 0
            while (y < 8) {
                x = 0
                while (x < 8) {
                    if (x >= srcx1 && x < srcx2 && y >= srcy1 && y < srcy2) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xFF)) {
                            fBuffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xF00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex++
                    x++
                }
                fbIndex -= 8
                fbIndex += 256
                y++
            }
        } else if (flipHorizontal && !flipVertical) {
            fbIndex = (dy shl 8) + dx
            tIndex = 7
            y = 0
            while (y < 8) {
                x = 0
                while (x < 8) {
                    if (x >= srcx1 && x < srcx2 && y >= srcy1 && y < srcy2) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xFF)) {
                            fBuffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xF00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex--
                    x++
                }
                fbIndex -= 8
                fbIndex += 256
                tIndex += 16
                y++
            }
        } else if (flipVertical && !flipHorizontal) {
            fbIndex = (dy shl 8) + dx
            tIndex = 56
            y = 0
            while (y < 8) {
                x = 0
                while (x < 8) {
                    if (x >= srcx1 && x < srcx2 && y >= srcy1 && y < srcy2) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xFF)) {
                            fBuffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xF00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex++
                    x++
                }
                fbIndex -= 8
                fbIndex += 256
                tIndex -= 16
                y++
            }
        } else {
            fbIndex = (dy shl 8) + dx
            tIndex = 63
            y = 0
            while (y < 8) {
                x = 0
                while (x < 8) {
                    if (x >= srcx1 && x < srcx2 && y >= srcy1 && y < srcy2) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xFF)) {
                            fBuffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xF00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex--
                    x++
                }
                fbIndex -= 8
                fbIndex += 256
                y++
            }
        }
    }

    fun isTransparent(x: Int, y: Int): Boolean {
        return (pix[(y shl 3) + x] == 0)
    }

    fun dumpData(file: String) {
        try {
            val f = File(file)
            val fWriter = FileWriter(f)

            for (y in 0..7) {
                for (x in 0..7) {
                    fWriter.write(Misc.hex8(pix[(y shl 3) + x]).substring(1))
                }
                fWriter.write("\r\n")
            }

            fWriter.close()

            //System.out.println("Tile data dumped to file "+file);
        } catch (e: Exception) {
            //System.out.println("Unable to dump tile to file.");
            e.printStackTrace()
        }
    }

    fun stateSave(buf: ByteBuffer) {
        buf.putBoolean(initialized)
        for (i in 0..7) {
            buf.putBoolean(opaque[i])
        }
        for (i in 0..63) {
            buf.putByte(pix[i].toByte().toShort())
        }
    }

    fun stateLoad(buf: ByteBuffer) {
        initialized = buf.readBoolean()
        for (i in 0..7) {
            opaque[i] = buf.readBoolean()
        }
        for (i in 0..63) {
            pix[i] = buf.readByte().toInt()
        }
    }
}