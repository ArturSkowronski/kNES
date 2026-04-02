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

package knes.emulator.ppu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.Tile
import knes.emulator.utils.NameTable

class SupportingClassesTest : FunSpec({

    // =========================================================================
    // NameTable tests
    // =========================================================================

    context("NameTable") {

        test("writeTileIndex and getTileIndex round-trip: single cell") {
            val nt = NameTable(32, 30, "test")
            nt.writeTileIndex(5, 0xAB)
            nt.tile[5] shouldBe 0xAB.toShort()
        }

        test("writeTileIndex and getTileIndex round-trip via getTileIndex") {
            val nt = NameTable(32, 30, "test")
            // index 0 corresponds to (x=0, y=0)
            nt.writeTileIndex(0, 0x7F)
            nt.getTileIndex(0, 0) shouldBe 0x7F.toShort()
        }

        test("writeTileIndex round-trip for multiple indices") {
            val nt = NameTable(32, 30, "test")
            for (i in 0 until 32 * 30) {
                nt.writeTileIndex(i, i % 256)
            }
            for (i in 0 until 32 * 30) {
                nt.tile[i] shouldBe (i % 256).toShort()
            }
        }

        test("getTileIndex uses row-major layout") {
            val nt = NameTable(32, 30, "test")
            // index for (x=3, y=2) = 2*32+3 = 67
            nt.writeTileIndex(67, 0x55)
            nt.getTileIndex(3, 2) shouldBe 0x55.toShort()
        }

        test("writeAttrib all-zero byte fills 4x4 block with palette index 0") {
            val nt = NameTable(32, 32, "test")
            // attribute index 0 → basex=0, basey=0
            nt.writeAttrib(0, 0x00)
            for (y in 0..3) {
                for (x in 0..3) {
                    nt.getAttrib(x, y) shouldBe 0.toShort()
                }
            }
        }

        test("writeAttrib all-FF byte fills each 2x2 quadrant with maximum palette index 12") {
            // 0xFF bits 0-1=3 → add=3 → (3 shl 2) and 12 = 12
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0xFF)
            for (y in 0..3) {
                for (x in 0..3) {
                    nt.getAttrib(x, y) shouldBe 12.toShort()
                }
            }
        }

        test("writeAttrib decodes top-left quadrant (bits 0-1)") {
            // bits 0-1 = 0b01 = 1 → add=1 → (1 shl 2) and 12 = 4
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0b00000001)
            // top-left 2x2 cells: (0,0),(1,0),(0,1),(1,1)
            nt.getAttrib(0, 0) shouldBe 4.toShort()
            nt.getAttrib(1, 0) shouldBe 4.toShort()
            nt.getAttrib(0, 1) shouldBe 4.toShort()
            nt.getAttrib(1, 1) shouldBe 4.toShort()
            // Other quadrants should be 0
            nt.getAttrib(2, 0) shouldBe 0.toShort()
            nt.getAttrib(0, 2) shouldBe 0.toShort()
        }

        test("writeAttrib decodes top-right quadrant (bits 2-3)") {
            // bits 2-3 = 0b01 = 1 → add=1 → 4
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0b00000100)
            // top-right 2x2 cells: (2,0),(3,0),(2,1),(3,1)
            nt.getAttrib(2, 0) shouldBe 4.toShort()
            nt.getAttrib(3, 0) shouldBe 4.toShort()
            nt.getAttrib(2, 1) shouldBe 4.toShort()
            nt.getAttrib(3, 1) shouldBe 4.toShort()
            // Other quadrants should be 0
            nt.getAttrib(0, 0) shouldBe 0.toShort()
            nt.getAttrib(0, 2) shouldBe 0.toShort()
        }

        test("writeAttrib decodes bottom-left quadrant (bits 4-5)") {
            // bits 4-5 = 0b01 = 1 → add=1 → 4
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0b00010000)
            // bottom-left 2x2 cells: (0,2),(1,2),(0,3),(1,3)
            nt.getAttrib(0, 2) shouldBe 4.toShort()
            nt.getAttrib(1, 2) shouldBe 4.toShort()
            nt.getAttrib(0, 3) shouldBe 4.toShort()
            nt.getAttrib(1, 3) shouldBe 4.toShort()
            // Other quadrants should be 0
            nt.getAttrib(0, 0) shouldBe 0.toShort()
            nt.getAttrib(2, 2) shouldBe 0.toShort()
        }

        test("writeAttrib decodes bottom-right quadrant (bits 6-7)") {
            // bits 6-7 = 0b01 = 1 → add=1 → 4
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0b01000000)
            // bottom-right 2x2 cells: (2,2),(3,2),(2,3),(3,3)
            nt.getAttrib(2, 2) shouldBe 4.toShort()
            nt.getAttrib(3, 2) shouldBe 4.toShort()
            nt.getAttrib(2, 3) shouldBe 4.toShort()
            nt.getAttrib(3, 3) shouldBe 4.toShort()
            // Other quadrants should be 0
            nt.getAttrib(0, 0) shouldBe 0.toShort()
            nt.getAttrib(0, 2) shouldBe 0.toShort()
        }

        test("writeAttrib palette index mapping: add=2 yields 8") {
            // bits 0-1 = 0b10 = 2 → (2 shl 2) and 12 = 8
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0b00000010)
            nt.getAttrib(0, 0) shouldBe 8.toShort()
        }

        test("writeAttrib palette index mapping: add=3 yields 12") {
            // bits 0-1 = 0b11 = 3 → (3 shl 2) and 12 = 12
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0b00000011)
            nt.getAttrib(0, 0) shouldBe 12.toShort()
        }

        test("writeAttrib for second attribute block (index 1) targets correct base position") {
            // index=1 → basex = (1%8)*4 = 4, basey = (1/8)*4 = 0
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(1, 0b00000001) // top-left 2x2 in that block gets add=1 → 4
            nt.getAttrib(4, 0) shouldBe 4.toShort()
            nt.getAttrib(5, 0) shouldBe 4.toShort()
            nt.getAttrib(4, 1) shouldBe 4.toShort()
            nt.getAttrib(5, 1) shouldBe 4.toShort()
            // First block should be untouched
            nt.getAttrib(0, 0) shouldBe 0.toShort()
        }

        test("writeAttrib independent quadrants with distinct palette values") {
            // bits 0-1=1, bits 2-3=2, bits 4-5=3, bits 6-7=0
            // Value = 0b00_11_10_01 = 0b00111001 = 0x39
            val nt = NameTable(32, 32, "test")
            nt.writeAttrib(0, 0b00111001)
            // top-left: add=1 → 4
            nt.getAttrib(0, 0) shouldBe 4.toShort()
            // top-right: add=2 → 8
            nt.getAttrib(2, 0) shouldBe 8.toShort()
            // bottom-left: add=3 → 12
            nt.getAttrib(0, 2) shouldBe 12.toShort()
            // bottom-right: add=0 → 0
            nt.getAttrib(2, 2) shouldBe 0.toShort()
        }
    }

    // =========================================================================
    // Tile tests
    // =========================================================================

    context("Tile") {

        test("setScanline sets initialized to true") {
            val tile = Tile()
            tile.initialized shouldBe false
            tile.setScanline(0, 0x00.toShort(), 0x00.toShort())
            tile.initialized shouldBe true
        }

        test("setScanline with all-zero bytes produces all-zero pixels") {
            val tile = Tile()
            tile.setScanline(0, 0x00.toShort(), 0x00.toShort())
            for (x in 0..7) {
                tile.pix[x] shouldBe 0
            }
        }

        test("setScanline pixel formula: pix = b1_bit | (b2_bit shl 1)") {
            // b1=0xFF (all bits set), b2=0x00 → each pixel = 1 (bit from b1 only)
            val tile = Tile()
            tile.setScanline(0, 0xFF.toShort(), 0x00.toShort())
            for (x in 0..7) {
                tile.pix[x] shouldBe 1
            }
        }

        test("setScanline b1=0 b2=0xFF produces pixel value 2 for all pixels") {
            // b1=0x00, b2=0xFF (all bits set) → each pixel = (0) + (1 shl 1) = 2
            val tile = Tile()
            tile.setScanline(0, 0x00.toShort(), 0xFF.toShort())
            for (x in 0..7) {
                tile.pix[x] shouldBe 2
            }
        }

        test("setScanline b1=0xFF b2=0xFF produces pixel value 3 for all pixels") {
            // both bits set → pixel = 1 + 2 = 3
            val tile = Tile()
            tile.setScanline(0, 0xFF.toShort(), 0xFF.toShort())
            for (x in 0..7) {
                tile.pix[x] shouldBe 3
            }
        }

        test("setScanline decodes bit order: MSB is pixel 0") {
            // b1=0b10000000 (bit 7 set), b2=0x00 → pixel 0 = 1, rest = 0
            val tile = Tile()
            tile.setScanline(0, 0b10000000.toShort(), 0x00.toShort())
            tile.pix[0] shouldBe 1
            for (x in 1..7) {
                tile.pix[x] shouldBe 0
            }
        }

        test("setScanline decodes bit order: LSB is pixel 7") {
            // b1=0b00000001 (bit 0 set), b2=0x00 → pixel 7 = 1, rest = 0
            val tile = Tile()
            tile.setScanline(0, 0b00000001.toShort(), 0x00.toShort())
            tile.pix[7] shouldBe 1
            for (x in 0..6) {
                tile.pix[x] shouldBe 0
            }
        }

        test("setScanline known pattern: alternating bits") {
            // b1=0b10101010=0xAA, b2=0x00 → pixels: 1,0,1,0,1,0,1,0
            val tile = Tile()
            tile.setScanline(0, 0xAA.toShort(), 0x00.toShort())
            tile.pix[0] shouldBe 1
            tile.pix[1] shouldBe 0
            tile.pix[2] shouldBe 1
            tile.pix[3] shouldBe 0
            tile.pix[4] shouldBe 1
            tile.pix[5] shouldBe 0
            tile.pix[6] shouldBe 1
            tile.pix[7] shouldBe 0
        }

        test("setScanline known pattern: b1 and b2 combine correctly") {
            // b1=0b10100000=0xA0, b2=0b11000000=0xC0
            // pixel 0: b1_bit=1, b2_bit=1 → 1 + 2 = 3
            // pixel 1: b1_bit=0, b2_bit=1 → 0 + 2 = 2
            // pixel 2: b1_bit=1, b2_bit=0 → 1 + 0 = 1
            // pixel 3: b1_bit=0, b2_bit=0 → 0
            // pixels 4-7: 0
            val tile = Tile()
            tile.setScanline(0, 0xA0.toShort(), 0xC0.toShort())
            tile.pix[0] shouldBe 3
            tile.pix[1] shouldBe 2
            tile.pix[2] shouldBe 1
            tile.pix[3] shouldBe 0
            tile.pix[4] shouldBe 0
            tile.pix[5] shouldBe 0
            tile.pix[6] shouldBe 0
            tile.pix[7] shouldBe 0
        }

        test("setScanline writes to correct row offset in pix array") {
            // scanline 3 → tIndex = 3*8 = 24
            val tile = Tile()
            tile.setScanline(3, 0xFF.toShort(), 0x00.toShort())
            // pixels at indices 24..31 should all be 1
            for (x in 24..31) {
                tile.pix[x] shouldBe 1
            }
            // pixels before row 3 should still be 0
            for (x in 0..23) {
                tile.pix[x] shouldBe 0
            }
        }

        test("setScanline multiple scanlines populate independent rows") {
            val tile = Tile()
            tile.setScanline(0, 0xFF.toShort(), 0x00.toShort()) // row 0 → all 1
            tile.setScanline(1, 0x00.toShort(), 0xFF.toShort()) // row 1 → all 2
            tile.setScanline(2, 0xFF.toShort(), 0xFF.toShort()) // row 2 → all 3
            // row 0
            for (x in 0..7) { tile.pix[x] shouldBe 1 }
            // row 1
            for (x in 8..15) { tile.pix[x] shouldBe 2 }
            // row 2
            for (x in 16..23) { tile.pix[x] shouldBe 3 }
        }

        test("setScanline opaque flag: all-zero scanline marks row as not opaque") {
            val tile = Tile()
            // opaque[sline] starts false; if any pixel is 0, it stays false
            tile.setScanline(2, 0x00.toShort(), 0x00.toShort())
            tile.opaque[2] shouldBe false
        }

        test("setScanline opaque flag remains true when no zero pixels (default false initial)") {
            // opaque array is initialized to false by default in Tile
            // setScanline only sets opaque[sline]=false when a pixel == 0
            // so a fully non-zero scanline leaves opaque[sline] at its initial false value
            val tile = Tile()
            tile.setScanline(0, 0xFF.toShort(), 0xFF.toShort())
            // All pixels = 3, no pixel == 0, so opaque[0] is never set to false
            // It retains its BooleanArray-initialized value of false
            tile.opaque[0] shouldBe false
        }
    }
})
