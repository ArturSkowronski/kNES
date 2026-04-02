package knes.emulator.ppu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for PPU register decoding and sprite RAM write logic.
 *
 * Because PPU control-flag fields are private, tests use two strategies:
 *  1. statusRegsToInt() — a public method that encodes all control flags into
 *     an Int, used to verify updateControlReg1 / updateControlReg2 effects.
 *  2. Reflection (via PpuTestHarness helpers) — used to inspect private sprite
 *     data arrays (sprX, sprY, sprTile, sprCol, vertFlip, horiFlip, bgPriority)
 *     after spriteRamWriteUpdate calls.
 *
 * statusRegsToInt() encoding (from PPU source):
 *   bit 0  = f_nmiOnVblank
 *   bit 1  = f_spriteSize
 *   bit 2  = f_bgPatternTable
 *   bit 3  = f_spPatternTable
 *   bit 4  = f_addrInc
 *   bits 5-6 = f_nTblAddress  (2 bits packed as (f_nTblAddress shl 5))
 *   bits 6-8 = f_color        (3 bits packed as (f_color shl 6))
 *   bit 7  = f_spVisibility
 *   bit 8  = f_bgVisibility
 *   bit 9  = f_spClipping
 *   bit 10 = f_bgClipping
 *   bit 11 = f_dispType
 */
class PPURegisterTest : FunSpec({

    // =========================================================================
    // Control Register 1 ($2000) — updateControlReg1
    // =========================================================================

    context("Control Register 1 decoding") {

        test("all-zero byte: all control-1 flags are 0") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x00)
            val status = h.ppu.statusRegsToInt()
            // bits 0-5 (f_nmiOnVblank through f_nTblAddress) should all be 0
            (status and 0x3F) shouldBe 0
        }

        test("bit 7 set: f_nmiOnVblank = 1 (appears at bit 0 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x80) // bit 7
            val status = h.ppu.statusRegsToInt()
            (status and 0x01) shouldBe 1  // f_nmiOnVblank at bit 0
        }

        test("bit 5 set: f_spriteSize = 1 (bit 1 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x20) // bit 5
            val status = h.ppu.statusRegsToInt()
            (status and 0x02) shouldBe 2  // f_spriteSize at bit 1
        }

        test("bit 4 set: f_bgPatternTable = 1 (bit 2 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x10) // bit 4
            val status = h.ppu.statusRegsToInt()
            (status and 0x04) shouldBe 4  // f_bgPatternTable at bit 2
        }

        test("bit 3 set: f_spPatternTable = 1 (bit 3 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x08) // bit 3
            val status = h.ppu.statusRegsToInt()
            (status and 0x08) shouldBe 8  // f_spPatternTable at bit 3
        }

        test("bit 2 set: f_addrInc = 1 (bit 4 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x04) // bit 2
            val status = h.ppu.statusRegsToInt()
            (status and 0x10) shouldBe 0x10  // f_addrInc at bit 4
        }

        test("bits 0-1 = 0b11: f_nTblAddress = 3 (bits 5-6 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x03)  // bits 0-1 both set
            val status = h.ppu.statusRegsToInt()
            // f_nTblAddress=3 stored at bits 5-6 as (3 shl 5) = 0x60
            (status shr 5 and 0x3) shouldBe 3
        }

        test("bits 0-1 = 0b01: f_nTblAddress = 1") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x01)
            val status = h.ppu.statusRegsToInt()
            (status shr 5 and 0x3) shouldBe 1
        }

        test("bits 0-1 = 0b10: f_nTblAddress = 2") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0x02)
            val status = h.ppu.statusRegsToInt()
            (status shr 5 and 0x3) shouldBe 2
        }

        test("all control-1 flags set (0xFF)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0xFF)
            val status = h.ppu.statusRegsToInt()
            // f_nmiOnVblank=1 (bit0), f_spriteSize=1 (bit1), f_bgPatternTable=1 (bit2),
            // f_spPatternTable=1 (bit3), f_addrInc=1 (bit4), f_nTblAddress=3 (bits5-6)
            (status and 0x01) shouldBe 1  // nmiOnVblank
            (status and 0x02) shouldBe 2  // spriteSize
            (status and 0x04) shouldBe 4  // bgPatternTable
            (status and 0x08) shouldBe 8  // spPatternTable
            (status and 0x10) shouldBe 0x10  // addrInc
            (status shr 5 and 0x3) shouldBe 3  // nTblAddress
        }

        test("update twice: second call overrides first") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg1(0xFF)
            h.ppu.updateControlReg1(0x00)
            val status = h.ppu.statusRegsToInt()
            (status and 0x1F) shouldBe 0  // all control-1 bits clear
        }
    }

    // =========================================================================
    // Control Register 2 ($2001) — updateControlReg2
    // =========================================================================

    context("Control Register 2 decoding") {

        test("all-zero byte: all control-2 flags are 0") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x00)
            val status = h.ppu.statusRegsToInt()
            // bits 6-11 of statusRegsToInt cover control-2 flags
            (status shr 6) shouldBe 0
        }

        test("bit 0 set: f_dispType = 1 (bit 11 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x01)
            val status = h.ppu.statusRegsToInt()
            (status shr 11 and 1) shouldBe 1  // f_dispType
        }

        test("bit 1 set: f_bgClipping = 1 (bit 10 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x02)
            val status = h.ppu.statusRegsToInt()
            (status shr 10 and 1) shouldBe 1  // f_bgClipping
        }

        test("bit 2 set: f_spClipping = 1 (bit 9 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x04)
            val status = h.ppu.statusRegsToInt()
            (status shr 9 and 1) shouldBe 1  // f_spClipping
        }

        test("bit 3 set: f_bgVisibility = 1 (bit 8 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x08)
            val status = h.ppu.statusRegsToInt()
            (status shr 8 and 1) shouldBe 1  // f_bgVisibility
        }

        test("bit 4 set: f_spVisibility = 1 (bit 7 of statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x10)
            val status = h.ppu.statusRegsToInt()
            (status shr 7 and 1) shouldBe 1  // f_spVisibility
        }

        test("bits 5-7 = 0b111: f_color = 7 (3 bits at offset 6 in statusRegsToInt)") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0xE0)  // bits 5,6,7 all set
            val status = h.ppu.statusRegsToInt()
            (status shr 6 and 0x7) shouldBe 7  // f_color
        }

        test("bits 5-7 = 0b001: f_color = 1") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x20)  // bit 5 only
            val status = h.ppu.statusRegsToInt()
            (status shr 6 and 0x7) shouldBe 1
        }

        test("bits 5-7 = 0b100: f_color = 4") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0x80.toInt())  // bit 7 only
            val status = h.ppu.statusRegsToInt()
            (status shr 6 and 0x7) shouldBe 4
        }

        test("update twice: second call overrides first") {
            val h = PpuTestHarness()
            h.ppu.updateControlReg2(0xFF)
            h.ppu.updateControlReg2(0x00)
            val status = h.ppu.statusRegsToInt()
            (status shr 6) shouldBe 0
        }
    }

    // =========================================================================
    // Sprite RAM Write Update — spriteRamWriteUpdate
    // =========================================================================

    context("Sprite RAM Write Update (OAM decode)") {

        test("address % 4 == 0: Y coordinate stored in sprY") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(4, 120.toShort())  // sprite index 1, byte 0 = Y
            h.readIntArrayElement("sprY", 1) shouldBe 120
        }

        test("address % 4 == 1: tile index stored in sprTile") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(5, 42.toShort())   // sprite index 1, byte 1 = tile
            h.readIntArrayElement("sprTile", 1) shouldBe 42
        }

        test("address % 4 == 3: X coordinate stored in sprX") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(7, 200.toShort())  // sprite index 1, byte 3 = X
            h.readIntArrayElement("sprX", 1) shouldBe 200
        }

        test("address % 4 == 2, bit 7 set: vertFlip = true") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x80.toShort())  // sprite 1, attributes byte
            h.readBoolArrayElement("vertFlip", 1) shouldBe true
        }

        test("address % 4 == 2, bit 7 clear: vertFlip = false") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x00.toShort())
            h.readBoolArrayElement("vertFlip", 1) shouldBe false
        }

        test("address % 4 == 2, bit 6 set: horiFlip = true") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x40.toShort())  // sprite 1, attributes
            h.readBoolArrayElement("horiFlip", 1) shouldBe true
        }

        test("address % 4 == 2, bit 6 clear: horiFlip = false") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x00.toShort())
            h.readBoolArrayElement("horiFlip", 1) shouldBe false
        }

        test("address % 4 == 2, bit 5 set: bgPriority = true") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x20.toShort())
            h.readBoolArrayElement("bgPriority", 1) shouldBe true
        }

        test("address % 4 == 2, bit 5 clear: bgPriority = false") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x00.toShort())
            h.readBoolArrayElement("bgPriority", 1) shouldBe false
        }

        test("address % 4 == 2: color bits (bits 0-1) stored as (val & 3) shl 2") {
            val h = PpuTestHarness()
            // bits 0-1 = 0b11, so sprCol = (3 and 3) shl 2 = 12
            h.ppu.spriteRamWriteUpdate(6, 0x03.toShort())
            h.readIntArrayElement("sprCol", 1) shouldBe 12
        }

        test("address % 4 == 2: color bits = 0b01 → sprCol = 4") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x01.toShort())
            h.readIntArrayElement("sprCol", 1) shouldBe 4
        }

        test("address % 4 == 2: color bits = 0b10 → sprCol = 8") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0x02.toShort())
            h.readIntArrayElement("sprCol", 1) shouldBe 8
        }

        test("address % 4 == 2: all attribute flags set (0xFF)") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(6, 0xFF.toShort())
            h.readBoolArrayElement("vertFlip", 1) shouldBe true
            h.readBoolArrayElement("horiFlip", 1) shouldBe true
            h.readBoolArrayElement("bgPriority", 1) shouldBe true
            h.readIntArrayElement("sprCol", 1) shouldBe 12
        }

        test("sprite index 0: Y stored in sprY[0]") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(0, 50.toShort())   // sprite 0, byte 0 = Y
            h.readIntArrayElement("sprY", 0) shouldBe 50
        }

        test("sprite index 63: X stored in sprX[63]") {
            val h = PpuTestHarness()
            h.ppu.spriteRamWriteUpdate(255, 128.toShort())  // sprite 63 (255/4=63), byte 3 = X
            h.readIntArrayElement("sprX", 63) shouldBe 128
        }
    }

    // =========================================================================
    // Scroll Register ($2005) — scrollWrite two-write latch
    // =========================================================================

    context("Scroll Register two-write latch") {

        test("first write sets regHT (horizontal tile) from bits 7-3") {
            val h = PpuTestHarness()
            // value=0b11111000=0xF8: (0xF8 shr 3) and 31 = 31
            h.ppu.scrollWrite(0xF8.toShort())
            h.readIntField("regHT") shouldBe 31
        }

        test("first write sets regFH (fine horizontal) from bits 2-0") {
            val h = PpuTestHarness()
            // value=0x07: regFH = 0x07 and 7 = 7
            h.ppu.scrollWrite(0x07.toShort())
            h.readIntField("regFH") shouldBe 7
        }

        test("second write sets regVT (vertical tile) from bits 7-3") {
            val h = PpuTestHarness()
            // first write (any value to advance latch)
            h.ppu.scrollWrite(0x00.toShort())
            // second write: value=0xF8 → regVT = (0xF8 shr 3) and 31 = 31
            h.ppu.scrollWrite(0xF8.toShort())
            h.readIntField("regVT") shouldBe 31
        }

        test("second write sets regFV (fine vertical) from bits 2-0") {
            val h = PpuTestHarness()
            h.ppu.scrollWrite(0x00.toShort())
            // second write: value=0x07 → regFV = 0x07 and 7 = 7
            h.ppu.scrollWrite(0x07.toShort())
            h.readIntField("regFV") shouldBe 7
        }

        test("latch alternates: first/second/first writes") {
            val h = PpuTestHarness()
            // Write 1: horizontal
            h.ppu.scrollWrite(0x18.toShort())  // regHT = 3, regFH = 0
            val ht1 = h.readIntField("regHT")
            // Write 2: vertical
            h.ppu.scrollWrite(0x28.toShort())  // regVT = 5, regFV = 0
            val vt1 = h.readIntField("regVT")
            // Write 3: horizontal again
            h.ppu.scrollWrite(0x40.toShort())  // regHT = 8
            val ht2 = h.readIntField("regHT")

            ht1 shouldBe 3
            vt1 shouldBe 5
            ht2 shouldBe 8
        }
    }

    // =========================================================================
    // VRAM Address Register ($2006) — writeVRAMAddress two-write latch
    // =========================================================================

    context("VRAM Address two-write latch") {

        test("first write sets regFV from bits 5-4") {
            val h = PpuTestHarness()
            // address=0x30: (0x30 shr 4) and 3 = 3
            h.ppu.writeVRAMAddress(0x30)
            h.readIntField("regFV") shouldBe 3
        }

        test("first write sets regV from bit 3") {
            val h = PpuTestHarness()
            // address=0x08: (0x08 shr 3) and 1 = 1
            h.ppu.writeVRAMAddress(0x08)
            h.readIntField("regV") shouldBe 1
        }

        test("first write sets regH from bit 2") {
            val h = PpuTestHarness()
            // address=0x04: (0x04 shr 2) and 1 = 1
            h.ppu.writeVRAMAddress(0x04)
            h.readIntField("regH") shouldBe 1
        }

        test("second write sets regHT from bits 4-0") {
            val h = PpuTestHarness()
            h.ppu.writeVRAMAddress(0x00)  // first write
            // second write: address=0x1F → regHT = 0x1F and 31 = 31
            h.ppu.writeVRAMAddress(0x1F)
            h.readIntField("regHT") shouldBe 31
        }

        test("latch toggles: second write uses lower byte") {
            val h = PpuTestHarness()
            h.ppu.writeVRAMAddress(0x20)  // first write: high byte portion
            h.ppu.writeVRAMAddress(0x10)  // second write: low byte
            // regHT from second write: (0x10 shr 5) and 7 ... and 0x10 and 31 = 16
            // Actually (0x10 shr 5)=0 for regVT-part, and 0x10 and 31 = 16 for regHT
            h.readIntField("regHT") shouldBe 16
        }
    }

    // =========================================================================
    // Status Register ($2002) — readStatusRegister
    // =========================================================================

    context("Status Register read side-effects") {

        test("readStatusRegister clears VBlank flag (bit 7) in cpuMem[0x2002]") {
            val h = PpuTestHarness()
            // Set VBlank flag manually: bit 7 at STATUS_VBLANK=7 → bit 7 of cpuMem[0x2002]
            val currentVal = h.cpuMemory.load(0x2002).toInt()
            h.cpuMemory.write(0x2002, (currentVal or 0x80).toShort())
            // Confirm it's set
            (h.cpuMemory.load(0x2002).toInt() and 0x80) shouldBe 0x80
            // Read status — should clear VBlank
            h.ppu.readStatusRegister()
            (h.cpuMemory.load(0x2002).toInt() and 0x80) shouldBe 0
        }

        test("readStatusRegister resets firstWrite latch to true") {
            val h = PpuTestHarness()
            // Advance the latch to secondWrite state via a scroll write
            h.ppu.scrollWrite(0x00.toShort())  // firstWrite: true → false
            h.readBoolField("firstWrite") shouldBe false
            // Reading status should reset it
            h.ppu.readStatusRegister()
            h.readBoolField("firstWrite") shouldBe true
        }

        test("readStatusRegister returns current value of cpuMem[0x2002]") {
            val h = PpuTestHarness()
            h.cpuMemory.write(0x2002, 0x60.toShort())  // bits 6 and 5 set
            val result = h.ppu.readStatusRegister()
            // Result should be the value before VBlank is cleared (bit 7 was already 0)
            (result.toInt() and 0xFF) shouldBe 0x60
        }

        test("readStatusRegister preserves non-VBlank bits") {
            val h = PpuTestHarness()
            // Set some bits but not VBlank (bit 7)
            h.cpuMemory.write(0x2002, 0x60.toShort())  // bits 6,5 set; bit 7 clear
            h.ppu.readStatusRegister()
            // After the read, VBlank (bit 7) stays 0, but bits 5,6 should remain
            (h.cpuMemory.load(0x2002).toInt() and 0x60) shouldBe 0x60
        }
    }
})
