package knes.agent.v2.llm

class HaikuClient(private val http: AnthropicHttp) {
    val modelId = "claude-haiku-4-5-20251001"

    /**
     * Scene description for the Executor. Returns a compact text breakdown of
     * what's visible in the viewport: party position, visible NPC sprites with
     * kind+viewport coords, and any interactive overlays (dialog/menu/shop UI).
     *
     * Per spec §3: vision pre-processing belongs in Haiku, not the Executor's
     * Sonnet call directly. Sonnet reads this text + RAM digest + plan and
     * picks the next tool.
     */
    suspend fun describeScene(screenshotB64: String): String {
        val systemPrompt = """
            You are a vision pre-processor for an FF1 NES playing agent.
            Read the screenshot and emit a TERSE scene digest in this exact format
            (one line per fact, no prose):

            party: vp(<x>,<y>) facing <N|S|E|W|?>
            overlay: <none|field-menu|dialog|shop-menu|battle|post-battle|title|encounter-flash>
            sprite: <kind> vp(<x>,<y>) [<short note>]
            sprite: ...
            exits-visible: <south|north|east|west|south,east|...|none>

            Rules:
            - Viewport tiles are 0..15 horizontally, 0..14 vertically. Party sprite is usually at vp(8,7) but verify.
            - Sprite kinds: shopkeeper, innkeeper, king, generic-npc, chest, sign, exit-tile.
            - For shopkeeper: include note with merchandise type if visible (weapon/armor/item/magic).
            - If overlay is shop-menu/dialog/battle, omit sprite lines (they're covered by the UI).
            - Be honest: if you can't tell, write "?" instead of guessing.
            - Max 8 lines total. NO markdown, NO extra prose.
        """.trimIndent()
        return http.generate(
            model = modelId,
            systemPrompt = systemPrompt,
            userText = "Describe what is on the screen now.",
            imageB64 = screenshotB64,
            maxTokens = 400,
        ).trim()
    }
}
