package knes.agent.campaign

import knes.agent.runtime.Phase

/**
 * What the agent is trying to achieve, in the game it happens to be playing.
 *
 * This is deliberately *not* profile data. Phases, landmarks and signals are RAM
 * interpretation and belong in `profiles/<id>.json`; a campaign is the game's party
 * model, its item encoding and its goal list, and expressing that as JSON would mean
 * inventing a generic RPG model nobody needs yet.
 *
 * It is also deliberately not left to the model. Claude Plays Pokémon has no equivalent
 * — objectives live in a knowledge base the model edits itself — and "declares quests
 * done before they actually are" is one of that harness's documented failure modes. A
 * hard predicate is the verifier that prevents it, so milestones stay deterministic.
 *
 * What this interface buys is isolation: game knowledge sits in one implementation
 * instead of being scattered through the runtime and the three agents.
 */
interface Campaign {
    /**
     * Milestones describing a transient state that legitimately stops holding once it
     * has fired. The Reviewer must not re-verify these or it regresses a real
     * achievement back to in_progress.
     */
    val eventTypeMilestones: Set<String>

    fun isSatisfied(
        id: String,
        phase: Phase,
        ram: Map<String, Int>,
        prereqDone: Map<String, Boolean> = emptyMap(),
    ): Boolean

    /** How many party members hold a weapon at all. */
    fun countHolding(ram: Map<String, Int>): Int

    /** How many party members have a weapon actually equipped. */
    fun countEquipped(ram: Map<String, Int>): Int

    /** Compact per-character weapon line for prompts. */
    fun partyDigest(ram: Map<String, Int>): String

    /** Spendable money, decoded from however the game stores it. */
    fun gold(ram: Map<String, Int>): Int

    companion object {
        fun of(profileId: String?): Campaign = when (profileId?.lowercase()) {
            "ff1" -> Ff1Campaign
            else -> NoCampaign
        }
    }
}

/**
 * The campaign for a game nobody has written one for. Answers "no" to everything rather
 * than guessing, so a milestone can never latch by accident on an unknown game.
 */
object NoCampaign : Campaign {
    override val eventTypeMilestones: Set<String> = emptySet()
    override fun isSatisfied(id: String, phase: Phase, ram: Map<String, Int>, prereqDone: Map<String, Boolean>) = false
    override fun countHolding(ram: Map<String, Int>) = 0
    override fun countEquipped(ram: Map<String, Int>) = 0
    override fun partyDigest(ram: Map<String, Int>) = ""
    override fun gold(ram: Map<String, Int>) = 0
}
