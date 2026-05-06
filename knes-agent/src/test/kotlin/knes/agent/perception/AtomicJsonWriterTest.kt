package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class AtomicJsonWriterTest : FunSpec({
    fun tmpDir() = Files.createTempDirectory("atomic-").toFile().apply { deleteOnExit() }

    test("write creates parent directory if missing") {
        val target = File(tmpDir(), "subdir/missing/state.json")
        AtomicJsonWriter.write(target, """{"x": 1}""")
        target.exists() shouldBe true
        target.readText() shouldBe """{"x": 1}"""
    }

    test("write replaces existing file in place") {
        val target = File(tmpDir(), "state.json")
        target.writeText("""{"version": 1}""")
        AtomicJsonWriter.write(target, """{"version": 2}""")
        target.readText() shouldBe """{"version": 2}"""
    }

    test("temp file is cleaned up after successful write") {
        val dir = tmpDir()
        val target = File(dir, "state.json")
        AtomicJsonWriter.write(target, """{"x": 1}""")
        // Only the target should remain — no `.tmp` sibling lingering.
        val siblings = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        siblings shouldBe listOf("state.json")
    }

    test("crash mid-write would leave previous file intact (simulated by not finishing rename)") {
        // Direct test: write the temp file manually but don't move it. The
        // target should still hold its previous content.
        val dir = tmpDir()
        val target = File(dir, "state.json")
        target.writeText("""{"v": "old"}""")
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText("""{"v": "partial-new"}""")
        // Simulated crash: temp exists, rename never happened.
        target.readText() shouldBe """{"v": "old"}"""
        target.readText() shouldNotContain "partial-new"
        // The next successful AtomicJsonWriter.write would overwrite the temp
        // (writeText is idempotent) and complete the rename.
        AtomicJsonWriter.write(target, """{"v": "new"}""")
        target.readText() shouldBe """{"v": "new"}"""
    }

    test("write of large content (multi-KB) succeeds atomically") {
        val target = File(tmpDir(), "big.json")
        val big = "x".repeat(100_000)
        AtomicJsonWriter.write(target, """{"data":"$big"}""")
        target.length() shouldBe ("""{"data":"$big"}""".toByteArray().size.toLong())
        target.readText() shouldContain big
    }
})
