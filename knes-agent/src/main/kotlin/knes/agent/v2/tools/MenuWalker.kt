package knes.agent.v2.tools

/**
 * Parses menu path strings like "main/equip/char1/weapon/0" into a sequence
 * of NES button taps for the FF1 field menu.
 *
 * Path grammar:
 *   <root> "/" <segment> ("/" <segment>)*
 *   root := main | shop
 *   main segments := item|magic|equip|status|exit|char1..char4|weapon|armor|<slot-index>
 *   shop segments := buy|sell|exit|<item-index>|char1..char4
 */
data class MenuTap(val button: String, val count: Int = 1)

class MenuWalker {
    fun parse(path: String): List<MenuTap> {
        val segments = path.trim('/').split("/")
        require(segments.isNotEmpty()) { "empty menu path" }
        return when (segments[0]) {
            "main" -> parseMain(segments.drop(1))
            "shop" -> parseShop(segments.drop(1))
            else -> throw IllegalArgumentException("unknown menu root: ${segments[0]}")
        }
    }

    private fun parseMain(rest: List<String>): List<MenuTap> {
        val out = mutableListOf<MenuTap>()
        out += MenuTap("B")    // open field menu
        if (rest.isEmpty()) return out
        val top = mapOf("item" to 0, "magic" to 1, "equip" to 2, "status" to 3, "exit" to 4)
        val idx = top[rest[0]] ?: throw IllegalArgumentException("unknown main item: ${rest[0]}")
        if (idx > 0) out += MenuTap("DOWN", idx)
        out += MenuTap("A")
        if (rest.size >= 2 && rest[1].startsWith("char")) {
            val n = rest[1].removePrefix("char").toInt()
            require(n in 1..4) { "char must be 1..4" }
            if (n > 1) out += MenuTap("DOWN", n - 1)
            out += MenuTap("A")
        }
        if (rest.size >= 3 && (rest[2] == "weapon" || rest[2] == "armor")) {
            if (rest[2] == "armor") out += MenuTap("RIGHT")
            if (rest.size >= 4) {
                val slot = rest[3].toInt()
                require(slot in 0..3) { "slot must be 0..3" }
                if (slot > 0) out += MenuTap("DOWN", slot)
                out += MenuTap("A")
            }
        }
        return out
    }

    private fun parseShop(rest: List<String>): List<MenuTap> {
        val out = mutableListOf<MenuTap>()
        if (rest.isEmpty()) return out
        val top = mapOf("buy" to 0, "sell" to 1, "exit" to 2)
        val idx = top[rest[0]] ?: throw IllegalArgumentException("unknown shop item: ${rest[0]}")
        if (idx > 0) out += MenuTap("DOWN", idx)
        out += MenuTap("A")
        if (rest.size >= 2) {
            val item = rest[1].toInt()
            require(item in 0..7) { "shop item must be 0..7" }
            if (item > 0) out += MenuTap("DOWN", item)
            out += MenuTap("A")
        }
        if (rest.size >= 3 && rest[2].startsWith("char")) {
            val n = rest[2].removePrefix("char").toInt()
            require(n in 1..4) { "char must be 1..4" }
            if (n > 1) out += MenuTap("DOWN", n - 1)
            out += MenuTap("A")
        }
        return out
    }
}
