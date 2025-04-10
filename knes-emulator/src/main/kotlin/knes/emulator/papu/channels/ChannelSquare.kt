package knes.emulator.papu.channels

import knes.emulator.papu.PAPUAudioContext
import knes.emulator.papu.PAPUChannel

class ChannelSquare(var audioContext: PAPUAudioContext?, var sqr1: Boolean) :
    PAPUChannel {
    @JvmField
    var isEnabled: Boolean = false
    var lengthCounterEnable: Boolean = false
    var sweepActive: Boolean = false
    var envDecayDisable: Boolean = false
    var envDecayLoopEnable: Boolean = false
    var envReset: Boolean = false
    var sweepCarry: Boolean = false
    var updateSweepPeriod: Boolean = false
    @JvmField
    var progTimerCount: Int = 0
    @JvmField
    var progTimerMax: Int = 0
    var lengthCounter: Int = 0
    @JvmField
    var squareCounter: Int = 0
    var sweepCounter: Int = 0
    var sweepCounterMax: Int = 0
    var sweepMode: Int = 0
    var sweepShiftAmount: Int = 0
    var envDecayRate: Int = 0
    var envDecayCounter: Int = 0
    var envVolume: Int = 0
    var masterVolume: Int = 0
    var dutyMode: Int = 0
    var sweepResult: Int = 0
    @JvmField
    var sampleValue: Int = 0
    var vol: Int = 0

    override fun clock() {
        // Implementation of clock method required by IChannel
        // This method would be called during the audio processing cycle
    }

    override fun writeReg(address: Int, value: Short) {
        // Convert short to int and call the existing method
        writeReg(address, value.toInt())
    }

    fun clockLengthCounter() {
        if (lengthCounterEnable && lengthCounter > 0) {
            lengthCounter--
            if (lengthCounter == 0) {
                updateSampleValue()
            }
        }
    }

    fun clockEnvDecay() {
        if (envReset) {
            // Reset envelope:

            envReset = false
            envDecayCounter = envDecayRate + 1
            envVolume = 0xF
        } else if ((--envDecayCounter) <= 0) {
            // Normal handling:

            envDecayCounter = envDecayRate + 1
            if (envVolume > 0) {
                envVolume--
            } else {
                envVolume = if (envDecayLoopEnable) 0xF else 0
            }
        }

        masterVolume = if (envDecayDisable) envDecayRate else envVolume
        updateSampleValue()
    }

    fun clockSweep() {
        if (--sweepCounter <= 0) {
            sweepCounter = sweepCounterMax + 1
            if (sweepActive && sweepShiftAmount > 0 && progTimerMax > 7) {
                // Calculate result from shifter:

                sweepCarry = false
                if (sweepMode == 0) {
                    progTimerMax += (progTimerMax shr sweepShiftAmount)
                    if (progTimerMax > 4095) {
                        progTimerMax = 4095
                        sweepCarry = true
                    }
                } else {
                    progTimerMax = progTimerMax - ((progTimerMax shr sweepShiftAmount) - (if (sqr1) 1 else 0))
                }
            }
        }

        if (updateSweepPeriod) {
            updateSweepPeriod = false
            sweepCounter = sweepCounterMax + 1
        }
    }

    fun updateSampleValue() {
        if (isEnabled && lengthCounter > 0 && progTimerMax > 7) {
            if (sweepMode == 0 && (progTimerMax + (progTimerMax shr sweepShiftAmount)) > 4095) {
                //if(sweepCarry){

                sampleValue = 0
            } else {
                sampleValue = masterVolume * dutyLookup[(dutyMode shl 3) + squareCounter]
            }
        } else {
            sampleValue = 0
        }
    }

    fun writeReg(address: Int, value: Int) {
        val addrAdd = (if (sqr1) 0 else 4)
        if (address == 0x4000 + addrAdd) {
            // Volume/Envelope decay:

            envDecayDisable = ((value and 0x10) != 0)
            envDecayRate = value and 0xF
            envDecayLoopEnable = ((value and 0x20) != 0)
            dutyMode = (value shr 6) and 0x3
            lengthCounterEnable = ((value and 0x20) == 0)
            masterVolume = if (envDecayDisable) envDecayRate else envVolume
            updateSampleValue()
        } else if (address == 0x4001 + addrAdd) {
            // Sweep:

            sweepActive = ((value and 0x80) != 0)
            sweepCounterMax = ((value shr 4) and 7)
            sweepMode = (value shr 3) and 1
            sweepShiftAmount = value and 7
            updateSweepPeriod = true
        } else if (address == 0x4002 + addrAdd) {
            // Programmable timer:

            progTimerMax = progTimerMax and 0x700
            progTimerMax = progTimerMax or value
        } else if (address == 0x4003 + addrAdd) {
            // Programmable timer, length counter

            progTimerMax = progTimerMax and 0xFF
            progTimerMax = progTimerMax or ((value and 0x7) shl 8)

            if (isEnabled) {
                // Use audioContext directly
                lengthCounter = audioContext!!.getLengthMax(value and 0xF8)
            }

            envReset = true
        }
    }

    fun setEnabled(value: Boolean) {
        isEnabled = value
        if (!value) {
            lengthCounter = 0
        }
        updateSampleValue()
    }

    override fun channelEnabled(): Boolean {
        return isEnabled
    }

    override val lengthStatus: Int
        get() = (if (lengthCounter == 0 || !isEnabled) 0 else 1)

    override fun reset() {
        progTimerCount = 0
        progTimerMax = 0
        lengthCounter = 0
        squareCounter = 0
        sweepCounter = 0
        sweepCounterMax = 0
        sweepMode = 0
        sweepShiftAmount = 0
        envDecayRate = 0
        envDecayCounter = 0
        envVolume = 0
        masterVolume = 0
        dutyMode = 0
        vol = 0

        isEnabled = false
        lengthCounterEnable = false
        sweepActive = false
        sweepCarry = false
        envDecayDisable = false
        envDecayLoopEnable = false
    }

    fun destroy() {
        audioContext = null
    }


    companion object {
        var dutyLookup: IntArray
        var impLookup: IntArray?

        init {
            dutyLookup = intArrayOf(
                0, 1, 0, 0, 0, 0, 0, 0,
                0, 1, 1, 0, 0, 0, 0, 0,
                0, 1, 1, 1, 1, 0, 0, 0,
                1, 0, 0, 1, 1, 1, 1, 1,
            )

            impLookup = intArrayOf(
                1, -1, 0, 0, 0, 0, 0, 0,
                1, 0, -1, 0, 0, 0, 0, 0,
                1, 0, 0, 0, -1, 0, 0, 0,
                -1, 0, 1, 0, 0, 0, 0, 0,
            )
        }
    }
}