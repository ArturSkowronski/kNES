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

package knes.emulator.papu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.cpu.CPUIIrqRequester
import knes.emulator.papu.channels.ChannelNoise
import knes.emulator.papu.channels.ChannelSquare
import knes.emulator.papu.channels.ChannelTriangle

// --- Mock implementations ---

class MockIrqRequester : CPUIIrqRequester {
    override fun requestIrq(type: Int) {}
    override fun haltCycles(cycles: Int) {}
}

class MockDMCSampler : PAPUDMCSampler {
    override fun loadSample(address: Int): Int = 0
    override fun hasPendingRead(): Boolean = false
    override val currentAddress: Int = 0
}

/**
 * Simple mock that replicates the real lengthLookup table from PAPU.
 * getLengthMax(value) returns lengthLookup[value shr 3], matching PAPU.getLengthMax().
 */
class MockAudioContext : PAPUAudioContext {
    override val irqRequester: CPUIIrqRequester = MockIrqRequester()
    override val PAPUDMCSampler: PAPUDMCSampler = MockDMCSampler()
    override val sampleRate: Int = 44100

    private val lengthLookup: IntArray = intArrayOf(
        0x0A, 0xFE,
        0x14, 0x02,
        0x28, 0x04,
        0x50, 0x06,
        0xA0, 0x08,
        0x3C, 0x0A,
        0x0E, 0x0C,
        0x1A, 0x0E,
        0x0C, 0x10,
        0x18, 0x12,
        0x30, 0x14,
        0x60, 0x16,
        0xC0, 0x18,
        0x48, 0x1A,
        0x10, 0x1C,
        0x20, 0x1E
    )

    override fun getLengthMax(value: Int): Int = lengthLookup[value shr 3]
    override fun clockFrameCounter(cycles: Int) {}
    override fun updateChannelEnable(value: Int) {}
}

// ---------------------------------------------------------------------------
// ChannelSquare Tests
// ---------------------------------------------------------------------------

class ChannelTest : FunSpec({

    // --- ChannelSquare ---

    context("ChannelSquare") {

        test("writeReg 0x4000: duty mode, envelope rate, envelope disable, envelope loop") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            // value = 0b10110101 = 0xB5
            // bits 7-6 = 0b10 => dutyMode = 2
            // bit 5 = 1 => envDecayLoopEnable = true, lengthCounterEnable = false
            // bit 4 = 1 => envDecayDisable = true
            // bits 3-0 = 0b0101 = 5 => envDecayRate = 5
            ch.writeReg(0x4000, 0xB5)
            ch.dutyMode shouldBe 2
            ch.envDecayRate shouldBe 5
            ch.envDecayDisable shouldBe true
            ch.envDecayLoopEnable shouldBe true
            ch.lengthCounterEnable shouldBe false
        }

        test("writeReg 0x4000: envelope loop disabled when bit5 = 0") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            // value = 0x0F: dutyMode=0, envDecayDisable=0, envDecayLoopEnable=0, rate=15
            ch.writeReg(0x4000, 0x0F)
            ch.dutyMode shouldBe 0
            ch.envDecayRate shouldBe 15
            ch.envDecayDisable shouldBe false
            ch.envDecayLoopEnable shouldBe false
            ch.lengthCounterEnable shouldBe true
        }

        test("writeReg 0x4001: sweep enable, period, mode, shift") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            // value = 0b10110101 = 0xB5
            // bit 7 = 1 => sweepActive = true
            // bits 6-4 = 0b011 = 3 => sweepCounterMax = 3
            // bit 3 = 0 => sweepMode = 0
            // bits 2-0 = 0b101 = 5 => sweepShiftAmount = 5
            ch.writeReg(0x4001, 0b10110101)
            ch.sweepActive shouldBe true
            ch.sweepCounterMax shouldBe 3
            ch.sweepMode shouldBe 0
            ch.sweepShiftAmount shouldBe 5
            ch.updateSweepPeriod shouldBe true
        }

        test("writeReg 0x4001: sweep disabled") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.writeReg(0x4001, 0x00)
            ch.sweepActive shouldBe false
            ch.sweepMode shouldBe 0
            ch.sweepShiftAmount shouldBe 0
        }

        test("writeReg 0x4002 and 0x4003: frequency timer low and high bytes") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.writeReg(0x4002, 0xAB) // low 8 bits
            ch.progTimerMax shouldBe 0xAB
            // 0x4003: high 3 bits in bits 2-0, length counter loaded from bits 7-3
            // value = 0b00000101 = 0x05 => high bits = 0b101 => timer |= 0x500
            ch.writeReg(0x4003, 0x05)
            ch.progTimerMax shouldBe (0xAB or (0x5 shl 8))
        }

        test("setEnabled(true): lengthStatus becomes 1 when lengthCounter > 0") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.setEnabled(true)
            // After enable alone, lengthCounter is still 0 so lengthStatus = 0
            ch.lengthStatus shouldBe 0
            // Set a non-zero progTimerMax and write 0x4003 to load lengthCounter
            ch.progTimerMax = 100
            ch.writeReg(0x4003, 0x00) // loads lengthLookup[0] = 0x0A = 10
            ch.lengthCounter shouldBe 0x0A
            ch.lengthStatus shouldBe 1
        }

        test("setEnabled(false): lengthStatus becomes 0") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.isEnabled = true
            ch.lengthCounter = 5
            ch.setEnabled(false)
            ch.lengthStatus shouldBe 0
            ch.lengthCounter shouldBe 0
        }

        test("clockLengthCounter: decrements counter when enabled and counter > 0") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.isEnabled = true
            ch.lengthCounterEnable = true
            ch.lengthCounter = 5
            ch.clockLengthCounter()
            ch.lengthCounter shouldBe 4
        }

        test("clockLengthCounter: does not decrement when lengthCounterEnable = false") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.isEnabled = true
            ch.lengthCounterEnable = false
            ch.lengthCounter = 5
            ch.clockLengthCounter()
            ch.lengthCounter shouldBe 5
        }

        test("clockLengthCounter: does not go below zero") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.isEnabled = true
            ch.lengthCounterEnable = true
            ch.lengthCounter = 1
            ch.clockLengthCounter()
            ch.lengthCounter shouldBe 0
            ch.clockLengthCounter() // second call at 0 — should be a no-op
            ch.lengthCounter shouldBe 0
        }

        test("clockEnvDecay: resets envelope when envReset is true") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.envReset = true
            ch.envDecayRate = 3
            ch.envDecayDisable = false
            ch.clockEnvDecay()
            ch.envReset shouldBe false
            ch.envDecayCounter shouldBe 4 // envDecayRate + 1
            ch.envVolume shouldBe 0xF
        }

        test("clockEnvDecay: decrements volume on normal clock") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.envReset = false
            ch.envDecayRate = 0
            ch.envDecayCounter = 1   // will hit <= 0 branch on first decrement
            ch.envVolume = 5
            ch.envDecayDisable = false
            ch.envDecayLoopEnable = false
            ch.clockEnvDecay()
            ch.envVolume shouldBe 4
        }

        test("clockEnvDecay: loops volume when envDecayLoopEnable = true and volume = 0") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.envReset = false
            ch.envDecayRate = 0
            ch.envDecayCounter = 1
            ch.envVolume = 0
            ch.envDecayDisable = false
            ch.envDecayLoopEnable = true
            ch.clockEnvDecay()
            ch.envVolume shouldBe 0xF
        }

        test("clockEnvDecay: volume stays 0 when loop disabled and volume = 0") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.envReset = false
            ch.envDecayRate = 0
            ch.envDecayCounter = 1
            ch.envVolume = 0
            ch.envDecayDisable = false
            ch.envDecayLoopEnable = false
            ch.clockEnvDecay()
            ch.envVolume shouldBe 0
        }

        test("reset: returns state to defaults") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = true)
            ch.isEnabled = true
            ch.lengthCounter = 10
            ch.progTimerMax = 200
            ch.envDecayRate = 7
            ch.dutyMode = 3
            ch.sweepActive = true
            ch.reset()
            ch.isEnabled shouldBe false
            ch.lengthCounter shouldBe 0
            ch.progTimerMax shouldBe 0
            ch.progTimerCount shouldBe 0
            ch.squareCounter shouldBe 0
            ch.sweepCounter shouldBe 0
            ch.sweepCounterMax shouldBe 0
            ch.sweepMode shouldBe 0
            ch.sweepShiftAmount shouldBe 0
            ch.envDecayRate shouldBe 0
            ch.envDecayCounter shouldBe 0
            ch.envVolume shouldBe 0
            ch.masterVolume shouldBe 0
            ch.dutyMode shouldBe 0
            ch.sweepActive shouldBe false
            ch.sweepCarry shouldBe false
            ch.envDecayDisable shouldBe false
            ch.envDecayLoopEnable shouldBe false
            ch.lengthCounterEnable shouldBe false
        }

        test("sqr2 uses address offset 0x4004-0x4007") {
            val ctx = MockAudioContext()
            val ch = ChannelSquare(ctx, sqr1 = false) // square 2
            ch.writeReg(0x4004, 0b11000111) // dutyMode=3, envDecayDisable=false, rate=7
            ch.dutyMode shouldBe 3
            ch.envDecayRate shouldBe 7
        }
    }

    // ---------------------------------------------------------------------------
    // ChannelTriangle Tests
    // ---------------------------------------------------------------------------

    context("ChannelTriangle") {

        test("writeReg 0x4008: linear counter load value and control flag") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            // value = 0b10111010 = 0xBA
            // bit 7 = 1 => lcControl = true, lengthCounterEnable = false
            // bits 6-0 = 0b0111010 = 58 => lcLoadValue = 58
            ch.writeReg(0x4008, 0xBA)
            ch.lcControl shouldBe true
            ch.lcLoadValue shouldBe 58
            ch.lengthCounterEnable shouldBe false
        }

        test("writeReg 0x4008: lcControl false enables length counter") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.writeReg(0x4008, 0x3F) // bit 7 = 0 => lcControl = false
            ch.lcControl shouldBe false
            ch.lengthCounterEnable shouldBe true
            ch.lcLoadValue shouldBe 0x3F
        }

        test("writeReg 0x400A and 0x400B: frequency timer set correctly") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.setEnabled(true)
            ch.writeReg(0x400A, 0xCD) // low 8 bits
            ch.progTimerMax shouldBe 0xCD
            // 0x400B: bits 2-0 are high timer bits, bits 7-3 index lengthLookup
            // value = 0x06 => high bits = 0b110 = 6 => timer = 0x6CD
            ch.writeReg(0x400B, 0x06)
            ch.progTimerMax shouldBe (0xCD or (0x6 shl 8))
        }

        test("writeReg 0x400B: loads length counter and sets lcHalt") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.setEnabled(true)
            // value = 0x08: bits 7-3 = 0b00001 => index 1 => lengthLookup[1] = 0xFE
            ch.writeReg(0x400B, 0x08)
            ch.lengthCounter shouldBe 0xFE
            ch.lcHalt shouldBe true
        }

        test("clockLinearCounter: loads from lcLoadValue when lcHalt is true") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.lcHalt = true
            ch.lcLoadValue = 20
            ch.linearCounter = 0
            ch.clockLinearCounter()
            ch.linearCounter shouldBe 20
        }

        test("clockLinearCounter: clears lcHalt when lcControl is false") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.lcHalt = true
            ch.lcControl = false
            ch.lcLoadValue = 10
            ch.clockLinearCounter()
            ch.lcHalt shouldBe false
        }

        test("clockLinearCounter: keeps lcHalt when lcControl is true") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.lcHalt = true
            ch.lcControl = true
            ch.lcLoadValue = 10
            ch.clockLinearCounter()
            ch.lcHalt shouldBe true
        }

        test("clockLinearCounter: decrements when lcHalt is false and counter > 0") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.lcHalt = false
            ch.linearCounter = 7
            ch.clockLinearCounter()
            ch.linearCounter shouldBe 6
        }

        test("clockLinearCounter: does not go below zero") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.lcHalt = false
            ch.linearCounter = 0
            ch.clockLinearCounter()
            ch.linearCounter shouldBe 0
        }

        test("clockLengthCounter: decrements when enabled and counter > 0") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.isEnabled = true
            ch.lengthCounterEnable = true
            ch.lengthCounter = 8
            ch.clockLengthCounter()
            ch.lengthCounter shouldBe 7
        }

        test("clockLengthCounter: does not decrement when disabled") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.isEnabled = true
            ch.lengthCounterEnable = false
            ch.lengthCounter = 8
            ch.clockLengthCounter()
            ch.lengthCounter shouldBe 8
        }

        test("setEnabled(true): channel enabled, lengthStatus reflects counter") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.setEnabled(true)
            ch.isEnabled shouldBe true
            ch.lengthStatus shouldBe 0 // counter still 0
        }

        test("setEnabled(false): zeros lengthCounter and lengthStatus = 0") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.isEnabled = true
            ch.lengthCounter = 10
            ch.setEnabled(false)
            ch.lengthCounter shouldBe 0
            ch.lengthStatus shouldBe 0
        }

        test("lengthStatus is 1 when enabled and lengthCounter > 0") {
            val ctx = MockAudioContext()
            val ch = ChannelTriangle(ctx)
            ch.setEnabled(true)
            ch.writeReg(0x400B, 0x00) // loads lengthLookup[0] = 0x0A
            ch.lengthCounter shouldBe 0x0A
            ch.lengthStatus shouldBe 1
        }
    }

    // ---------------------------------------------------------------------------
    // ChannelNoise Tests
    // ---------------------------------------------------------------------------

    context("ChannelNoise") {

        test("writeReg 0x400C: envelope settings") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            // value = 0b00110110 = 0x36
            // bit 5 = 1 => envDecayLoopEnable = true, lengthCounterEnable = false
            // bit 4 = 1 => envDecayDisable = true
            // bits 3-0 = 0b0110 = 6 => envDecayRate = 6
            ch.writeReg(0x400C, 0x36)
            ch.envDecayLoopEnable shouldBe true
            ch.envDecayDisable shouldBe true
            ch.envDecayRate shouldBe 6
            ch.lengthCounterEnable shouldBe false
        }

        test("writeReg 0x400C: length counter enabled when bit5 = 0") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.writeReg(0x400C, 0x09) // bit 5 = 0
            ch.envDecayLoopEnable shouldBe false
            ch.lengthCounterEnable shouldBe true
            ch.envDecayRate shouldBe 9
        }

        test("writeReg 0x400E: timer period and random mode") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            // value = 0b10000011 = 0x83
            // bit 7 = 1 => randomMode = 1
            // bits 3-0 = 0b0011 = 3 => progTimerMax = 4 * 3 = 12
            ch.writeReg(0x400E, 0x83)
            ch.randomMode shouldBe 1
            ch.progTimerMax shouldBe 12
        }

        test("writeReg 0x400E: random mode 0") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.writeReg(0x400E, 0x05) // bit 7 = 0, bits 3-0 = 5 => progTimerMax = 20
            ch.randomMode shouldBe 0
            ch.progTimerMax shouldBe 20
        }

        test("writeReg 0x400F: length counter load and envReset") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.setEnabled(true)
            // value = 0x10 => (value and 0xF8) = 0x10, getLengthMax(0x10) => lengthLookup[0x10 shr 3] = lengthLookup[2] = 0x14 = 20
            ch.writeReg(0x400F, 0x10)
            ch.lengthCounter shouldBe 0x14
            ch.envReset shouldBe true
        }

        test("writeReg 0x400F: lengthLookup index 0 loads 0x0A") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.setEnabled(true)
            ch.writeReg(0x400F, 0x00) // bits 7-3 = 0 => lengthLookup[0] = 0x0A
            ch.lengthCounter shouldBe 0x0A
        }

        test("setEnabled(true): channel is enabled") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.setEnabled(true)
            ch.isEnabled shouldBe true
        }

        test("setEnabled(false): zeros lengthCounter and lengthStatus = 0") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.isEnabled = true
            ch.lengthCounter = 12
            ch.setEnabled(false)
            ch.lengthCounter shouldBe 0
            ch.lengthStatus shouldBe 0
        }

        test("lengthStatus is 1 when enabled and lengthCounter > 0") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.setEnabled(true)
            ch.writeReg(0x400F, 0x00)
            ch.lengthStatus shouldBe 1
        }

        test("reset: returns state to defaults") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.isEnabled = true
            ch.lengthCounter = 10
            ch.envDecayRate = 7
            ch.randomMode = 1
            ch.reset()
            ch.isEnabled shouldBe false
            ch.lengthCounter shouldBe 0
            ch.progTimerCount shouldBe 0
            ch.progTimerMax shouldBe 0
            ch.envDecayDisable shouldBe false
            ch.envDecayLoopEnable shouldBe false
            ch.envDecayRate shouldBe 0
            ch.envDecayCounter shouldBe 0
            ch.envVolume shouldBe 0
            ch.masterVolume shouldBe 0
            ch.randomBit shouldBe 0
            ch.randomMode shouldBe 0
            ch.sampleValue shouldBe 0
        }

        test("shiftReg initialized to 1 shl 14 on construction") {
            val ctx = MockAudioContext()
            val ch = ChannelNoise(ctx)
            ch.shiftReg shouldBe (1 shl 14)
        }
    }
})
