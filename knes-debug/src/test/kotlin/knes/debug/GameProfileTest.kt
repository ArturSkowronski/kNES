package knes.debug

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class GameProfileTest : FunSpec({

    test("builtin profiles are loaded") {
        GameProfile.list().size shouldBeGreaterThan 0
        GameProfile.get("smb") shouldNotBe null
        GameProfile.get("ff1") shouldNotBe null
    }

    test("SMB profile has expected addresses") {
        val smb = GameProfile.get("smb")!!
        smb.name shouldBe "Super Mario Bros"
        smb.addresses["playerX"] shouldNotBe null
        smb.addresses["lives"] shouldNotBe null
        smb.toWatchMap()["playerX"] shouldBe 0x0086
    }

    test("FF1 profile has expected addresses") {
        val ff1 = GameProfile.get("ff1")!!
        ff1.name shouldBe "Final Fantasy"
        ff1.addresses["char1_hpLow"] shouldNotBe null
        ff1.addresses["goldLow"] shouldNotBe null
        ff1.toWatchMap()["char1_hpLow"] shouldBe 0x610A
    }

    test("FF1 hidden flag marks cheat addresses") {
        val ff1 = GameProfile.get("ff1")!!
        ff1.addresses["encounterCounter"]!!.hidden shouldBe true
        ff1.addresses["enemy1_hpLow"]!!.hidden shouldBe true
        ff1.addresses["char1_hpLow"]!!.hidden shouldBe false
        ff1.addresses["goldLow"]!!.hidden shouldBe false
    }

    test("toFairWatchMap excludes hidden addresses") {
        val ff1 = GameProfile.get("ff1")!!
        val fair = ff1.toFairWatchMap()
        val all = ff1.toWatchMap()
        all.size shouldBeGreaterThan fair.size
        fair.containsKey("char1_hpLow") shouldBe true
        fair.containsKey("encounterCounter") shouldBe false
        fair.containsKey("enemy1_hpLow") shouldBe false
    }

    test("register custom profile") {
        val custom = GameProfile("Test", "test-game", "test", mapOf("hp" to AddressEntry(0x50, "health")))
        GameProfile.register(custom)
        GameProfile.get("test-game") shouldBe custom
    }

    test("MemoryMonitor applies profile") {
        val monitor = MemoryMonitor()
        val smb = GameProfile.get("smb")!!
        monitor.applyProfile(smb)
        monitor.activeProfile shouldBe smb
        monitor.getWatchedAddresses()["playerX"] shouldBe 0x0086
    }
})
