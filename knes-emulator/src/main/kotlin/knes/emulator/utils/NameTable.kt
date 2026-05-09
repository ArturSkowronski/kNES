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

package knes.emulator.utils

import knes.emulator.ByteBuffer

class NameTable(var width: Int, var height: Int, var name: String?) {
    var tile: ShortArray
    var attrib: ShortArray

    init {
        tile = ShortArray(width * height)
        attrib = ShortArray(width * height)
    }

    fun getTileIndex(x: Int, y: Int): Short {
        return tile[y * width + x]
    }

    fun getAttrib(x: Int, y: Int): Short {
        return attrib[y * width + x]
    }

    fun writeTileIndex(index: Int, value: Int) {
        tile[index] = value.toShort()
    }

    fun writeAttrib(index: Int, value: Int) {
        var basex: Int
        var basey: Int
        var add: Int
        var tx: Int
        var ty: Int
        var attindex: Int
        basex = index % 8
        basey = index / 8
        basex *= 4
        basey *= 4

        for (sqy in 0..1) {
            for (sqx in 0..1) {
                add = (value shr (2 * (sqy * 2 + sqx))) and 3
                for (y in 0..1) {
                    for (x in 0..1) {
                        tx = basex + sqx * 2 + x
                        ty = basey + sqy * 2 + y
                        attindex = ty * width + tx
                        attrib[ty * width + tx] = ((add shl 2) and 12).toShort()
                    }
                }
            }
        }
    }

    fun stateSave(buf: ByteBuffer) {
        // V5.46.3 (2026-05-09): the per-tile write was previously gated by
        // `if (tile[i] > 255)` — a leftover debug-print guard that turned into
        // the body of the loop, so we wrote 0 bytes per nametable for any tile
        // index that fit in a byte (i.e., effectively all of them). stateLoad
        // unconditionally reads `width*height` bytes, so each load consumed
        // bytes from the NEXT field, cascading corruption through the rest of
        // the PPU snapshot. Manifested as: RAM restores fine but PPU draws
        // overworld tiles when the save was taken inside a town/shop overlay.
        for (i in 0 until width * height) {
            buf.putByte(tile[i].toByte().toShort())
        }
        for (i in 0 until width * height) {
            buf.putByte(attrib[i].toByte().toShort())
        }
    }

    fun stateLoad(buf: ByteBuffer) {
        for (i in 0 until width * height) {
            tile[i] = buf.readByte()
        }
        for (i in 0 until width * height) {
            attrib[i] = buf.readByte()
        }
    }
}