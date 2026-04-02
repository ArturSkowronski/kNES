package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MemoryTest : FunSpec({

    test("write and read single byte") {
        val mem = Memory(0x10000)
        mem.write(0x0042, 0xAB.toShort())
        mem.load(0x0042) shouldBe 0xAB.toShort()
    }

    test("write and read at address 0x0000") {
        val mem = Memory(0x10000)
        mem.write(0x0000, 0xFF.toShort())
        mem.load(0x0000) shouldBe 0xFF.toShort()
    }

    test("write and read at last address") {
        val mem = Memory(0x10000)
        mem.write(0xFFFF, 0x42.toShort())
        mem.load(0xFFFF) shouldBe 0x42.toShort()
    }

    test("reset clears all memory") {
        val mem = Memory(0x100)
        mem.write(0x00, 0xAB.toShort())
        mem.write(0x50, 0xCD.toShort())
        mem.write(0xFF, 0xEF.toShort())
        mem.reset()
        mem.load(0x00) shouldBe 0.toShort()
        mem.load(0x50) shouldBe 0.toShort()
        mem.load(0xFF) shouldBe 0.toShort()
    }

    test("write array copies data") {
        val mem = Memory(0x100)
        val data = shortArrayOf(0x11, 0x22, 0x33, 0x44)
        mem.write(0x10, data, data.size)
        mem.load(0x10) shouldBe 0x11.toShort()
        mem.load(0x11) shouldBe 0x22.toShort()
        mem.load(0x12) shouldBe 0x33.toShort()
        mem.load(0x13) shouldBe 0x44.toShort()
    }

    test("write array with offset") {
        val mem = Memory(0x100)
        val data = shortArrayOf(0x11, 0x22, 0x33, 0x44)
        mem.write(0x10, data, 1, 2)
        mem.load(0x10) shouldBe 0x22.toShort()
        mem.load(0x11) shouldBe 0x33.toShort()
    }

    test("write array does not overflow") {
        val mem = Memory(0x10)
        val data = shortArrayOf(0x11, 0x22, 0x33)
        mem.write(0x0F, data, data.size)
        mem.load(0x0F) shouldBe 0.toShort()
    }

    test("state save and load roundtrip") {
        val mem = Memory(0x100)
        mem.write(0x00, 0xAB.toShort())
        mem.write(0x42, 0xCD.toShort())
        mem.write(0xFF, 0xEF.toShort())

        val buf = ByteBuffer(0x200, ByteBuffer.BO_BIG_ENDIAN)
        mem.stateSave(buf)
        buf.goTo(0)

        val mem2 = Memory(0x100)
        mem2.stateLoad(buf)
        mem2.load(0x00) shouldBe 0xAB.toShort()
        mem2.load(0x42) shouldBe 0xCD.toShort()
        mem2.load(0xFF) shouldBe 0xEF.toShort()
    }
})
