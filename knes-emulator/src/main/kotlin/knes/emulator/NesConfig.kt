package knes.emulator

import knes.emulator.utils.Globals

/**
 * Per-instance runtime configuration for one [NES].
 *
 * These used to live on the [Globals] singleton, which meant two emulators in one JVM
 * could not disagree about region or sound — a problem for parallel agent runs and for
 * tests, whose result depended on what an earlier test had left in the singleton.
 *
 * [enableSound] is a `var` because the audio device can fail at runtime and the APU has
 * to turn itself off; everything else is fixed for the life of the instance.
 *
 * `Globals.memoryFlushValue` is deliberately not carried over: the applet sets it, but
 * nothing in the emulator ever reads it. Moving it here would have implied it does
 * something.
 */
data class NesConfig(
    val palEmulation: Boolean = false,
    var enableSound: Boolean = true,
    /** Sleep between frames to hold real-time speed. Off means run as fast as possible. */
    val timeEmulation: Boolean = true,
    val disableSprites: Boolean = false,
    val preferredFrameRate: Int = 60,
) {
    /** Microseconds per frame. */
    val frameTime: Int get() = 1_000_000 / preferredFrameRate

    companion object {
        /** Headless: no audio device, no frame pacing. */
        val HEADLESS = NesConfig(enableSound = false, timeEmulation = false)

        /**
         * Reads the legacy [Globals] singleton, for hosts that still configure through it.
         * New code should build a [NesConfig] and pass it to [NES] instead.
         */
        fun fromGlobals() = NesConfig(
            palEmulation = Globals.palEmulation,
            enableSound = Globals.enableSound,
            timeEmulation = Globals.timeEmulation,
            disableSprites = Globals.disableSprites,
            preferredFrameRate = Globals.preferredFrameRate,
        )
    }
}
