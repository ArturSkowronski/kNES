package knes.emulator.utils

class HiResTimer {
    fun currentMicros(): Long {
        return System.nanoTime() / 1000
    }

    fun currentTick(): Long {
        return System.nanoTime()
    }

    fun sleepMicros(time: Long) {
        try {
            var nanos = time - (time / 1000) * 1000
            if (nanos > 999999) {
                nanos = 999999
            }
            Thread.sleep(time / 1000, nanos.toInt())
        } catch (e: Exception) {
            //System.out.println("Sleep interrupted..");

            e.printStackTrace()
        }
    }

    fun sleepMillisIdle(millis: Int) {
        var millis = millis
        millis /= 10
        millis *= 10

        try {
            Thread.sleep(millis.toLong())
        } catch (ie: InterruptedException) {
        }
    }

    fun yield() {
        Thread.yield()
    }
}