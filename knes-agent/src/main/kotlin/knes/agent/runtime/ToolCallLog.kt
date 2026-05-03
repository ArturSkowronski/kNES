package knes.agent.runtime

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Append-only log of `name(args)` strings recorded by SkillRegistry @Tool methods.
 * Drained per executor turn into TraceEvent.toolCalls so live traces show what
 * the LLM actually invoked (with arguments), not just its prose.
 */
class ToolCallLog {
    private val queue = ConcurrentLinkedQueue<String>()

    fun append(name: String, args: String) { queue.add("$name($args)") }
    fun appendNoArgs(name: String) { queue.add("$name()") }

    fun drain(): List<String> {
        val out = ArrayList<String>(queue.size)
        while (true) { out.add(queue.poll() ?: break) }
        return out
    }
}
