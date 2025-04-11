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