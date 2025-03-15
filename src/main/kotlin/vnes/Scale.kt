package vnes

object Scale {
    private var brightenShift = 0
    private var brightenShiftMask = 0
    private var brightenCutoffMask = 0
    private var darkenShift = 0
    private var darkenShiftMask = 0
    private const val si = 0
    private const val di = 0
    private const val di2 = 0
    private const val `val` = 0
    private const val x = 0
    private const val y = 0

    fun setFilterParams(darkenDepth: Int, brightenDepth: Int) {
        when (darkenDepth) {
            0 -> {
                darkenShift = 0
                darkenShiftMask = 0x00000000
            }

            1 -> {
                darkenShift = 4
                darkenShiftMask = 0x000F0F0F
            }

            2 -> {
                darkenShift = 3
                darkenShiftMask = 0x001F1F1F
            }

            3 -> {
                darkenShift = 2
                darkenShiftMask = 0x003F3F3F
            }

            else -> {
                darkenShift = 1
                darkenShiftMask = 0x007F7F7F
            }
        }

        when (brightenDepth) {
            0 -> {
                brightenShift = 0
                brightenShiftMask = 0x00000000
                brightenCutoffMask = 0x00000000
            }

            1 -> {
                brightenShift = 4
                brightenShiftMask = 0x000F0F0F
                brightenCutoffMask = 0x003F3F3F
            }

            2 -> {
                brightenShift = 3
                brightenShiftMask = 0x001F1F1F
                brightenCutoffMask = 0x003F3F3F
            }

            3 -> {
                brightenShift = 2
                brightenShiftMask = 0x003F3F3F
                brightenCutoffMask = 0x007F7F7F
            }

            else -> {
                brightenShift = 1
                brightenShiftMask = 0x007F7F7F
                brightenCutoffMask = 0x007F7F7F
            }
        }
    }

    @JvmStatic
    fun doScanlineScaling(src: IntArray, dest: IntArray, changed: BooleanArray) {
        var di = 0
        var di2 = 512
        var `val`: Int
        var max: Int

        for (y in 0..239) {
            if (changed[y]) {
                max = (y + 1) shl 8
                for (si in y shl 8 until max) {
                    // get pixel value:

                    `val` = src[si]

                    // fill the two pixels on the current scanline:
                    dest[di] = `val`
                    dest[++di] = `val`

                    // darken pixel:
                    `val` -= ((`val` shr 2) and 0x003F3F3F)

                    // fill the two pixels on the next scanline:
                    dest[di2] = `val`
                    dest[++di2] = `val`

                    //si ++;
                    di++
                    di2++
                }
            } else {
                di += 512
                di2 += 512
            }

            // skip one scanline:
            di += 512
            di2 += 512
        }
    }

    @JvmStatic
    fun doRasterScaling(src: IntArray, dest: IntArray, changed: BooleanArray) {
        var di = 0
        var di2 = 512

        var max: Int
        var col1: Int
        var col2: Int
        var col3: Int
        var flag = 0

        for (y in 0..239) {
            if (changed[y]) {
                max = (y + 1) shl 8
                for (si in y shl 8 until max) {
                    // get pixel value:

                    col1 = src[si]

                    // fill the two pixels on the current scanline:
                    dest[di] = col1
                    dest[++di] = col1

                    // fill the two pixels on the next scanline:
                    dest[di2] = col1
                    dest[++di2] = col1

                    // darken pixel:
                    col2 = col1 - ((col1 shr darkenShift) and darkenShiftMask)

                    // brighten pixel:
                    col3 = col1 +
                            ((((0x00FFFFFF - col1) and brightenCutoffMask) shr brightenShift) and brightenShiftMask)

                    dest[di + (512 and flag)] = col2
                    dest[di + (512 and flag) - 1] = col2
                    dest[di + 512 and (512 - flag)] = col3
                    flag = 512 - flag

                    di++
                    di2++
                }
            } else {
                di += 512
                di2 += 512
            }

            // skip one scanline:
            di += 512
            di2 += 512
        }
    }

    @JvmStatic
    fun doNormalScaling(src: IntArray, dest: IntArray, changed: BooleanArray) {
        var di = 0
        var di2 = 512
        var `val`: Int
        var max: Int

        for (y in 0..239) {
            if (changed[y]) {
                max = (y + 1) shl 8
                for (si in y shl 8 until max) {
                    // get pixel value:

                    `val` = src[si]

                    // fill the two pixels on the current scanline:
                    dest[di++] = `val`
                    dest[di++] = `val`

                    // fill the two pixels on the next scanline:
                    dest[di2++] = `val`
                    dest[di2++] = `val`
                }
            } else {
                di += 512
                di2 += 512
            }

            // skip one scanline:
            di += 512
            di2 += 512
        }
    }
}