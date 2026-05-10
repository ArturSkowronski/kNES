package knes.agent.tools.save

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SaveFormatCodecTest : FunSpec({
    val sampleBytes = byteArrayOf(0x00, 0x01, 0x7f, 0x42, -1, -128)

    test("encode wraps emulator bytes as base64") {
        val save = SaveFormatCodec.encode(
            emulatorStateBytes = sampleBytes,
            rom = "ff1.nes",
            intent = "leave Coneria",
            recentMoves = emptyList(),
            decisionLog = emptyList(),
            landmarks = LandmarksSnapshot(),
            visitedMinimap = VisitedMinimap(bitsBase64 = ""),
        )
        save.rom shouldBe "ff1.nes"
        save.currentIntent shouldBe "leave Coneria"
        save.schemaVersion shouldBe 1
        (save.createdAtMs > 0) shouldBe true
        SaveFormatCodec.decodeEmulatorBytes(save).toList() shouldBe sampleBytes.toList()
    }

    test("JSON round-trip preserves emulator bytes exactly") {
        val save = SaveFormatCodec.encode(
            emulatorStateBytes = sampleBytes,
            rom = "ff1.nes",
            intent = "leave Coneria",
            recentMoves = emptyList(),
            decisionLog = emptyList(),
            landmarks = LandmarksSnapshot(),
            visitedMinimap = VisitedMinimap(bitsBase64 = ""),
        )
        val json = SaveFormatCodec.toJson(save)
        val parsed = SaveFormatCodec.fromJson(json)
        SaveFormatCodec.decodeEmulatorBytes(parsed).toList() shouldBe sampleBytes.toList()
    }

    test("schema version mismatch fails loudly") {
        val save = SaveFormatCodec.encode(
            emulatorStateBytes = sampleBytes,
            rom = "ff1.nes",
            intent = "leave Coneria",
            recentMoves = emptyList(),
            decisionLog = emptyList(),
            landmarks = LandmarksSnapshot(),
            visitedMinimap = VisitedMinimap(bitsBase64 = ""),
        )
        val json = SaveFormatCodec.toJson(save)
        val forged = json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        shouldThrow<IllegalStateException> {
            SaveFormatCodec.fromJson(forged)
        }
    }
})
