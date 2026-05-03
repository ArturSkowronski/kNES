package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MapSessionTest : FunSpec({
    fun buildRom(): ByteArray {
        val rom = ByteArray(0x10010 + 0x4000 * 2)
        // map 0 -> bankIdx 0, $8200 (file 0x10210). Emit single tile 0x05 + 0xFF.
        rom[0x10010] = 0x00; rom[0x10011] = 0x02
        rom[0x10210] = 0x05; rom[0x10211] = 0xFF.toByte()
        // map 1 -> bankIdx 0, $8400 (file 0x10410). Emit single tile 0x09 + 0xFF.
        rom[0x10012] = 0x00; rom[0x10013] = 0x04
        rom[0x10410] = 0x09; rom[0x10411] = 0xFF.toByte()
        return rom
    }

    test("cache hit on same mapId returns same instance") {
        val ms = MapSession(InteriorMapLoader(buildRom()), FogOfWar())
        ms.ensureCurrent(0)
        val first = ms.currentMap
        ms.ensureCurrent(0)
        ms.currentMap shouldBe first
    }

    test("cache invalidation + fog clear on mapId change") {
        val fog = FogOfWar()
        val ms = MapSession(InteriorMapLoader(buildRom()), fog)
        ms.ensureCurrent(0)
        val first = ms.currentMap
        fog.markBlocked(1, 1)
        fog.isBlocked(1, 1) shouldBe true
        ms.ensureCurrent(1)
        ms.currentMap shouldNotBe first
        fog.isBlocked(1, 1) shouldBe false
    }

    test("readViewport delegates to current map") {
        val ms = MapSession(InteriorMapLoader(buildRom()), FogOfWar())
        ms.ensureCurrent(0)
        val vp = ms.readViewport(8 to 8)
        vp.partyWorldXY shouldBe (8 to 8)
        vp.width shouldBe 16
    }

    test("readViewport before ensureCurrent throws") {
        val ms = MapSession(InteriorMapLoader(buildRom()), FogOfWar())
        try {
            ms.readViewport(0 to 0)
            error("expected throw")
        } catch (_: IllegalStateException) { /* ok */ }
    }
})
