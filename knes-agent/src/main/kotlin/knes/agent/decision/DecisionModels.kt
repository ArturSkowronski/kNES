package knes.agent.decision

import java.io.File

/**
 * Which decision model the goal selector asks, chosen from the environment.
 *
 * Off by default. The agent has a working chat-model Executor; a second decision path
 * earns its place by being switched on deliberately and watched, not by appearing under
 * everyone the moment a Python package happens to be installed.
 *
 *     KNES_DECISION=off     the selector is not built; the Executor asks the chat model  (default)
 *     KNES_DECISION=order   the selector runs on [DeclaredOrder] — no model, no network
 *     KNES_DECISION=semif   the selector runs on a local SemIf sidecar
 *
 * The SemIf settings all have defaults that match a stock checkout, so in practice
 * `KNES_DECISION=semif SEMIF_PYTHON=~/GitHub/SemIf/.venv/bin/python` is the whole setup.
 */
object DecisionModels {

    enum class Kind { Off, DeclaredOrder, SemIf }

    /** Split from the environment so the rule itself can be tested. */
    fun select(requested: String?): Kind =
        when (val name = requested?.takeIf { it.isNotBlank() }?.lowercase()?.trim()) {
            null, "off", "none", "false" -> Kind.Off
            "order", "priority", "declared-order" -> Kind.DeclaredOrder
            "semif", "jev" -> Kind.SemIf
            else -> error("KNES_DECISION='$name' is not a decision model; use 'off', 'order' or 'semif'")
        }

    /**
     * The sidecar command line.
     *
     * Pure, and the defaults are the pinned checkpoint SemIf's own examples use — a
     * floating revision would silently change what the agent decides between runs, which
     * is why SemIf refuses a remote model without a 40-character commit in the first
     * place.
     */
    fun semIfCommand(
        python: String = env("SEMIF_PYTHON") ?: "python3",
        sidecar: String = env("SEMIF_SIDECAR") ?: sidecarPath(),
        model: String = env("SEMIF_MODEL") ?: DEFAULT_MODEL,
        revision: String = env("SEMIF_REVISION") ?: DEFAULT_REVISION,
        backend: String = env("SEMIF_BACKEND") ?: "mlx",
        bits: String? = env("SEMIF_BITS"),
        semifSrc: String? = env("SEMIF_SRC"),
    ): List<String> = buildList {
        add(python)
        add(sidecar)
        add("--model"); add(model)
        add("--revision"); add(revision)
        add("--backend"); add(backend)
        bits?.let { add("--bits"); add(it) }
        semifSrc?.let { add("--semif-src"); add(it) }
    }

    /** Builds and starts the model, or returns null when decisions stay with the chat model. */
    suspend fun fromEnvironment(workingDir: File? = null): DecisionModel? =
        when (select(env("KNES_DECISION"))) {
            Kind.Off -> null
            Kind.DeclaredOrder -> DeclaredOrder
            Kind.SemIf -> SemIfProcess(semIfCommand(), workingDir = workingDir).start()
        }

    /**
     * Where `tools/semif_sidecar.py` is, found by walking up from the working directory.
     *
     * The agent is started from the repository root by `./gradlew :knes-agent:run`, but
     * tests run from the module directory and a developer may run from anywhere. A
     * relative path that resolves in one of those and not the others fails as a process
     * that dies before saying anything, which is a poor way to learn about a path.
     */
    fun sidecarPath(start: File = File(".").absoluteFile): String {
        var dir: File? = start
        while (dir != null) {
            val candidate = File(dir, RELATIVE_SIDECAR)
            if (candidate.isFile) return candidate.path
            dir = dir.parentFile
        }
        return RELATIVE_SIDECAR
    }

    const val RELATIVE_SIDECAR = "tools/semif_sidecar.py"

    /** Qwen3.5-4B, the checkpoint SemIf's own examples pin. */
    const val DEFAULT_MODEL = "Qwen/Qwen3.5-4B"
    const val DEFAULT_REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a"

    private fun env(key: String) = System.getenv(key)?.takeIf { it.isNotBlank() }
}
