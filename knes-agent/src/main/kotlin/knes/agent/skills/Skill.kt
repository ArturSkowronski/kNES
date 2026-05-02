package knes.agent.skills

/**
 * One scripted FF1 macro. Implementations call EmulatorToolset directly to drive the game;
 * the LLM only chooses which Skill to invoke (via the @Tool methods on SkillRegistry).
 *
 * See spec §5 for design rationale (Voyager skill library + CPP navigator).
 */
interface Skill {
    val id: String                              // stable identifier, snake_case
    val description: String                     // surfaced as @LLMDescription text
    suspend fun invoke(args: Map<String, String> = emptyMap()): SkillResult
}
