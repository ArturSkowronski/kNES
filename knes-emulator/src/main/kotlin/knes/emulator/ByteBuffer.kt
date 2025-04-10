package knes.emulator
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

import java.io.*
import java.util.zip.*

class ByteBuffer {
    companion object {
        @JvmField
        val DEBUG = false

        @JvmField
        val BO_BIG_ENDIAN = 0

        @JvmField
        val BO_LITTLE_ENDIAN = 1

        @JvmStatic
        fun asciiEncode(buf: ByteBuffer): ByteBuffer {
            val data = buf.buf
            val enc = ByteArray(buf.getSize() * 2)

            var encpos = 0
            var tmp: Int
            for (i in data.indices) {
                tmp = data[i].toInt()
                enc[encpos] = (65 + (tmp and 0xF)).toByte()
                enc[encpos + 1] = (65 + (tmp shr 4 and 0xF)).toByte()
                encpos += 2
            }
            return ByteBuffer(enc, BO_BIG_ENDIAN)
        }

        @JvmStatic
        fun asciiDecode(@Suppress("UNUSED_PARAMETER") buf: ByteBuffer): ByteBuffer? {
            // This method is not implemented in the original Java code
            return null
        }

        @JvmStatic
        fun saveToZipFile(f: File, buf: ByteBuffer) {
            try {
                val fOut = FileOutputStream(f)
                val zipOut = ZipOutputStream(fOut)
                zipOut.putNextEntry(ZipEntry("contents"))
                zipOut.write(buf.getBytes())
                zipOut.closeEntry()
                zipOut.close()
                fOut.close()
                //System.out.println("Buffer was successfully saved to "+f.getPath());
            } catch (e: Exception) {
                //System.out.println("Unable to save buffer to file "+f.getPath());
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun readFromZipFile(f: File): ByteBuffer? {
            try {
                val `in` = FileInputStream(f)
                val zipIn = ZipInputStream(`in`)
                val zip = ZipFile(f)
                val entry = zip.getEntry("contents")
                val len = entry.size.toInt()
                //System.out.println("Len = "+len);

                var curlen = 0
                val buf = ByteArray(len)
                zipIn.nextEntry
                while (curlen < len) {
                    val read = zipIn.read(buf, curlen, len - curlen)
                    if (read >= 0) {
                        curlen += read
                    } else {
                        // end of file.
                        break
                    }
                }
                zipIn.closeEntry()
                zipIn.close()
                `in`.close()
                zip.close()
                return ByteBuffer(buf, BO_BIG_ENDIAN)
            } catch (e: Exception) {
                //System.out.println("Unable to load buffer from file "+f.getPath());
                e.printStackTrace()
            }

            // fail:
            return null
        }
    }

    private var byteOrder = BO_BIG_ENDIAN
    private var buf: ShortArray
    private var size: Int
    private var curPos: Int = 0
    private var hasBeenErrors: Boolean = false
    private var expandable = true
    private var expandBy = 4096

    constructor(size: Int, byteOrdering: Int) {
        var adjustedSize = size
        if (adjustedSize < 1) {
            adjustedSize = 1
        }
        buf = ShortArray(adjustedSize)
        this.size = adjustedSize
        this.byteOrder = byteOrdering
    }

    constructor(content: ByteArray, byteOrdering: Int) {
        try {
            buf = ShortArray(content.size)
            for (i in content.indices) {
                buf[i] = (content[i].toInt() and 255).toShort()
            }
            size = content.size
            this.byteOrder = byteOrdering
        } catch (e: Exception) {
            // Initialize with defaults in case of exception
            buf = ShortArray(1)
            size = 1
            //System.out.println("ByteBuffer: Couldn't create buffer from empty array.");
        }
    }

    fun setExpandable(exp: Boolean) {
        expandable = exp
    }

    fun setExpandBy(expBy: Int) {
        if (expBy > 1024) {
            this.expandBy = expBy
        }
    }

    fun setByteOrder(byteOrder: Int) {
        if (byteOrder >= 0 && byteOrder < 2) {
            this.byteOrder = byteOrder
        }
    }

    fun getBytes(): ByteArray {
        val ret = ByteArray(buf.size)
        for (i in buf.indices) {
            ret[i] = buf[i].toByte()
        }
        return ret
    }

    fun getSize(): Int {
        return this.size
    }

    fun getPos(): Int {
        return curPos
    }

    private fun error() {
        hasBeenErrors = true
        //System.out.println("Not in range!");
    }

    fun hasHadErrors(): Boolean {
        return hasBeenErrors
    }

    fun clear() {
        for (i in buf.indices) {
            buf[i] = 0
        }
        curPos = 0
    }

    fun fill(value: Byte) {
        for (i in 0 until size) {
            buf[i] = value.toShort()
        }
    }

    fun fillRange(start: Int, length: Int, value: Byte): Boolean {
        if (inRange(start, length)) {
            for (i in start until (start + length)) {
                buf[i] = value.toShort()
            }
            return true
        } else {
            error()
            return false
        }
    }

    fun resize(length: Int) {
        val newbuf = ShortArray(length)
        System.arraycopy(buf, 0, newbuf, 0, Math.min(length, size))
        buf = newbuf
        size = length
    }

    fun resizeToCurrentPos() {
        resize(curPos)
    }

    fun expand() {
        expand(expandBy)
    }

    fun expand(byHowMuch: Int) {
        resize(size + byHowMuch)
    }

    fun goTo(position: Int) {
        if (inRange(position)) {
            curPos = position
        } else {
            error()
        }
    }

    fun move(howFar: Int) {
        curPos += howFar
        if (!inRange(curPos)) {
            curPos = size - 1
        }
    }

    fun inRange(pos: Int): Boolean {
        if (pos >= 0 && pos < size) {
            return true
        } else {
            if (expandable) {
                expand(Math.max(pos + 1 - size, expandBy))
                return true
            } else {
                return false
            }
        }
    }

    fun inRange(pos: Int, length: Int): Boolean {
        if (pos >= 0 && pos + (length - 1) < size) {
            return true
        } else {
            if (expandable) {
                expand(Math.max(pos + length - size, expandBy))
                return true
            } else {
                return false
            }
        }
    }

    fun putBoolean(b: Boolean): Boolean {
        val ret = putBoolean(b, curPos)
        move(1)
        return ret
    }

    fun putBoolean(b: Boolean, pos: Int): Boolean {
        return if (b) {
            putByte(1, pos)
        } else {
            putByte(0, pos)
        }
    }

    fun putByte(var1: Short): Boolean {
        if (inRange(curPos, 1)) {
            buf[curPos] = var1
            move(1)
            return true
        } else {
            error()
            return false
        }
    }

    fun putByte(var1: Int): Boolean {
        return putByte(var1.toShort())
    }

    fun putByte(var1: Short, pos: Int): Boolean {
        if (inRange(pos, 1)) {
            buf[pos] = var1
            return true
        } else {
            error()
            return false
        }
    }

    fun putByte(var1: Int, pos: Int): Boolean {
        return putByte(var1.toShort(), pos)
    }

    fun putShort(var1: Short): Boolean {
        val ret = putShort(var1, curPos)
        if (ret) {
            move(2)
        }
        return ret
    }

    fun putShort(var1: Short, pos: Int): Boolean {
        if (inRange(pos, 2)) {
            if (this.byteOrder == BO_BIG_ENDIAN) {
                buf[pos + 0] = ((var1.toInt() shr 8) and 255).toShort()
                buf[pos + 1] = ((var1.toInt()) and 255).toShort()
            } else {
                buf[pos + 1] = ((var1.toInt() shr 8) and 255).toShort()
                buf[pos + 0] = ((var1.toInt()) and 255).toShort()
            }
            return true
        } else {
            error()
            return false
        }
    }

    fun putInt(var1: Int): Boolean {
        val ret = putInt(var1, curPos)
        if (ret) {
            move(4)
        }
        return ret
    }

    fun putInt(var1: Int, pos: Int): Boolean {
        if (inRange(pos, 4)) {
            if (this.byteOrder == BO_BIG_ENDIAN) {
                buf[pos + 0] = ((var1 shr 24) and 255).toShort()
                buf[pos + 1] = ((var1 shr 16) and 255).toShort()
                buf[pos + 2] = ((var1 shr 8) and 255).toShort()
                buf[pos + 3] = ((var1) and 255).toShort()
            } else {
                buf[pos + 3] = ((var1 shr 24) and 255).toShort()
                buf[pos + 2] = ((var1 shr 16) and 255).toShort()
                buf[pos + 1] = ((var1 shr 8) and 255).toShort()
                buf[pos + 0] = ((var1) and 255).toShort()
            }
            return true
        } else {
            error()
            return false
        }
    }

    fun putString(var1: String): Boolean {
        val ret = putString(var1, curPos)
        if (ret) {
            move(2 * var1.length)
        }
        return ret
    }

    fun putString(var1: String, pos: Int): Boolean {
        val charArr = var1.toCharArray()
        if (inRange(pos, var1.length * 2)) {
            var position = pos
            for (i in var1.indices) {
                buf[position + 0] = ((charArr[i].code shr 8) and 255).toShort()
                buf[position + 1] = ((charArr[i].code) and 255).toShort()
                position += 2
            }
            return true
        } else {
            error()
            return false
        }
    }

    fun putChar(var1: Char): Boolean {
        val ret = putChar(var1, curPos)
        if (ret) {
            move(2)
        }
        return ret
    }

    fun putChar(var1: Char, pos: Int): Boolean {
        val tmp = var1.code
        if (inRange(pos, 2)) {
            if (byteOrder == BO_BIG_ENDIAN) {
                buf[pos + 0] = ((tmp shr 8) and 255).toShort()
                buf[pos + 1] = ((tmp) and 255).toShort()
            } else {
                buf[pos + 1] = ((tmp shr 8) and 255).toShort()
                buf[pos + 0] = ((tmp) and 255).toShort()
            }
            return true
        } else {
            error()
            return false
        }
    }

    fun putCharAscii(var1: Char): Boolean {
        val ret = putCharAscii(var1, curPos)
        if (ret) {
            move(1)
        }
        return ret
    }

    fun putCharAscii(var1: Char, pos: Int): Boolean {
        if (inRange(pos)) {
            buf[pos] = var1.code.toShort()
            return true
        } else {
            error()
            return false
        }
    }

    fun putStringAscii(var1: String): Boolean {
        val ret = putStringAscii(var1, curPos)
        if (ret) {
            move(var1.length)
        }
        return ret
    }

    fun putStringAscii(var1: String, pos: Int): Boolean {
        val charArr = var1.toCharArray()
        if (inRange(pos, var1.length)) {
            var position = pos
            for (i in var1.indices) {
                buf[position] = charArr[i].code.toShort()
                position++
            }
            return true
        } else {
            error()
            return false
        }
    }

    fun putByteArray(arr: ShortArray): Boolean {
        if (buf.size - curPos < arr.size) {
            resize(curPos + arr.size)
        }
        for (i in arr.indices) {
            buf[curPos + i] = arr[i]
        }
        curPos += arr.size
        return true
    }

    fun readByteArray(arr: ShortArray): Boolean {
        if (buf.size - curPos < arr.size) {
            return false
        }
        for (i in arr.indices) {
            arr[i] = (buf[curPos + i].toInt() and 0xFF).toShort()
        }
        curPos += arr.size
        return true
    }

    fun putShortArray(arr: ShortArray): Boolean {
        if (buf.size - curPos < arr.size * 2) {
            resize(curPos + arr.size * 2)
        }
        if (byteOrder == BO_BIG_ENDIAN) {
            for (i in arr.indices) {
                buf[curPos + 0] = ((arr[i].toInt() shr 8) and 255).toShort()
                buf[curPos + 1] = ((arr[i].toInt()) and 255).toShort()
                curPos += 2
            }
        } else {
            for (i in arr.indices) {
                buf[curPos + 1] = ((arr[i].toInt() shr 8) and 255).toShort()
                buf[curPos + 0] = ((arr[i].toInt()) and 255).toShort()
                curPos += 2
            }
        }
        return true
    }

    override fun toString(): String {
        val strBuf = StringBuffer()
        var tmp: Short
        for (i in 0 until (size - 1) step 2) {
            tmp = ((buf[i].toInt() shl 8) or (buf[i + 1].toInt())).toShort()
            strBuf.append(tmp.toInt().toChar())
        }
        return strBuf.toString()
    }

    fun toStringAscii(): String {
        val strBuf = StringBuffer()
        for (i in 0 until size) {
            strBuf.append(buf[i].toInt().toChar())
        }
        return strBuf.toString()
    }

    fun readBoolean(): Boolean {
        val ret = readBoolean(curPos)
        move(1)
        return ret
    }

    fun readBoolean(pos: Int): Boolean {
        return readByte(pos) == 1.toShort()
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readByte(): Short {
        val ret = readByte(curPos)
        move(1)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readByte(pos: Int): Short {
        if (inRange(pos)) {
            return buf[pos]
        } else {
            error()
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readShort(): Short {
        val ret = readShort(curPos)
        move(2)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readShort(pos: Int): Short {
        if (inRange(pos, 2)) {
            return if (this.byteOrder == BO_BIG_ENDIAN) {
                ((buf[pos].toInt() shl 8) or (buf[pos + 1].toInt())).toShort()
            } else {
                ((buf[pos + 1].toInt() shl 8) or (buf[pos].toInt())).toShort()
            }
        } else {
            error()
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readInt(): Int {
        val ret = readInt(curPos)
        move(4)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readInt(pos: Int): Int {
        var ret = 0
        if (inRange(pos, 4)) {
            if (this.byteOrder == BO_BIG_ENDIAN) {
                ret = ret or (buf[pos + 0].toInt() shl 24)
                ret = ret or (buf[pos + 1].toInt() shl 16)
                ret = ret or (buf[pos + 2].toInt() shl 8)
                ret = ret or (buf[pos + 3].toInt())
            } else {
                ret = ret or (buf[pos + 3].toInt() shl 24)
                ret = ret or (buf[pos + 2].toInt() shl 16)
                ret = ret or (buf[pos + 1].toInt() shl 8)
                ret = ret or (buf[pos + 0].toInt())
            }
            return ret
        } else {
            error()
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readChar(): Char {
        val ret = readChar(curPos)
        move(2)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readChar(pos: Int): Char {
        if (inRange(pos, 2)) {
            return readShort(pos).toInt().toChar()
        } else {
            error()
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readCharAscii(): Char {
        val ret = readCharAscii(curPos)
        move(1)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readCharAscii(pos: Int): Char {
        if (inRange(pos, 1)) {
            return (readByte(pos).toInt() and 255).toChar()
        } else {
            error()
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readString(length: Int): String {
        return if (length > 0) {
            val ret = readString(curPos, length)
            move(ret.length * 2)
            ret
        } else {
            ""
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readString(pos: Int, length: Int): String {
        if (inRange(pos, length * 2) && length > 0) {
            val tmp = CharArray(length)
            for (i in 0 until length) {
                tmp[i] = readChar(pos + i * 2)
            }
            return String(tmp)
        } else {
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readStringWithShortLength(): String {
        val ret = readStringWithShortLength(curPos)
        move(ret.length * 2 + 2)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readStringWithShortLength(pos: Int): String {
        if (inRange(pos, 2)) {
            val len = readShort(pos).toInt()
            return if (len > 0) {
                readString(pos + 2, len)
            } else {
                ""
            }
        } else {
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readStringAscii(length: Int): String {
        val ret = readStringAscii(curPos, length)
        move(ret.length)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readStringAscii(pos: Int, length: Int): String {
        if (inRange(pos, length) && length > 0) {
            val tmp = CharArray(length)
            for (i in 0 until length) {
                tmp[i] = readCharAscii(pos + i)
            }
            return String(tmp)
        } else {
            throw ArrayIndexOutOfBoundsException()
        }
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readStringAsciiWithShortLength(): String {
        val ret = readStringAsciiWithShortLength(curPos)
        move(ret.length + 2)
        return ret
    }

    @Throws(ArrayIndexOutOfBoundsException::class)
    fun readStringAsciiWithShortLength(pos: Int): String {
        if (inRange(pos, 2)) {
            val len = readShort(pos).toInt()
            return if (len > 0) {
                readStringAscii(pos + 2, len)
            } else {
                ""
            }
        } else {
            throw ArrayIndexOutOfBoundsException()
        }
    }

    private fun expandShortArray(array: ShortArray, size: Int): ShortArray {
        val newArr = ShortArray(array.size + size)
        if (size > 0) {
            System.arraycopy(array, 0, newArr, 0, array.size)
        } else {
            System.arraycopy(array, 0, newArr, 0, newArr.size)
        }
        return newArr
    }

    fun crop() {
        if (curPos > 0) {
            if (curPos < buf.size) {
                val newBuf = ShortArray(curPos)
                System.arraycopy(buf, 0, newBuf, 0, curPos)
                buf = newBuf
            }
        } else {
            //System.out.println("Could not crop buffer, as the current position is 0. The buffer may not be empty.");
        }
    }
}
