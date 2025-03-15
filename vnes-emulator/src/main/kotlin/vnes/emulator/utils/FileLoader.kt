package vnes.emulator.utils

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.function.Consumer
import java.util.zip.ZipInputStream

class FileLoader {
    // Load a file.
    fun loadFile(fileName: String, loadProgress: Consumer<Int>): ShortArray? {
        val flen: Int
        var tmp = ByteArray(2048)

        // Read file:
        try {
            var `in`: InputStream?
            `in` = javaClass.getResourceAsStream(fileName)

            if (`in` == null) {
                // Try another approach.

                `in` = FileInputStream(fileName)
                if (`in` == null) {
                    throw IOException("Unable to load " + fileName)
                }
            }
            val zis: ZipInputStream? = null
            val zip = false

            var pos = 0
            var readbyte = 0

            if (`in` !is FileInputStream) {
                val total: Long = -1

                var progress: Long = -1
                while (readbyte != -1) {
                    readbyte = if (zip) zis!!.read(tmp, pos, tmp.size - pos) else `in`.read(tmp, pos, tmp.size - pos)
                    if (readbyte != -1) {
                        if (pos >= tmp.size) {
                            val newtmp = ByteArray(tmp.size + 32768)
                            for (i in tmp.indices) {
                                newtmp[i] = tmp[i]
                            }
                            tmp = newtmp
                        }
                        pos += readbyte
                    }

                    if (total > 0 && ((pos * 100) / total) > progress) {
                        progress = (pos * 100) / total
                        if (loadProgress != null) {
                            loadProgress.accept(progress.toInt())
                        }
                    }
                }
            } else {
                // This is easy, can find the file size since it's
                // in the local file system.

                val f = File(fileName)
                var count = 0
                val total = (f.length()).toInt()
                tmp = ByteArray(total)
                while (count < total) {
                    count += `in`.read(tmp, count, total - count)
                }
                pos = total
            }

            // Put into array without any padding:
            val newtmp = ByteArray(pos)
            for (i in 0 until pos) {
                newtmp[i] = tmp[i]
            }
            tmp = newtmp

            // File size:
            flen = tmp.size
        } catch (ioe: IOException) {
            // Something went wrong.

            ioe.printStackTrace()
            return null
        }

        val ret = ShortArray(flen)
        for (i in 0 until flen) {
            ret[i] = (tmp[i].toInt() and 255).toShort()
        }
        return ret
    }
}