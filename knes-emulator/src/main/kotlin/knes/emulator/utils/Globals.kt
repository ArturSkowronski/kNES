package knes.emulator.utils

object Globals {
    @JvmField
    var CPU_FREQ_NTSC: Double = 1789772.5
    var CPU_FREQ_PAL: Double = 1773447.4
    @JvmField
    var preferredFrameRate: Int = 60

    // Microseconds per frame:
    @JvmField
    var frameTime: Int = 1000000 / preferredFrameRate

    // What value to flush memory with on power-up:
    @JvmField
    var memoryFlushValue: Short = 0xFF

    const val debug: Boolean = true
    const val fsdebug: Boolean = false

    @JvmField
    var appletMode: Boolean = true
    @JvmField
    var disableSprites: Boolean = false
    @JvmField
    var timeEmulation: Boolean = true
    @JvmField
    var palEmulation: Boolean = false
    @JvmField
    var enableSound: Boolean = true
    @JvmField
    var focused: Boolean = false

    @JvmField
    var keycodes: HashMap<String, Int> = HashMap<String, Int>() //Java key codes
    @JvmField
    var controls: HashMap<String, String> = HashMap<String, String>() //vNES controls codes
}
