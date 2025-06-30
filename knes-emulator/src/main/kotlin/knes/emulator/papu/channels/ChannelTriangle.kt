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

package knes.emulator.papu.channels

class ChannelTriangle(var audioContext: knes.emulator.papu.PAPUAudioContext?) : knes.emulator.papu.PAPUChannel {
    @JvmField
    var isEnabled: Boolean = false
    @JvmField
    var sampleCondition: Boolean = false
    var lengthCounterEnable: Boolean = false
    var lcHalt: Boolean = false
    var lcControl: Boolean = false
    @JvmField
    var progTimerCount: Int = 0
    @JvmField
    var progTimerMax: Int = 0
    @JvmField
    var triangleCounter: Int = 0
    @JvmField
    var lengthCounter: Int = 0
    @JvmField
    var linearCounter: Int = 0
    var lcLoadValue: Int = 0
    @JvmField
    var sampleValue: Int = 0
    var tmp: Int = 0

    override fun writeReg(address: Int, value: Short) {
        writeReg(address, value.toInt() and 0xFF)
    }

    override fun clock() {
        // Implementation of clock method required by IChannel
        // This should update the channel state on each clock cycle
    }

    fun clockLengthCounter() {
        if (lengthCounterEnable && lengthCounter > 0) {
            lengthCounter--
            if (lengthCounter == 0) {
                updateSampleCondition()
            }
        }
    }

    fun clockLinearCounter() {
        if (lcHalt) {
            // Load:

            linearCounter = lcLoadValue
            updateSampleCondition()
        } else if (linearCounter > 0) {
            // Decrement:

            linearCounter--
            updateSampleCondition()
        }

        if (!lcControl) {
            // Clear halt flag:

            lcHalt = false
        }
    }

    override val lengthStatus: Int
        get() = (if (lengthCounter == 0 || !isEnabled) 0 else 1)

    fun readReg(address_in: Int): Int {
        return 0
    }

    fun writeReg(address: Int, value: Int) {
        if (address == 0x4008) {
            // New values for linear counter:

            lcControl = (value and 0x80) != 0
            lcLoadValue = value and 0x7F

            // Length counter enable:
            lengthCounterEnable = !lcControl
        } else if (address == 0x400A) {
            // Programmable timer:

            progTimerMax = progTimerMax and 0x700
            progTimerMax = progTimerMax or value
        } else if (address == 0x400B) {
            // Programmable timer, length counter

            progTimerMax = progTimerMax and 0xFF
            progTimerMax = progTimerMax or ((value and 0x07) shl 8)
            lengthCounter = audioContext!!.getLengthMax(value and 0xF8)
            lcHalt = true
        }

        updateSampleCondition()
    }

    fun clockProgrammableTimer(nCycles: Int) {
        if (progTimerMax > 0) {
            progTimerCount += nCycles
            while (progTimerMax > 0 && progTimerCount >= progTimerMax) {
                progTimerCount -= progTimerMax
                if (isEnabled && lengthCounter > 0 && linearCounter > 0) {
                    clockTriangleGenerator()
                }
            }
        }
    }

    fun clockTriangleGenerator() {
        triangleCounter++
        triangleCounter = triangleCounter and 0x1F
    }

    fun setEnabled(value: Boolean) {
        isEnabled = value
        if (!value) {
            lengthCounter = 0
        }
        updateSampleCondition()
    }

    override fun channelEnabled(): Boolean {
        return isEnabled
    }

    fun updateSampleCondition() {
        sampleCondition =
            isEnabled && progTimerMax > 7 && linearCounter > 0 && lengthCounter > 0
    }

    override fun reset() {
        progTimerCount = 0
        progTimerMax = 0
        triangleCounter = 0
        isEnabled = false
        sampleCondition = false
        lengthCounter = 0
        lengthCounterEnable = false
        linearCounter = 0
        lcLoadValue = 0
        lcHalt = true
        lcControl = false
        tmp = 0
        sampleValue = 0xF
    }

    fun destroy() {
        audioContext = null
    }
}