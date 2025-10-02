/*
 *
 *  * Copyright (C) 2025 Artur Skowroński
 *  * This file is part of kNES, a fork of vNES (GPLv3) rewritten in Kotlin.
 *  *
 *  * vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license.
 *  * This project is a reimplementation and extension of that work.
 *  *
 *  * kNES is licensed under the GNU General Public License v3.0.
 *  * See the LICENSE file for more details.
 *
 */

package knes.emulator

class Tile {
    // Tile data:
    @JvmField
    var pix: IntArray = IntArray(64)
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

    fun setScanline(sline: Int, b1: Short, b2: Short) {
        initialized = true
        tIndex = sline shl 3
        for (x in 0..7) {
            pix[tIndex + x] = ((b1.toInt() shr (7 - x)) and 1) + (((b2.toInt() shr (7 - x)) and 1) shl 1)
            if (pix[tIndex + x] == 0) {
                opaque[sline] = false
            }
        }
    }

    fun render(
        srcx1_in: Int,
        srcy1_in: Int,
        srcx2_in: Int,
        srcy2_in: Int,
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
        var srcx1 = srcx1_in
        var srcy1 = srcy1_in
        var srcx2 = srcx2_in
        var srcy2 = srcy2_in
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
            for (y in 0..7) {
                for (x in 0..7) {
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
                }
                fbIndex -= 8
                fbIndex += 256
            }
        } else if (flipHorizontal && !flipVertical) {
            fbIndex = (dy shl 8) + dx
            tIndex = 7
            for (y in 0..7) {
                for (x in 0..7) {
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
                }
                fbIndex -= 8
                fbIndex += 256
                tIndex += 16
            }
        } else if (flipVertical && !flipHorizontal) {
            fbIndex = (dy shl 8) + dx
            tIndex = 56
            for (y in 0..7) {
                for (x in 0..7) {
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
                }
                fbIndex -= 8
                fbIndex += 256
                tIndex -= 16
            }
        } else {
            fbIndex = (dy shl 8) + dx
            tIndex = 63
            for (y in 0..7) {
                for (x in 7 downTo 0) {
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
                }
                fbIndex -= 8
                fbIndex += 256
            }
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
