package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import java.nio.file.Files

class OutfitStateTest : FunSpec({
    test("markBought + load round-trips and weaponsBoughtFor matches hash") {
        val tmp = Files.createTempFile("outfit-state-", ".json").toFile().apply { deleteOnExit() }
        val store = OutfitState(file = tmp)
        store.markBought(savestateHash = "abc123", equipped = listOf(1, 2, 3, 4),
            goldSpent = 32, shops = listOf("weapon@map7-(8,5)"))

        val loaded = OutfitState(file = tmp)
        loaded.weaponsBoughtFor("abc123") shouldBe true
        loaded.weaponsBoughtFor("xyz789") shouldBe false
        loaded.shopsClassified shouldContain "weapon@map7-(8,5)"
    }

    test("missing file returns weaponsBoughtFor=false") {
        val tmp = Files.createTempFile("outfit-state-missing-", ".json").toFile()
        tmp.delete()
        val store = OutfitState(file = tmp)
        store.weaponsBoughtFor("anything") shouldBe false
    }

    test("corrupt JSON returns weaponsBoughtFor=false (no crash)") {
        val tmp = Files.createTempFile("outfit-state-corrupt-", ".json").toFile().apply { deleteOnExit() }
        tmp.writeText("not valid json {")
        val store = OutfitState(file = tmp)
        store.weaponsBoughtFor("anything") shouldBe false
    }
})
