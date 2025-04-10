package knes.emulator

import kotlin.math.sin

class BlipBuffer {
    // These values must be set:
    var win_size: Int = 0
    var smp_period: Int = 0
    var sinc_periods: Int = 0

    // Different samplings of bandlimited impulse:
    lateinit var imp: Array<IntArray?>

    // Difference buffer:
    lateinit var diff: IntArray

    // Last position changed in buffer:
    var lastChanged: Int = 0

    // Previous end absolute value:
    var prevSum: Int = 0

    // DC removal:
    var dc_prev: Int = 0
    var dc_diff: Int = 0
    var dc_acc: Int = 0

    fun init(bufferSize: Int, windowSize: Int, samplePeriod: Int, sincPeriods: Int) {
        win_size = windowSize
        smp_period = samplePeriod
        sinc_periods = sincPeriods
        val buf = DoubleArray(smp_period * win_size)


        // Sample sinc:
        val si_p = sinc_periods.toDouble()
        for (i in buf.indices) {
            buf[i] = sinc(-si_p * Math.PI + (si_p * 2.0 * (i.toDouble()) * Math.PI) / (buf.size.toDouble()))
        }

        // Fill into impulse buffer:
        imp = Array<IntArray?>(smp_period) { IntArray(win_size) }
        for (off in 0 until smp_period) {
            var sum = 0.0
            for (i in 0 until win_size) {
                sum += 32768.0 * buf[i * smp_period + off]
                imp[smp_period - 1 - off]!![i] = sum.toInt()
            }
        }

        // Create difference buffer:
        diff = IntArray(bufferSize)
        lastChanged = 0
        prevSum = 0
        dc_prev = 0
        dc_diff = 0
        dc_acc = 0
    }

    fun impulse(smpPos: Int, smpOffset: Int, magnitude: Int) {
        // Add into difference buffer:
        //if(smpPos+win_size < diff.length){

        for (i in lastChanged until smpPos + win_size) {
            diff[i] = prevSum
        }
        for (i in 0 until win_size) {
            diff[smpPos + i] += (imp[smpOffset]!![i] * magnitude) shr 8
        }
        lastChanged = smpPos + win_size
        prevSum = diff[smpPos + win_size - 1]

        //}
    }

    fun integrate(): Int {
        var sum = prevSum
        for (i in diff.indices) {
            sum += diff[i]

            // Remove DC:
            dc_diff = sum - dc_prev
            dc_prev += dc_diff
            dc_acc += dc_diff - (dc_acc shr 10)
            diff[i] = dc_acc
        }
        prevSum = sum
        return lastChanged
    }

    fun clear() {
        for (i in diff.indices) {
            diff[i] = 0
        }
        lastChanged = 0
    }

    companion object {
        fun sinc(x: Double): Double {
            if (x == 0.0) {
                return 1.0
            }
            return sin(x) / x
        }
    }
}