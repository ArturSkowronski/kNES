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

            How to classify overlay (CRITICAL — do NOT confuse these):
            - "field-menu": the WORLD/TOWN-pause menu opened with START. Has the
              text labels ITEM, MAGIC, WEAPON, ARMOR, STATUS in a left column,
              party HP cards on the right, and a gold counter. The PARTY is
              NOT shown standing in front of enemies. This is the most common
              UI screen. THIS IS NOT BATTLE.
            - "battle": you can see ENEMY SPRITES (orcs/imps/wolves/etc) on the
              left side of a black backdrop, the party silhouettes on the right
              side, and a command box at the bottom with FIGHT/MAGIC/DRINK/ITEM.
              Only label "battle" if you actually see ENEMY SPRITES.
            - "shop-menu": dialog box with merchandise list (item names + prices).
            - "dialog": NPC speech-bubble overlay with text but no menu list.
            - "title": pre-game title screen (FINAL FANTASY logo, blank field).
            - "encounter-flash": brief inverted/flashed colors transitioning into battle.
            - "none": pure overworld/town tile view, no UI overlay.

            Rules:
            - Viewport tiles are 0..15 horizontally, 0..14 vertically. Party sprite is usually at vp(8,7) but verify.
            - Sprite kinds: shopkeeper, innkeeper, king, generic-npc, chest, sign, exit-tile.
            - For shopkeeper: include note with merchandise type if visible (weapon/armor/item/magic).
            - If overlay is shop-menu/dialog/battle/field-menu, omit sprite lines (they're covered by the UI).
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

    data class SpriteCandidate(val kind: String, val vpX: Int, val vpY: Int, val note: String)

    /**
     * Parse [describeScene] output into structured sprite candidates. The
     * scene digest already emits `sprite: <kind> vp(x,y) [note]` lines — this
     * just regex-extracts them so approachSprite can target by kind without a
     * second LLM call. Returns empty list when no sprite lines are present
     * (e.g. overlay covers viewport).
     */
    fun parseSpriteLines(scene: String): List<SpriteCandidate> {
        val rx = Regex("""sprite:\s*([A-Za-z_-]+)\s*vp\((\d+),\s*(\d+)\)(?:\s*\[(.*?)\])?""")
        return scene.lineSequence()
            .mapNotNull { rx.find(it.trim()) }
            .map { m -> SpriteCandidate(
                kind = m.groupValues[1].trim().lowercase(),
                vpX = m.groupValues[2].toInt().coerceIn(0, 15),
                vpY = m.groupValues[3].toInt().coerceIn(0, 14),
                note = m.groupValues.getOrNull(4)?.trim().orEmpty(),
            ) }
            .toList()
    }

    /**
     * Vision scan of the current viewport for visible NPC/landmark sprites,
     * built on top of [describeScene] so we don't duplicate the prompt.
     * Caller typically wants `kind = "shopkeeper"` for the keeper-approach
     * final step. Returned coords are viewport tiles (party renders at ~8,7).
     */
    suspend fun scanCandidates(screenshotB64: String): List<SpriteCandidate> =
        parseSpriteLines(describeScene(screenshotB64))

    /**
     * Plan auditor — given the Advisor's plan, the current screenshot, and a
     * RAM digest, the model lists discrepancies between what the plan claims
     * has happened (steps already past the cursor) and what RAM/screen actually
     * show. Returns the raw JSON response; caller parses `{"issues":[...]}`.
     */
    suspend fun auditPlan(planDescription: String, ramDigest: String, screenshotB64: String): String {
        val systemPrompt = """
            You are a plan auditor for an FF1 NES playing agent. Read the
            Advisor's current plan, the live RAM digest, and the screenshot.
            For each step the plan considers ALREADY DONE (cursor past it),
            check whether the intended effect is actually reflected in RAM or
            on screen. Output ONLY JSON:
              {"issues":[{"step":N,"problem":"<≤80 chars>"}]}
            Use an empty list {"issues":[]} when everything checks out.
            Be conservative — only flag clear contradictions (e.g. plan says
            "Equipped Rapier for char1" but char1_weapon0 byte has bit7 unset).
            Do NOT speculate about steps that haven't been attempted yet.
        """.trimIndent()
        return http.generate(
            model = modelId,
            systemPrompt = systemPrompt,
            userText = "PLAN:\n$planDescription\n\nRAM:\n$ramDigest",
            imageB64 = screenshotB64,
            maxTokens = 400,
        ).trim()
    }

    /**
     * One-shot cardinal direction picker for vision-driven walking. Used by
     * the Town walkTo loop: ask Haiku which cardinal moves the party toward
     * a described target visible (or believed to be near) on screen. Returns
     * exactly one of: N, S, E, W, DONE, UNCLEAR.
     */
    suspend fun directionTo(screenshotB64: String, targetText: String): String {
        val systemPrompt = """
            You navigate an FF1 NES party. The party sprite is usually at viewport
            tile (8,7). Given a screenshot and a target description, decide which
            cardinal direction (N/S/E/W) the party should step next to get closer
            to the target.

            Rules:
            - Output EXACTLY one token: N, S, E, W, DONE, or UNCLEAR. No prose.
            - DONE if the party is already adjacent to (or on) the target.
            - UNCLEAR if you cannot see the target on screen.
            - Follow locate-party → locate-target → derive-direction. NO prior-knowledge
              of FF1 geography.
        """.trimIndent()
        return http.generate(
            model = modelId,
            systemPrompt = systemPrompt,
            userText = "Target: $targetText. Cardinal direction?",
            imageB64 = screenshotB64,
            maxTokens = 8,
        ).trim().uppercase().take(8)
    }
}
