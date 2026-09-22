package knes.agent.decision

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DecisionModelsTest : FunSpec({

    test("decisions stay with the chat model unless asked for") {
        DecisionModels.select(null) shouldBe DecisionModels.Kind.Off
        DecisionModels.select("") shouldBe DecisionModels.Kind.Off
        DecisionModels.select("off") shouldBe DecisionModels.Kind.Off
    }

    test("the model is named explicitly, case and whitespace insensitive") {
        DecisionModels.select(" SemIf ") shouldBe DecisionModels.Kind.SemIf
        DecisionModels.select("jev") shouldBe DecisionModels.Kind.SemIf
        DecisionModels.select("order") shouldBe DecisionModels.Kind.DeclaredOrder
        DecisionModels.select("pixels") shouldBe DecisionModels.Kind.Pixels
        DecisionModels.select("vision") shouldBe DecisionModels.Kind.Pixels
        DecisionModels.select("priority") shouldBe DecisionModels.Kind.DeclaredOrder
    }

    test("an unrecognised name stops the run rather than silently falling back") {
        shouldThrow<IllegalStateException> { DecisionModels.select("gpt-5") }
            .message shouldContain "is not a decision model"
    }

    test("the sidecar command pins the revision, because a floating one changes decisions") {
        val command = DecisionModels.semIfCommand(
            python = "/venv/bin/python", sidecar = "tools/semif_sidecar.py",
            model = "Qwen/Qwen3.5-4B", revision = "a".repeat(40), backend = "mlx",
            bits = null, semifSrc = null,
        )
        command shouldContainInOrder listOf("/venv/bin/python", "tools/semif_sidecar.py")
        command shouldContainInOrder listOf("--revision", "a".repeat(40))
        command shouldContainInOrder listOf("--backend", "mlx")
    }

    test("the pixel backend never asks for quantization — it loads the whole model, ViT and all") {
        val pixels = DecisionModels.semIfCommand(
            python = "python3", sidecar = "s.py", model = "m", revision = "r", backend = "pixels",
            bits = null, semifSrc = null,
        )
        pixels shouldContainInOrder listOf("--backend", "pixels")
        pixels.contains("--bits") shouldBe false
    }

    test("quantization and a source path are only passed when they were asked for") {
        val bare = DecisionModels.semIfCommand(
            python = "python3", sidecar = "s.py", model = "m", revision = "r", backend = "mlx",
            bits = null, semifSrc = null,
        )
        bare.contains("--bits") shouldBe false
        bare.contains("--semif-src") shouldBe false

        val full = DecisionModels.semIfCommand(
            python = "python3", sidecar = "s.py", model = "m", revision = "r", backend = "mlx",
            bits = "4", semifSrc = "/SemIf/src",
        )
        full shouldContainInOrder listOf("--bits", "4")
        full shouldContainInOrder listOf("--semif-src", "/SemIf/src")
    }
})

class SidecarPathTest : FunSpec({

    test("the sidecar is found by walking up, so the agent runs from any directory") {
        val root = kotlin.io.path.createTempDirectory("knes-sidecar").toFile()
        val sidecar = java.io.File(root, DecisionModels.RELATIVE_SIDECAR).apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env python3\n")
        }
        val deep = java.io.File(root, "knes-agent/build/classes").apply { mkdirs() }
        DecisionModels.sidecarPath(deep) shouldBe sidecar.path
        root.deleteRecursively()
    }

    test("when it is nowhere above, the relative path is returned and the spawn reports it") {
        val empty = kotlin.io.path.createTempDirectory("knes-empty").toFile()
        DecisionModels.sidecarPath(empty) shouldBe DecisionModels.RELATIVE_SIDECAR
        empty.deleteRecursively()
    }
})
