package vnes.utils
/*
vNES
Copyright © 2006-2013 Open Emulation Project

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.awt.Color
import java.io.BufferedReader
import java.io.InputStreamReader

class PaletteTable {
    companion object {
        @JvmField
        val curTable = IntArray(64)
        
        @JvmField
        val origTable = IntArray(64)
        
        @JvmField
        val emphTable = Array(8) { IntArray(64) }
    }

    private var currentEmph = -1
    private var currentHue = 0
    private var currentSaturation = 0
    private var currentLightness = 0
    private var currentContrast = 0

    // Load the NTSC palette:
    fun loadNTSCPalette(): Boolean {
        println("PaletteTable: Loading NTSC Palette.")
        return loadPalette("palettes/ntsc.txt")
    }

    // Load the PAL palette:
    fun loadPALPalette(): Boolean {
        println("PaletteTable: Loading PAL Palette.")
        return loadPalette("palettes/pal.txt")
    }

    // Load a palette file:
    fun loadPalette(file: String): Boolean {
        try {
            if (file.lowercase().endsWith("pal")) {
                // Read binary palette file.
                val fStr = javaClass.getResourceAsStream("/$file")
                val tmp = ByteArray(64 * 3)

                var n = 0
                while (n < 64) {
                    n += fStr!!.read(tmp, n, tmp.size - n)
                }

                val tmpi = IntArray(64 * 3)
                for (i in tmp.indices) {
                    tmpi[i] = tmp[i].toInt() and 0xFF
                }

                for (i in 0 until 64) {
                    val r = tmpi[i * 3 + 0]
                    val g = tmpi[i * 3 + 1]
                    val b = tmpi[i * 3 + 2]
                    origTable[i] = r or (g shl 8) or (b shl 16)
                }
            } else {
                // Read text file with hex codes.
                val fStr = javaClass.getResourceAsStream("/$file")
                val isr = InputStreamReader(fStr!!)
                val br = BufferedReader(isr)

                var line = br.readLine()
                var palIndex = 0
                while (line != null) {
                    if (line.startsWith("#")) {
                        val hexR = line.substring(1, 3)
                        val hexG = line.substring(3, 5)
                        val hexB = line.substring(5, 7)

                        val r = Integer.decode("0x$hexR").toInt()
                        val g = Integer.decode("0x$hexG").toInt()
                        val b = Integer.decode("0x$hexB").toInt()
                        origTable[palIndex] = r or (g shl 8) or (b shl 16)

                        palIndex++
                    }
                    line = br.readLine()
                }
            }

            setEmphasis(0)
            makeTables()
            updatePalette()

            return true
        } catch (e: Exception) {
            println(e.stackTrace.toString())

            // Unable to load palette.
            println("PaletteTable: Internal Palette Loaded.")
            loadDefaultPalette()
            return false
        }
    }

    fun makeTables() {
        // Calculate a table for each possible emphasis setting:
        for (emph in 0 until 8) {
            // Determine color component factors:
            var rFactor = 1.0f
            var gFactor = 1.0f
            var bFactor = 1.0f
            
            if (emph and 1 != 0) {
                rFactor = 0.75f
                bFactor = 0.75f
            }
            if (emph and 2 != 0) {
                rFactor = 0.75f
                gFactor = 0.75f
            }
            if (emph and 4 != 0) {
                gFactor = 0.75f
                bFactor = 0.75f
            }

            // Calculate table:
            for (i in 0 until 64) {
                val col = origTable[i]
                val r = (getRed(col) * rFactor).toInt()
                val g = (getGreen(col) * gFactor).toInt()
                val b = (getBlue(col) * bFactor).toInt()
                emphTable[emph][i] = getRgb(r, g, b)
            }
        }
    }

    fun setEmphasis(emph: Int) {
        if (emph != currentEmph) {
            currentEmph = emph
            for (i in 0 until 64) {
                curTable[i] = emphTable[emph][i]
            }
            updatePalette()
        }
    }

    fun getEntry(yiq: Int): Int {
        return curTable[yiq]
    }

    fun RGBtoHSL(r: Int, g: Int, b: Int): Int {
        val hsbvals = FloatArray(3)
        Color.RGBtoHSB(b, g, r, hsbvals)
        hsbvals[0] -= Math.floor(hsbvals[0].toDouble()).toFloat()

        var ret = 0
        ret = ret or ((hsbvals[0] * 255.0).toInt() shl 16)
        ret = ret or ((hsbvals[1] * 255.0).toInt() shl 8)
        ret = ret or (hsbvals[2] * 255.0).toInt()

        return ret
    }

    fun RGBtoHSL(rgb: Int): Int {
        return RGBtoHSL(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
    }

    fun HSLtoRGB(h: Int, s: Int, l: Int): Int {
        return Color.HSBtoRGB(h / 255.0f, s / 255.0f, l / 255.0f)
    }

    fun HSLtoRGB(hsl: Int): Int {
        val h = ((hsl shr 16) and 0xFF) / 255.0f
        val s = ((hsl shr 8) and 0xFF) / 255.0f
        val l = (hsl and 0xFF) / 255.0f
        return Color.HSBtoRGB(h, s, l)
    }

    fun getHue(hsl: Int): Int {
        return (hsl shr 16) and 0xFF
    }

    fun getSaturation(hsl: Int): Int {
        return (hsl shr 8) and 0xFF
    }

    fun getLightness(hsl: Int): Int {
        return hsl and 0xFF
    }

    fun getRed(rgb: Int): Int {
        return (rgb shr 16) and 0xFF
    }

    fun getGreen(rgb: Int): Int {
        return (rgb shr 8) and 0xFF
    }

    fun getBlue(rgb: Int): Int {
        return rgb and 0xFF
    }

    fun getRgb(r: Int, g: Int, b: Int): Int {
        return ((r shl 16) or (g shl 8) or b)
    }

    fun updatePalette() {
        updatePalette(currentHue, currentSaturation, currentLightness, currentContrast)
    }

    // Change palette colors.
    // Arguments should be set to 0 to keep the original value.
    fun updatePalette(hueAdd: Int, saturationAdd: Int, lightnessAdd: Int, contrastAdd: Int) {
        var contrastAddValue = contrastAdd
        
        if (contrastAddValue > 0) {
            contrastAddValue *= 4
        }
        
        for (i in 0 until 64) {
            val hsl = RGBtoHSL(emphTable[currentEmph][i])
            var h = getHue(hsl) + hueAdd
            var s = (getSaturation(hsl) * (1.0 + saturationAdd / 256f)).toInt()
            var l = getLightness(hsl)

            if (h < 0) {
                h += 255
            }
            if (s < 0) {
                s = 0
            }
            if (l < 0) {
                l = 0
            }

            if (h > 255) {
                h -= 255
            }
            if (s > 255) {
                s = 255
            }
            if (l > 255) {
                l = 255
            }

            val rgb = HSLtoRGB(h, s, l)

            var r = getRed(rgb)
            var g = getGreen(rgb)
            var b = getBlue(rgb)

            r = 128 + lightnessAdd + ((r - 128) * (1.0 + contrastAddValue / 256f)).toInt()
            g = 128 + lightnessAdd + ((g - 128) * (1.0 + contrastAddValue / 256f)).toInt()
            b = 128 + lightnessAdd + ((b - 128) * (1.0 + contrastAddValue / 256f)).toInt()

            if (r < 0) {
                r = 0
            }
            if (g < 0) {
                g = 0
            }
            if (b < 0) {
                b = 0
            }

            if (r > 255) {
                r = 255
            }
            if (g > 255) {
                g = 255
            }
            if (b > 255) {
                b = 255
            }

            val finalRgb = getRgb(r, g, b)
            curTable[i] = finalRgb
        }

        currentHue = hueAdd
        currentSaturation = saturationAdd
        currentLightness = lightnessAdd
        currentContrast = contrastAdd
    }

    fun loadDefaultPalette() {
        origTable[0] = getRgb(124, 124, 124)
        origTable[1] = getRgb(0, 0, 252)
        origTable[2] = getRgb(0, 0, 188)
        origTable[3] = getRgb(68, 40, 188)
        origTable[4] = getRgb(148, 0, 132)
        origTable[5] = getRgb(168, 0, 32)
        origTable[6] = getRgb(168, 16, 0)
        origTable[7] = getRgb(136, 20, 0)
        origTable[8] = getRgb(80, 48, 0)
        origTable[9] = getRgb(0, 120, 0)
        origTable[10] = getRgb(0, 104, 0)
        origTable[11] = getRgb(0, 88, 0)
        origTable[12] = getRgb(0, 64, 88)
        origTable[13] = getRgb(0, 0, 0)
        origTable[14] = getRgb(0, 0, 0)
        origTable[15] = getRgb(0, 0, 0)
        origTable[16] = getRgb(188, 188, 188)
        origTable[17] = getRgb(0, 120, 248)
        origTable[18] = getRgb(0, 88, 248)
        origTable[19] = getRgb(104, 68, 252)
        origTable[20] = getRgb(216, 0, 204)
        origTable[21] = getRgb(228, 0, 88)
        origTable[22] = getRgb(248, 56, 0)
        origTable[23] = getRgb(228, 92, 16)
        origTable[24] = getRgb(172, 124, 0)
        origTable[25] = getRgb(0, 184, 0)
        origTable[26] = getRgb(0, 168, 0)
        origTable[27] = getRgb(0, 168, 68)
        origTable[28] = getRgb(0, 136, 136)
        origTable[29] = getRgb(0, 0, 0)
        origTable[30] = getRgb(0, 0, 0)
        origTable[31] = getRgb(0, 0, 0)
        origTable[32] = getRgb(248, 248, 248)
        origTable[33] = getRgb(60, 188, 252)
        origTable[34] = getRgb(104, 136, 252)
        origTable[35] = getRgb(152, 120, 248)
        origTable[36] = getRgb(248, 120, 248)
        origTable[37] = getRgb(248, 88, 152)
        origTable[38] = getRgb(248, 120, 88)
        origTable[39] = getRgb(252, 160, 68)
        origTable[40] = getRgb(248, 184, 0)
        origTable[41] = getRgb(184, 248, 24)
        origTable[42] = getRgb(88, 216, 84)
        origTable[43] = getRgb(88, 248, 152)
        origTable[44] = getRgb(0, 232, 216)
        origTable[45] = getRgb(120, 120, 120)
        origTable[46] = getRgb(0, 0, 0)
        origTable[47] = getRgb(0, 0, 0)
        origTable[48] = getRgb(252, 252, 252)
        origTable[49] = getRgb(164, 228, 252)
        origTable[50] = getRgb(184, 184, 248)
        origTable[51] = getRgb(216, 184, 248)
        origTable[52] = getRgb(248, 184, 248)
        origTable[53] = getRgb(248, 164, 192)
        origTable[54] = getRgb(240, 208, 176)
        origTable[55] = getRgb(252, 224, 168)
        origTable[56] = getRgb(248, 216, 120)
        origTable[57] = getRgb(216, 248, 120)
        origTable[58] = getRgb(184, 248, 184)
        origTable[59] = getRgb(184, 248, 216)
        origTable[60] = getRgb(0, 252, 252)
        origTable[61] = getRgb(216, 216, 16)
        origTable[62] = getRgb(0, 0, 0)
        origTable[63] = getRgb(0, 0, 0)

        setEmphasis(0)
        makeTables()
    }

    fun reset() {
        currentEmph = 0
        currentHue = 0
        currentSaturation = 0
        currentLightness = 0
        setEmphasis(0)
        updatePalette()
    }
}
