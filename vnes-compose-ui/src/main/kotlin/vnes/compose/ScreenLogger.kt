package vnes.compose

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

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Utility class for logging screen-related information and debug data.
 */
object ScreenLogger {

    /**
     * Logs the current frame image to a file.
     * 
     * @param image The BufferedImage to log
     * @param filename The name of the file to save (default: "frame.jpg")
     * @param directory The directory to save the file in (default: "debug")
     */
    fun logFrameImage(image: BufferedImage, filename: String = "frame.jpg", directory: String = "debug") {
        try {
            // Create a debug directory in the current working directory
            val debugDir = File(directory)
            if (!debugDir.exists()) {
                debugDir.mkdir()
            }
            val outputFile = File(debugDir, filename)
            ImageIO.write(image, "jpg", outputFile)
            println("[DEBUG] Image written to ${outputFile.absoluteFile}")
        } catch (e: Exception) {
            println("[DEBUG] Error writing image to file: ${e.message}")
        }
    }

    /**
     * Logs color information from the buffer.
     * 
     * @param topColors The list of top colors to log
     * @param previousTopColors The list of previous top colors for comparison
     * @return True if the top colors have changed, false otherwise
     */
    fun logColorChanges(
        topColors: List<Map.Entry<Int, Int>>, 
        previousTopColors: List<Map.Entry<Int, Int>>
    ): Boolean {
        // Check if the top colors have changed
        var topColorsChanged = false
        if (topColors.size != previousTopColors.size) {
            topColorsChanged = true
        } else {
            for (i in topColors.indices) {
                if (i >= previousTopColors.size) {
                    topColorsChanged = true
                    break
                }
                val current = topColors[i]
                val previous = previousTopColors[i]
                if (current.key != previous.key || current.value != previous.value) {
                    topColorsChanged = true
                    break
                }
            }
        }

        // Log if colors have changed
        if (topColorsChanged) {
            println("[DEBUG] Top colors changed:")
            topColors.forEachIndexed { index, entry ->
                val color = entry.key
                val count = entry.value
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                println("[DEBUG] Color $index: RGB($r,$g,$b) - Count: $count")
            }

            // More detailed logging in ComposeScreenView format
            println("======================")
            println("[ComposeScreenView] Top 5 colors in buffer (sorted by color):")
            topColors.forEach { (color, count) ->
                println("[ComposeScreenView] 0x${color.toString(16).uppercase()} : $count")
            }
        }

        return topColorsChanged
    }

    /**
     * Visualizes the buffer in the terminal.
     * 
     * @param buffer The buffer to visualize
     * @param width The width of the buffer
     * @param height The height of the buffer
     * @param topColors The list of top colors in the buffer
     */
    fun visualizeBufferInTerminal(buffer: IntArray, width: Int, height: Int, topColors: List<Map.Entry<Int, Int>>) {
        println("======================")
        println("[ComposeScreenView] Buffer visualization:")

        // Create a map of colors to ASCII characters for visualization
        val colorToChar = mutableMapOf<Int, Char>()
        val chars = listOf('#', '@', '*', '+', '.', ' ') // Characters to represent different colors

        // Assign characters to the top colors
        topColors.forEachIndexed { index, entry ->
            colorToChar[entry.key] = chars[index.coerceAtMost(chars.size - 1)]
        }

        // Default character for colors not in the top 5
        val defaultChar = ' '

        // Draw the buffer line by line
        for (y in 0 until height) {
            val line = StringBuilder()
            for (x in 0 until width) {
                val pixel = buffer[y * width + x]
                val char = colorToChar[pixel] ?: defaultChar
                line.append(char)
            }
            // Print every 8th line to reduce output volume
            if (y % 8 == 0) {
                println(line.toString())
            }
        }
        println("======================")
    }
}
