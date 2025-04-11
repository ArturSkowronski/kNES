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

import knes.emulator.Memory
import knes.emulator.NES
import knes.emulator.cpu.CPU
import knes.emulator.cpu.CPUIIrqRequester
import knes.emulator.mappers.MemoryMapper
import knes.emulator.papu.channels.ChannelDM
import knes.emulator.papu.channels.ChannelNoise
import knes.emulator.papu.channels.ChannelSquare
import knes.emulator.papu.channels.ChannelTriangle
import knes.emulator.producers.ChannelRegistryProducer
import knes.emulator.ui.PAPU_Applet_Functionality
import knes.emulator.utils.Globals
import java.nio.ByteBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

class PAPU(nes: NES) : PAPU_Applet_Functionality, PAPUAudioContext, PAPUDMCSampler, PAPUClockFrame {
    /**
     * @return Current address pointer for sample loading
     */
    override var currentAddress: Int = 0
        private set
    private val memoryMapper: MemoryMapper?
    var cpuMem: Memory?
    var mixer: Mixer? = null

    /**
     * Get the IRQ requester for interrupt handling.
     * @return The IRQ requester
     */
    override lateinit var irqRequester: CPUIIrqRequester
    private var registry: ChannelRegistry? = null

    override var line: SourceDataLine? = null
    var square1: ChannelSquare? = null
    var square2: ChannelSquare? = null
    var triangle: ChannelTriangle? = null
    var noise: ChannelNoise? = null
    var dmc: ChannelDM? = null

    var lengthLookup: IntArray = IntArray(0)
    var dmcFreqLookup: IntArray = IntArray(0)
    var noiseWavelengthLookup: IntArray = IntArray(0)
    var square_table: IntArray = IntArray(0)
    var tnd_table: IntArray = IntArray(0)
    var ismpbuffer: IntArray?
    var sampleBuffer: ByteArray = ByteArray(0)
    var frameIrqCounter: Int = 0
    var frameIrqCounterMax: Int
    var initCounter: Int
    var channelEnableValue: Short = 0
    var b1: Byte = 0
    var b2: Byte = 0
    var b3: Byte = 0
    var b4: Byte = 0
    var bufferSize: Int = 2048

    var bufferPos: Int
    override var sampleRate: Int = 44100

    override val bufferIndex: Int
        get() = bufferPos
    var frameIrqEnabled: Boolean
    var frameIrqActive: Boolean = false
    var frameClockNow: Boolean = false
    var startedPlaying: Boolean = false
    var recordOutput: Boolean = false
    var stereo: Boolean = true
    var initingHardware: Boolean = false
    private var userEnableSquare1 = true
    private var userEnableSquare2 = true
    private var userEnableTriangle = true
    private var userEnableNoise = true
    var userEnableDmc: Boolean = true
    var masterFrameCounter: Int = 0
    var derivedFrameCounter: Int = 0
    var countSequence: Int = 0
    var sampleTimer: Int = 0
    var frameTime: Int = 0
    var sampleTimerMax: Int = 0
    var sampleCount: Int = 0
    var sampleValueL: Int = 0
    var sampleValueR: Int = 0
    var triValue: Int = 0
    var smpSquare1: Int = 0
    var smpSquare2: Int = 0
    var smpTriangle: Int = 0
    var smpNoise: Int = 0
    var smpDmc: Int = 0
    var accCount: Int = 0
    var sq_index: Int = 0
    var tnd_index: Int = 0

    // DC removal vars:
    var prevSampleL: Int = 0
    var prevSampleR: Int = 0
    var smpAccumL: Int = 0
    var smpAccumR: Int = 0
    var smpDiffL: Int = 0
    var smpDiffR: Int = 0

    // DAC range:
    var dacRange: Int = 0
    var dcValue: Int = 0

    // Master volume:
    var masterVolume: Int = 256
        set(value) {
            var adjustedValue = value
            if (adjustedValue < 0) {
                adjustedValue = 0
            }
            if (adjustedValue > 256) {
                adjustedValue = 256
            }
            field = adjustedValue
            updateStereoPos()
        }

    // Panning:
    var panning: IntArray = intArrayOf(80, 170, 100, 150, 128)
        set(pos) {
            System.arraycopy(pos, 0, field, 0, 5)
            updateStereoPos()
        }

    // Stereo positioning:
    var stereoPosLSquare1: Int = 0
    var stereoPosLSquare2: Int = 0
    var stereoPosLTriangle: Int = 0
    var stereoPosLNoise: Int = 0
    var stereoPosLDMC: Int = 0
    var stereoPosRSquare1: Int = 0
    var stereoPosRSquare2: Int = 0
    var stereoPosRTriangle: Int = 0
    var stereoPosRNoise: Int = 0
    var stereoPosRDMC: Int = 0
    var extraCycles: Int = 0
    var maxCycles: Int = 0


    override val PAPUDMCSampler: PAPUDMCSampler
        /**
         * Get the DMC sampler for sample loading operations.
         * @return The DMC sampler (this)
         */
        get() = this

    /**
     * Loads a 7-bit sample from the specified memory address
     * @param address CPU memory address (0x0000-0xFFFF)
     * @return Unsigned 7-bit sample value (0-127)
     */
    override fun loadSample(address: Int): Int {
        this.currentAddress = address
        return memoryMapper!!.load(address).toInt()
    }

    /**
     * @return true if there's a pending memory read operation
     */
    override fun hasPendingRead(): Boolean {
        // Delegate to memory mapper if it has a method for this
        // For now, return false as default implementation
        return false
    }


    /**
     * New constructor that accepts a channel registry
     */
    init {
        cpuMem = nes.cpuMemory
        memoryMapper = nes.memoryMapper

        setSampleRate(nes, sampleRate, false)
        sampleBuffer = ByteArray(bufferSize * (if (stereo) 4 else 2))
        ismpbuffer = IntArray(bufferSize * (if (stereo) 2 else 1))
        this.bufferPos = 0
        frameIrqEnabled = false
        initCounter = 2048

        // masterVolume and panning are already initialized with their property declarations
        updateStereoPos()

        // Initialize lookup tables:
        initLengthLookup()
        initDmcFrequencyLookup()
        initNoiseWavelengthLookup()
        initDACtables()

        frameIrqEnabled = false
        frameIrqCounterMax = 4
    }

    fun init(channelRegistryProducer: ChannelRegistryProducer) {
        // Init sound registers:
        this.registry = channelRegistryProducer.produce(this)
        square1 = registry!!.getChannel(0x4000) as ChannelSquare?
        square2 = registry!!.getChannel(0x4004) as ChannelSquare?
        triangle = registry!!.getChannel(0x4008) as ChannelTriangle?
        noise = registry!!.getChannel(0x400C) as ChannelNoise?
        dmc = registry!!.getChannel(0x4010) as ChannelDM?
        for (i in 0..0x13) {
            if (i == 0x10) {
                writeReg(0x4010, 0x10.toShort())
            } else {
                writeReg(0x4000 + i, 0.toShort())
            }
        }
    }

    fun stateLoad(buf: ByteBuffer?) {
        // not yet.
    }

    fun stateSave(buf: ByteBuffer?) {
        // not yet.
    }

    @Synchronized
    fun start() {
        if (line != null && line!!.isActive()) {
            //System.out.println("* Already running.");
            return
        }

        this.bufferPos = 0
        val mixerInfo = AudioSystem.getMixerInfo()

        if (mixerInfo == null || mixerInfo.size == 0) {
            //System.out.println("No audio mixer available, sound disabled.");
            Globals.enableSound = false
            return
        }

        mixer = AudioSystem.getMixer(mixerInfo[1])

        val audioFormat = AudioFormat(sampleRate.toFloat(), 16, (if (stereo) 2 else 1), true, false)
        val info = DataLine.Info(SourceDataLine::class.java, audioFormat, sampleRate)

        try {
            line = AudioSystem.getLine(info) as SourceDataLine?
            line!!.open(audioFormat)
            line!!.start()
        } catch (e: Exception) {
            //System.out.println("Couldn't get sound lines.");
        }
    }

    fun readReg(address: Int): Short {
        // Read 0x4015:

        var tmp = 0
        tmp = tmp or (square1!!.lengthStatus)
        tmp = tmp or (square2!!.lengthStatus shl 1)
        tmp = tmp or (triangle!!.lengthStatus shl 2)
        tmp = tmp or (noise!!.lengthStatus shl 3)
        tmp = tmp or (dmc!!.lengthStatus shl 4)
        tmp = tmp or ((if (frameIrqActive && frameIrqEnabled) 1 else 0) shl 6)
        tmp = tmp or (dmc!!.irqStatus shl 7)

        frameIrqActive = false
        dmc!!.irqGenerated = false

        // System.out.println("\$4015 read. Value = " + Misc.bin8(tmp) + " countseq = " + countSequence)
        return tmp.toShort()
    }

    fun writeReg(address: Int, value: Short) {
        // Use registry to route register writes to appropriate channels
        if (address >= 0x4000 && address <= 0x4013) {
            val channel = registry!!.getChannel(address)
            if (channel != null) {
                channel.writeReg(address, value)
            }
        } else if (address == 0x4015) {
            // Channel enable

            updateChannelEnable(value.toInt())

            if (value.toInt() != 0 && initCounter > 0) {
                // Start hardware initialization

                initingHardware = true
            }

            // DMC/IRQ Status
            dmc!!.writeReg(address, value)
        } else if (address == 0x4017) {
            // Frame counter control


            countSequence = (value.toInt() shr 7) and 1
            masterFrameCounter = 0
            frameIrqActive = false

            frameIrqEnabled = ((value.toInt() shr 6) and 0x1) == 0

            if (countSequence == 0) {
                // NTSC:

                frameIrqCounterMax = 4
                derivedFrameCounter = 4
            } else {
                // PAL:

                frameIrqCounterMax = 5
                derivedFrameCounter = 0
                frameCounterTick()
            }
        }
    }

    fun resetCounter() {
        if (countSequence == 0) {
            derivedFrameCounter = 4
        } else {
            derivedFrameCounter = 0
        }
    }


    // Updates channel enable status.
    // This is done on writes to the
    // channel enable register (0x4015),
    // and when the user enables/disables channels
    // in the GUI.
    override fun updateChannelEnable(value: Int) {
        channelEnableValue = value.toShort()
        square1!!.setEnabled(userEnableSquare1 && (value and 1) != 0)
        square2!!.setEnabled(userEnableSquare2 && (value and 2) != 0)
        triangle!!.setEnabled(userEnableTriangle && (value and 4) != 0)
        noise!!.setEnabled(userEnableNoise && (value and 8) != 0)
        dmc!!.setEnabled(userEnableDmc && (value and 16) != 0)
    }

    // Clocks the frame counter. It should be clocked at
    // twice the cpu speed, so the cycles will be
    // divided by 2 for those counters that are
    // clocked at cpu speed.
    override fun clockFrameCounter(nCycles: Int) {
        var nCycles = nCycles
        if (initCounter > 0) {
            if (initingHardware) {
                initCounter -= nCycles
                if (initCounter <= 0) {
                    initingHardware = false
                }
                return
            }
        }

        // Don't process ticks beyond next sampling:
        nCycles += extraCycles
        maxCycles = sampleTimerMax - sampleTimer
        if ((nCycles shl 10) > maxCycles) {
            extraCycles = ((nCycles shl 10) - maxCycles) shr 10
            nCycles -= extraCycles
        } else {
            extraCycles = 0
        }

        // Clock DMC:
        if (dmc!!.isEnabled) {
            dmc!!.shiftCounter -= (nCycles shl 3)
            while (dmc!!.shiftCounter <= 0 && dmc!!.dmaFrequency > 0) {
                dmc!!.shiftCounter += dmc!!.dmaFrequency
                dmc!!.clockDmc(CPU.Companion.IRQ_NORMAL)
            }
        }

        // Clock Triangle channel Prog timer:
        if (triangle!!.progTimerMax > 0) {
            triangle!!.progTimerCount -= nCycles
            while (triangle!!.progTimerCount <= 0) {
                triangle!!.progTimerCount += triangle!!.progTimerMax + 1
                if (triangle!!.linearCounter > 0 && triangle!!.lengthCounter > 0) {
                    triangle!!.triangleCounter++
                    triangle!!.triangleCounter = triangle!!.triangleCounter and 0x1F

                    if (triangle!!.isEnabled) {
                        if (triangle!!.triangleCounter >= 0x10) {
                            // Normal value.
                            triangle!!.sampleValue = (triangle!!.triangleCounter and 0xF)
                        } else {
                            // Inverted value.
                            triangle!!.sampleValue = (0xF - (triangle!!.triangleCounter and 0xF))
                        }
                        triangle!!.sampleValue = triangle!!.sampleValue shl 4
                    }
                }
            }
        }

        // Clock Square channel 1 Prog timer:
        square1!!.progTimerCount -= nCycles
        if (square1!!.progTimerCount <= 0) {
            square1!!.progTimerCount += (square1!!.progTimerMax + 1) shl 1

            square1!!.squareCounter++
            square1!!.squareCounter = square1!!.squareCounter and 0x7
            square1!!.updateSampleValue()
        }

        // Clock Square channel 2 Prog timer:
        square2!!.progTimerCount -= nCycles
        if (square2!!.progTimerCount <= 0) {
            square2!!.progTimerCount += (square2!!.progTimerMax + 1) shl 1

            square2!!.squareCounter++
            square2!!.squareCounter = square2!!.squareCounter and 0x7
            square2!!.updateSampleValue()
        }

        // Clock noise channel Prog timer:
        var acc_c = nCycles
        if (noise!!.progTimerCount - acc_c > 0) {
            // Do all cycles at once:

            noise!!.progTimerCount -= acc_c
            noise!!.accCount += acc_c.toLong()
            noise!!.accValue += acc_c.toLong() * noise!!.sampleValue
        } else {
            // Slow-step:

            while ((acc_c--) > 0) {
                if (--noise!!.progTimerCount <= 0 && noise!!.progTimerMax > 0) {
                    // Update noise shift register:

                    noise!!.shiftReg = noise!!.shiftReg shl 1
                    noise!!.tmp =
                        (((noise!!.shiftReg shl (if (noise!!.randomMode == 0) 1 else 6)) xor noise!!.shiftReg) and 0x8000)
                    if (noise!!.tmp != 0) {
                        // Sample value must be 0.

                        noise!!.shiftReg = noise!!.shiftReg or 0x01
                        noise!!.randomBit = 0
                        noise!!.sampleValue = 0
                    } else {
                        // Find sample value:

                        noise!!.randomBit = 1
                        if (noise!!.isEnabled && noise!!.lengthCounter > 0) {
                            noise!!.sampleValue = noise!!.masterVolume
                        } else {
                            noise!!.sampleValue = 0
                        }
                    }

                    noise!!.progTimerCount += noise!!.progTimerMax
                }

                noise!!.accValue += noise!!.sampleValue.toLong()
                noise!!.accCount++
            }
        }


        // Frame IRQ handling:
        if (frameIrqEnabled && frameIrqActive) {
            irqRequester.requestIrq(CPU.Companion.IRQ_NORMAL)
        }

        // Clock frame counter at double CPU speed:
        masterFrameCounter += (nCycles shl 1)
        if (masterFrameCounter >= frameTime) {
            // 240Hz tick:

            masterFrameCounter -= frameTime
            frameCounterTick()
        }


        // Accumulate sample value:
        accSample(nCycles)


        // Clock sample timer:
        sampleTimer += nCycles shl 10
        if (sampleTimer >= sampleTimerMax) {
            // Sample channels:

            sample()
            sampleTimer -= sampleTimerMax
        }
    }

    private fun accSample(cycles: Int) {
        // Special treatment for triangle channel - need to interpolate.

        if (triangle!!.sampleCondition) {
            triValue = (triangle!!.progTimerCount shl 4) / (triangle!!.progTimerMax + 1)
            if (triValue > 16) {
                triValue = 16
            }
            if (triangle!!.triangleCounter >= 16) {
                triValue = 16 - triValue
            }

            // Add non-interpolated sample value:
            triValue += triangle!!.sampleValue
        }


        // Now sample normally:
        if (cycles == 2) {
            smpTriangle += triValue shl 1
            smpDmc += dmc!!.sample shl 1
            smpSquare1 += square1!!.sampleValue shl 1
            smpSquare2 += square2!!.sampleValue shl 1
            accCount += 2
        } else if (cycles == 4) {
            smpTriangle += triValue shl 2
            smpDmc += dmc!!.sample shl 2
            smpSquare1 += square1!!.sampleValue shl 2
            smpSquare2 += square2!!.sampleValue shl 2
            accCount += 4
        } else {
            smpTriangle += cycles * triValue
            smpDmc += cycles * dmc!!.sample
            smpSquare1 += cycles * square1!!.sampleValue
            smpSquare2 += cycles * square2!!.sampleValue
            accCount += cycles
        }
    }

    fun frameCounterTick() {
        derivedFrameCounter++
        if (derivedFrameCounter >= frameIrqCounterMax) {
            derivedFrameCounter = 0
        }

        if (derivedFrameCounter == 1 || derivedFrameCounter == 3) {
            // Clock length & sweep:

            triangle!!.clockLengthCounter()
            square1!!.clockLengthCounter()
            square2!!.clockLengthCounter()
            noise!!.clockLengthCounter()
            square1!!.clockSweep()
            square2!!.clockSweep()
        }

        if (derivedFrameCounter >= 0 && derivedFrameCounter < 4) {
            // Clock linear & decay:

            square1!!.clockEnvDecay()
            square2!!.clockEnvDecay()
            noise!!.clockEnvDecay()
            triangle!!.clockLinearCounter()
        }

        if (derivedFrameCounter == 3 && countSequence == 0) {
            // Enable IRQ:

            frameIrqActive = true
        }


        // End of 240Hz tick
    }


    // Samples the channels, mixes the output together,
    // writes to buffer and (if enabled) file.
    fun sample() {
        if (accCount > 0) {
            smpSquare1 = smpSquare1 shl 4
            smpSquare1 /= accCount

            smpSquare2 = smpSquare2 shl 4
            smpSquare2 /= accCount

            smpTriangle /= accCount

            smpDmc = smpDmc shl 4
            smpDmc /= accCount

            accCount = 0
        } else {
            smpSquare1 = square1!!.sampleValue shl 4
            smpSquare2 = square2!!.sampleValue shl 4
            smpTriangle = triangle!!.sampleValue
            smpDmc = dmc!!.sample shl 4
        }

        smpNoise = ((noise!!.accValue shl 4) / noise!!.accCount).toInt()
        noise!!.accValue = (smpNoise shr 4).toLong()
        noise!!.accCount = 1

        if (stereo) {
            // Stereo sound.

            // Left channel:

            sq_index = (smpSquare1 * stereoPosLSquare1 + smpSquare2 * stereoPosLSquare2) shr 8
            tnd_index =
                (3 * smpTriangle * stereoPosLTriangle + (smpNoise shl 1) * stereoPosLNoise + smpDmc * stereoPosLDMC) shr 8
            if (sq_index >= square_table.size) {
                sq_index = square_table.size - 1
            }
            if (tnd_index >= tnd_table.size) {
                tnd_index = tnd_table.size - 1
            }
            sampleValueL = square_table[sq_index] + tnd_table[tnd_index] - dcValue

            // Right channel:
            sq_index = (smpSquare1 * stereoPosRSquare1 + smpSquare2 * stereoPosRSquare2) shr 8
            tnd_index =
                (3 * smpTriangle * stereoPosRTriangle + (smpNoise shl 1) * stereoPosRNoise + smpDmc * stereoPosRDMC) shr 8
            if (sq_index >= square_table.size) {
                sq_index = square_table.size - 1
            }
            if (tnd_index >= tnd_table.size) {
                tnd_index = tnd_table.size - 1
            }
            sampleValueR = square_table[sq_index] + tnd_table[tnd_index] - dcValue
        } else {
            // Mono sound:

            sq_index = smpSquare1 + smpSquare2
            tnd_index = 3 * smpTriangle + 2 * smpNoise + smpDmc
            if (sq_index >= square_table.size) {
                sq_index = square_table.size - 1
            }
            if (tnd_index >= tnd_table.size) {
                tnd_index = tnd_table.size - 1
            }
            sampleValueL = 3 * (square_table[sq_index] + tnd_table[tnd_index] - dcValue)
            sampleValueL = sampleValueL shr 2
        }

        // Remove DC from left channel:
        smpDiffL = sampleValueL - prevSampleL
        prevSampleL += smpDiffL
        smpAccumL += smpDiffL - (smpAccumL shr 10)
        sampleValueL = smpAccumL

        if (stereo) {
            // Remove DC from right channel:

            smpDiffR = sampleValueR - prevSampleR
            prevSampleR += smpDiffR
            smpAccumR += smpDiffR - (smpAccumR shr 10)
            sampleValueR = smpAccumR

            // Write:
            if (this.bufferPos + 4 < sampleBuffer.size) {
                sampleBuffer[this.bufferPos++] = ((sampleValueL) and 0xFF).toByte()
                sampleBuffer[this.bufferPos++] = ((sampleValueL shr 8) and 0xFF).toByte()
                sampleBuffer[this.bufferPos++] = ((sampleValueR) and 0xFF).toByte()
                sampleBuffer[this.bufferPos++] = ((sampleValueR shr 8) and 0xFF).toByte()
            }
        } else {
            // Write:

            if (this.bufferPos + 2 < sampleBuffer.size) {
                sampleBuffer[this.bufferPos++] = ((sampleValueL) and 0xFF).toByte()
                sampleBuffer[this.bufferPos++] = ((sampleValueL shr 8) and 0xFF).toByte()
            }
        }
        // Reset sampled values:
        smpSquare1 = 0
        smpSquare2 = 0
        smpTriangle = 0
        smpDmc = 0
    }


    // Writes the sound buffer to the output line:
    override fun writeBuffer() {
        if (line == null) {
            return
        }
        this.bufferPos -= (this.bufferPos % (if (stereo) 4 else 2))
        line!!.write(sampleBuffer, 0, this.bufferPos)

        this.bufferPos = 0
    }

    fun stop() {
        if (line == null) {
            // No line to close. Probably lack of sound card.
            return
        }

        if (line != null && line!!.isOpen() && line!!.isActive()) {
            line!!.close()
        }

        // Lose line:
        line = null
    }

    fun reset(nes: NES) {
        setSampleRate(nes, sampleRate, false)
        updateChannelEnable(0)
        masterFrameCounter = 0
        derivedFrameCounter = 0
        countSequence = 0
        sampleCount = 0
        initCounter = 2048
        frameIrqEnabled = false
        initingHardware = false

        resetCounter()

        square1!!.reset()
        square2!!.reset()
        triangle!!.reset()
        noise!!.reset()
        dmc!!.reset()

        this.bufferPos = 0
        accCount = 0
        smpSquare1 = 0
        smpSquare2 = 0
        smpTriangle = 0
        smpNoise = 0
        smpDmc = 0

        frameIrqEnabled = false
        frameIrqCounterMax = 4

        channelEnableValue = 0xFF
        b1 = 0
        b2 = 0
        startedPlaying = false
        sampleValueL = 0
        sampleValueR = 0
        prevSampleL = 0
        prevSampleR = 0
        smpAccumL = 0
        smpAccumR = 0
        smpDiffL = 0
        smpDiffR = 0
    }

    override fun getLengthMax(value: Int): Int {
        return lengthLookup[value shr 3]
    }

    fun getDmcFrequency(value: Int): Int {
        if (value >= 0 && value < 0x10) {
            return dmcFreqLookup[value]
        }
        return 0
    }

    fun getNoiseWaveLength(value: Int): Int {
        if (value >= 0 && value < 0x10) {
            return noiseWavelengthLookup[value]
        }
        return 0
    }

    @Synchronized
    private fun setSampleRate(nes: NES, rate: Int, restart: Boolean) {
        val cpuRunning = nes.isRunning

        if (cpuRunning) {
            nes.stopEmulation()
        }

        sampleRate = rate
        sampleTimerMax = ((1024.0 * Globals.CPU_FREQ_NTSC * Globals.preferredFrameRate) /
                (sampleRate * 60.0)).toInt()

        frameTime = ((14915.0 * Globals.preferredFrameRate.toDouble()) / 60.0).toInt()

        sampleTimer = 0
        this.bufferPos = 0

        if (restart) {
            stop()
            start()
        }

        if (cpuRunning) {
            nes.startEmulation()
        }
    }

    val papuBufferSize: Int
        get() = sampleBuffer.size

    fun setChannelEnabled(channel: Int, value: Boolean) {
        if (channel == 0) {
            userEnableSquare1 = value
        } else if (channel == 1) {
            userEnableSquare2 = value
        } else if (channel == 2) {
            userEnableTriangle = value
        } else if (channel == 3) {
            userEnableNoise = value
        } else {
            userEnableDmc = value
        }
        updateChannelEnable(channelEnableValue.toInt())
    }

    // setPanning and setMasterVolume methods removed
    // Their functionality is now handled by the property setters

    fun updateStereoPos() {
        stereoPosLSquare1 = (panning[0] * masterVolume) shr 8
        stereoPosLSquare2 = (panning[1] * masterVolume) shr 8
        stereoPosLTriangle = (panning[2] * masterVolume) shr 8
        stereoPosLNoise = (panning[3] * masterVolume) shr 8
        stereoPosLDMC = (panning[4] * masterVolume) shr 8

        stereoPosRSquare1 = masterVolume - stereoPosLSquare1
        stereoPosRSquare2 = masterVolume - stereoPosLSquare2
        stereoPosRTriangle = masterVolume - stereoPosLTriangle
        stereoPosRNoise = masterVolume - stereoPosLNoise
        stereoPosRDMC = masterVolume - stereoPosLDMC
    }

    val isRunning: Boolean
        get() = (line != null && line!!.isActive())

    override fun getMillisToAvailableAbove(target_avail: Int): Int {
        var time: Double
        val cur_avail: Int
        if ((line!!.available().also { cur_avail = it }) >= target_avail) {
            return 0
        }

        time = (((target_avail - cur_avail) * 1000) / sampleRate).toDouble()
        time /= (if (stereo) 4 else 2).toDouble()

        return time.toInt()
    }

    fun initLengthLookup() {
        lengthLookup = intArrayOf(
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
    }

    fun initDmcFrequencyLookup() {
        dmcFreqLookup = IntArray(16)

        dmcFreqLookup[0x0] = 0xD60
        dmcFreqLookup[0x1] = 0xBE0
        dmcFreqLookup[0x2] = 0xAA0
        dmcFreqLookup[0x3] = 0xA00
        dmcFreqLookup[0x4] = 0x8F0
        dmcFreqLookup[0x5] = 0x7F0
        dmcFreqLookup[0x6] = 0x710
        dmcFreqLookup[0x7] = 0x6B0
        dmcFreqLookup[0x8] = 0x5F0
        dmcFreqLookup[0x9] = 0x500
        dmcFreqLookup[0xA] = 0x470
        dmcFreqLookup[0xB] = 0x400
        dmcFreqLookup[0xC] = 0x350
        dmcFreqLookup[0xD] = 0x2A0
        dmcFreqLookup[0xE] = 0x240
        dmcFreqLookup[0xF] = 0x1B0

        //for(int i=0;i<16;i++)dmcFreqLookup[i]/=8;
    }

    fun initNoiseWavelengthLookup() {
        noiseWavelengthLookup = IntArray(16)

        noiseWavelengthLookup[0x0] = 0x004
        noiseWavelengthLookup[0x1] = 0x008
        noiseWavelengthLookup[0x2] = 0x010
        noiseWavelengthLookup[0x3] = 0x020
        noiseWavelengthLookup[0x4] = 0x040
        noiseWavelengthLookup[0x5] = 0x060
        noiseWavelengthLookup[0x6] = 0x080
        noiseWavelengthLookup[0x7] = 0x0A0
        noiseWavelengthLookup[0x8] = 0x0CA
        noiseWavelengthLookup[0x9] = 0x0FE
        noiseWavelengthLookup[0xA] = 0x17C
        noiseWavelengthLookup[0xB] = 0x1FC
        noiseWavelengthLookup[0xC] = 0x2FA
        noiseWavelengthLookup[0xD] = 0x3F8
        noiseWavelengthLookup[0xE] = 0x7F2
        noiseWavelengthLookup[0xF] = 0xFE4
    }

    fun initDACtables() {
        square_table = IntArray(32 * 16)
        tnd_table = IntArray(204 * 16)
        var value: Double

        var ival: Int
        var max_sqr = 0
        var max_tnd = 0

        for (i in 0 until 32 * 16) {
            value = 95.52 / (8128.0 / (i.toDouble() / 16.0) + 100.0)
            value *= 0.98411
            value *= 50000.0
            ival = value.toInt()

            square_table[i] = ival
            if (ival > max_sqr) {
                max_sqr = ival
            }
        }

        for (i in 0 until 204 * 16) {
            value = 163.67 / (24329.0 / (i.toDouble() / 16.0) + 100.0)
            value *= 0.98411
            value *= 50000.0
            ival = value.toInt()

            tnd_table[i] = ival
            if (ival > max_tnd) {
                max_tnd = ival
            }
        }

        this.dacRange = max_sqr + max_tnd
        this.dcValue = dacRange / 2
    }

    fun destroy() {
        cpuMem = null

        if (square1 != null) {
            square1!!.destroy()
        }
        if (square2 != null) {
            square2!!.destroy()
        }
        if (triangle != null) {
            triangle!!.destroy()
        }
        if (noise != null) {
            noise!!.destroy()
        }
        if (dmc != null) {
            dmc!!.destroy()
        }

        square1 = null
        square2 = null
        triangle = null
        noise = null
        dmc = null

        mixer = null
        line = null
    }
}
