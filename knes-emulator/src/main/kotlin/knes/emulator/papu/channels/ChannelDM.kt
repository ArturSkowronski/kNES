package knes.emulator.papu.channels

import knes.emulator.papu.PAPUAudioContext
import knes.emulator.papu.PAPUChannel

class ChannelDM(private var audioContext: PAPUAudioContext?) : PAPUChannel {
    @JvmField
    var isEnabled: Boolean = false
    var hasSample: Boolean = false
    @JvmField
    var irqGenerated: Boolean = false
    var playMode: Int = 0
    @JvmField
    var dmaFrequency: Int = 0
    var dmaCounter: Int = 0
    var deltaCounter: Int = 0
    var playStartAddress: Int = 0
    var playAddress: Int = 0
    var playLength: Int = 0
    var playLengthCounter: Int = 0
    @JvmField
    var shiftCounter: Int = 0
    var reg4012: Int = 0
    var reg4013: Int = 0
    var status: Int = 0
    @JvmField
    var sample: Int = 0
    var dacLsb: Int = 0
    var data: Int = 0

    override fun writeReg(address: Int, value: Short) {
        writeReg(address, value.toInt() and 0xFF)
    }

    override fun clock() {
        // Implementation of clock method required by IChannel
        // This should update the channel state on each clock cycle
    }

    fun clockDmc(irqNormal: Int) {
        // Only alter DAC value if the sample buffer has data:

        if (hasSample) {
            if ((data and 1) == 0) {
                // Decrement delta:

                if (deltaCounter > 0) {
                    deltaCounter--
                }
            } else {
                // Increment delta:

                if (deltaCounter < 63) {
                    deltaCounter++
                }
            }

            // Update sample value:
            sample = if (isEnabled) (deltaCounter shl 1) + dacLsb else 0

            // Update shift register:
            data = data shr 1
        }

        dmaCounter--
        if (dmaCounter <= 0) {
            // No more sample bits.

            hasSample = false
            endOfSample()
            dmaCounter = 8
        }

        if (irqGenerated) {
            audioContext!!.irqRequester.requestIrq(irqNormal)
        }
    }

    private fun endOfSample() {
        if (playLengthCounter == 0 && playMode == MODE_LOOP) {
            // Start from beginning of sample:

            playAddress = playStartAddress
            playLengthCounter = playLength
        }

        if (playLengthCounter > 0) {
            // Fetch next sample:

            nextSample()

            if (playLengthCounter == 0) {
                // Last byte of sample fetched, generate IRQ:

                if (playMode == MODE_IRQ) {
                    // Generate IRQ:

                    irqGenerated = true
                }
            }
        }
    }

    private fun nextSample() {
        // Fetch byte using DMCSampler instead of direct MemoryMapper access

        data = audioContext!!.PAPUDMCSampler.loadSample(playAddress)
        audioContext!!.irqRequester.haltCycles(4)

        playLengthCounter--
        playAddress++
        if (playAddress > 0xFFFF) {
            playAddress = 0x8000
        }

        hasSample = true
    }

    fun writeReg(address: Int, value: Int) {
        if (address == 0x4010) {
            // Play mode, DMA Frequency

            if ((value shr 6) == 0) {
                playMode = MODE_NORMAL
            } else if (((value shr 6) and 1) == 1) {
                playMode = MODE_LOOP
            } else if ((value shr 6) == 2) {
                playMode = MODE_IRQ
            }

            if ((value and 0x80) == 0) {
                irqGenerated = false
            }

            // Note: IAudioContext doesn't have getDmcFrequency method, so we need to implement it or use a different approach
            // For now, using a placeholder value
            dmaFrequency = 54 * (value and 0xF) + 100 // Simple approximation
        } else if (address == 0x4011) {
            // Delta counter load register:

            deltaCounter = (value shr 1) and 63
            dacLsb = value and 1
            // Note: IAudioContext doesn't have userEnableDmc field, so we need to implement it or use a different approach
            // For now, always updating the sample value
            sample = ((deltaCounter shl 1) + dacLsb) // update sample value
        } else if (address == 0x4012) {
            // DMA address load register

            playStartAddress = (value shl 6) or 0x0C000
            playAddress = playStartAddress
            reg4012 = value
        } else if (address == 0x4013) {
            // Length of play code

            playLength = (value shl 4) + 1
            playLengthCounter = playLength
            reg4013 = value
        } else if (address == 0x4015) {
            // DMC/IRQ Status

            if (((value shr 4) and 1) == 0) {
                // Disable:
                playLengthCounter = 0
            } else {
                // Restart:
                playAddress = playStartAddress
                playLengthCounter = playLength
            }
            irqGenerated = false
        }
    }

    fun setEnabled(value: Boolean) {
        if ((!isEnabled) && value) {
            playLengthCounter = playLength
        }
        isEnabled = value
    }

    override fun channelEnabled(): Boolean {
        return isEnabled
    }

    override val lengthStatus: Int
        get() = (if (playLengthCounter == 0 || !isEnabled) 0 else 1)

    val irqStatus: Int
        get() = (if (irqGenerated) 1 else 0)

    override fun reset() {
        isEnabled = false
        irqGenerated = false
        playMode = MODE_NORMAL
        dmaFrequency = 0
        dmaCounter = 0
        deltaCounter = 0
        playStartAddress = 0
        playAddress = 0
        playLength = 0
        playLengthCounter = 0
        status = 0
        sample = 0
        dacLsb = 0
        shiftCounter = 0
        reg4012 = 0
        reg4013 = 0
        data = 0
    }

    fun destroy() {
        audioContext = null
    }

    companion object {
        const val MODE_NORMAL: Int = 0
        const val MODE_LOOP: Int = 1
        const val MODE_IRQ: Int = 2
    }
}