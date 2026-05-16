package knes.agent.runtime

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Coloured per-agent logging for the v2 campaign loop.
 *
 * Each subsystem (main orchestrator / Advisor / Executor / Reviewer /
 * Cartographer / LLM client) gets a distinct ANSI badge so when you tail
 * the agent's stderr you can tell at a glance which agent is acting.
 *
 * Output goes to **stderr** (same channel as the legacy `System.err.println`
 * calls this replaces — viewer / log files / tee pipes don't change).
 *
 * Format:
 *   `HH:MM:SS  T0042  [AGENT]  message`
 *
 * - timestamp: wall clock HH:MM:SS, dim grey
 * - turn: optional, `T<5-digit>`, dim
 * - agent badge: fixed 11-col wide, bold, coloured background
 * - message: default fg
 *
 * Disable colour by exporting `NO_COLOR=1` (follows the widely-adopted
 * https://no-color.org/ convention) or running without a TTY (we then
 * auto-strip the ANSI codes).
 */
object Log {
    private val ts: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    // Decide once at startup whether to emit colour. Console-detection is
    // not perfect (gradle's run task pipes through a non-TTY by default —
    // so we ALSO honour an explicit FORCE_COLOR=1 escape hatch for users
    // running under `./gradlew :knes-agent:runV2`).
    private val useColour: Boolean = run {
        if (System.getenv("NO_COLOR")?.isNotBlank() == true) return@run false
        if (System.getenv("FORCE_COLOR")?.isNotBlank() == true) return@run true
        // Gradle runs us with no controlling tty even from an interactive
        // shell — default-on so the badge colours show up on a normal
        // terminal session. Users who pipe to a file should set NO_COLOR.
        true
    }

    private const val RESET = "[0m"
    private const val DIM = "[2m"
    private const val BOLD = "[1m"

    /** Per-agent style: (foreground hex, background hex, badge label). */
    private enum class Agent(val fg: String, val bg: String, val tag: String) {
        MAIN     ("#0d1117", "#00d4d4", "  MAIN     "),
        ADVISOR  ("#0d1117", "#c678dd", "  ADVISOR  "),
        EXECUTOR ("#0d1117", "#98c379", " EXECUTOR  "),
        REVIEWER ("#0d1117", "#e5c07b", " REVIEWER  "),
        CART     ("#0d1117", "#d19a66", "  CARTOG   "),
        LLM      ("#dddddd", "#5c6370", "    LLM    "),
        EVENT    ("#0d1117", "#56b6c2", "  EVENT    "),
        WARN     ("#0d1117", "#e5c07b", "   WARN    "),
        ERROR    ("#ffffff", "#e06c75", "   ERROR   "),
        OK       ("#0d1117", "#7bcf6d", "    OK     "),
    }

    private fun badge(a: Agent): String =
        if (!useColour) "[${a.tag.trim()}]".padEnd(12)
        else "$BOLD[38;2;${rgb(a.fg)};48;2;${rgb(a.bg)}m${a.tag}$RESET"

    private fun rgb(hex: String): String {
        val v = hex.removePrefix("#").toInt(16)
        val r = (v shr 16) and 0xFF
        val g = (v shr 8) and 0xFF
        val b = v and 0xFF
        return "$r;$g;$b"
    }

    private fun timestamp(): String {
        val t = LocalTime.now().format(ts)
        return if (useColour) "$DIM$t$RESET" else t
    }

    private fun turnTag(turn: Int?): String {
        if (turn == null) return "       "
        val s = "T${turn.toString().padStart(5, '0')}"
        return if (useColour) "$DIM$s$RESET" else s
    }

    private fun dim(s: String): String =
        if (useColour) "$DIM$s$RESET" else s

    private fun emit(agent: Agent, turn: Int?, msg: String) {
        val line = "${timestamp()}  ${turnTag(turn)}  ${badge(agent)}  $msg"
        System.err.println(line)
    }

    // ── Public API: one method per agent / event kind ──────────────────────

    fun main(msg: String, turn: Int? = null) = emit(Agent.MAIN, turn, msg)
    fun advisor(msg: String, turn: Int? = null) = emit(Agent.ADVISOR, turn, msg)
    fun executor(msg: String, turn: Int? = null) = emit(Agent.EXECUTOR, turn, msg)
    fun reviewer(msg: String, turn: Int? = null) = emit(Agent.REVIEWER, turn, msg)
    fun cartographer(msg: String, turn: Int? = null) = emit(Agent.CART, turn, msg)
    fun llm(msg: String, turn: Int? = null) = emit(Agent.LLM, turn, msg)
    fun event(msg: String, turn: Int? = null) = emit(Agent.EVENT, turn, msg)
    fun warn(msg: String, turn: Int? = null) = emit(Agent.WARN, turn, msg)
    fun error(msg: String, turn: Int? = null) = emit(Agent.ERROR, turn, msg)
    fun ok(msg: String, turn: Int? = null) = emit(Agent.OK, turn, msg)

    /**
     * Per-turn decision dump — the workhorse called after every Executor
     * tick. Renders as:
     *   `12:34:56  T0042  [EXECUTOR]  Town sm=(11,18) │ sequence(buttons=A) │ ok │ tapped 1 button`
     *
     * Pieces are colour-cycled and separated by `│` so the eye can land
     * on tool/outcome/message instantly.
     */
    fun turn(
        turn: Int,
        phase: String,
        smX: Int?, smY: Int?,
        tool: String,
        args: Map<String, String>,
        outcome: String,
        message: String,
        reasoning: String?,
    ) {
        val pos = if (smX != null && smY != null) "sm=($smX,$smY)" else "sm=(?,?)"
        val argStr = if (args.isEmpty()) "" else args.entries.joinToString(",") { "${it.key}=${it.value}" }
        val toolStr = if (argStr.isEmpty()) tool else "$tool($argStr)"
        val outcomeStr = when (outcome.lowercase()) {
            "ok" -> if (useColour) "[38;2;123;207;109m$outcome$RESET" else outcome
            "fail" -> if (useColour) "[38;2;224;108;117m$outcome$RESET" else outcome
            "reject" -> if (useColour) "[38;2;229;192;123m$outcome$RESET" else outcome
            else -> outcome
        }
        val sep = if (useColour) "$DIM│$RESET" else "|"
        val body = buildString {
            append(dim("$phase $pos")).append(" $sep ")
            append(toolStr).append(" $sep ")
            append(outcomeStr).append(" $sep ")
            append(dim(message.take(80)))
            if (!reasoning.isNullOrBlank()) {
                append(" $sep ")
                append(dim(reasoning.removePrefix("llm:").trim().take(70)))
            }
        }
        emit(Agent.EXECUTOR, turn, body)
    }
}
