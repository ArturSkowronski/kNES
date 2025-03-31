package vnes.emulator.papu.channels

import vnes.emulator.papu.IAudioContext
import vnes.emulator.papu.PAPUChannel

class ChannelNoise(var audioContext: IAudioContext?) : PAPUChannel {
    @JvmField
    var isEnabled: Boolean = false
    var envDecayDisable: Boolean = false
    var envDecayLoopEnable: Boolean = false
    var lengthCounterEnable: Boolean = false
    var envReset: Boolean = false
    var shiftNow: Boolean = false
    @JvmField
    var lengthCounter: Int = 0
    @JvmField
    var progTimerCount: Int = 0
    @JvmField
    var progTimerMax: Int = 0
    var envDecayRate: Int = 0
    var envDecayCounter: Int = 0
    var envVolume: Int = 0
    @JvmField
    var masterVolume: Int = 0
    @JvmField
    var shiftReg: Int
    @JvmField
    var randomBit: Int = 0
    @JvmField
    var randomMode: Int = 0
    @JvmField
    var sampleValue: Int = 0
    @JvmField
    var accValue: Long = 0
    @JvmField
    var accCount: Long = 1
    @JvmField
    var tmp: Int = 0

    init {
        shiftReg = 1 shl 14
    }

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
        } else if (--envDecayCounter <= 0) {
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

    fun updateSampleValue() {
        if (isEnabled && lengthCounter > 0) {
            sampleValue = randomBit * masterVolume
        }
    }

    fun writeReg(address: Int, value: Int) {
        if (address == 0x400C) {
            // Volume/Envelope decay:

            envDecayDisable = ((value and 0x10) != 0)
            envDecayRate = value and 0xF
            envDecayLoopEnable = ((value and 0x20) != 0)
            lengthCounterEnable = ((value and 0x20) == 0)
            masterVolume = if (envDecayDisable) envDecayRate else envVolume
        } else if (address == 0x400E) {
            // Programmable timer:
            // Note: IAudioContext doesn't have getNoiseWaveLength method, so we need to implement it or use a different approach
            // For now, using a placeholder value

            progTimerMax = 4 * (value and 0xF) // Simple approximation
            randomMode = value shr 7
        } else if (address == 0x400F) {
            // Length counter

            lengthCounter = audioContext!!.getLengthMax(value and 248)
            envReset = true
        }

        // Update:
        //updateSampleValue();
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
        isEnabled = false
        lengthCounter = 0
        lengthCounterEnable = false
        envDecayDisable = false
        envDecayLoopEnable = false
        shiftNow = false
        envDecayRate = 0
        envDecayCounter = 0
        envVolume = 0
        masterVolume = 0
        shiftReg = 1
        randomBit = 0
        randomMode = 0
        sampleValue = 0
        tmp = 0
    }

    fun destroy() {
        audioContext = null
    }
}