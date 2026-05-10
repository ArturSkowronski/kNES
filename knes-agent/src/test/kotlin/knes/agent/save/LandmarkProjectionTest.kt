package knes.agent.save

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import java.nio.file.Files

class LandmarkProjectionTest : FunSpec({

    test("toSnapshot groups landmarks by kind") {
        val tmpFile = Files.createTempFile("lm-", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmpFile)
        mem.record(Landmark(id = "k1", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 16, localY = 8, note = "King of Coneria"))
        mem.record(Landmark(id = "s1", kind = LandmarkKind.NPC_SHOPKEEPER,
            mapId = 0, localX = 12, localY = 18, note = "weapon shop"))
        mem.record(Landmark(id = "i1", kind = LandmarkKind.NPC_INNKEEPER,
            mapId = 0, localX = 14, localY = 6, note = "Coneria inn"))
        mem.record(Landmark(id = "t1", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 153, worldY = 162, note = "Coneria entry"))

        val snap = mem.toSnapshot()
        snap.kings.size shouldBe 1
        snap.kings[0].label shouldBe "King of Coneria"
        snap.kings[0].x shouldBe 16
        snap.shops.size shouldBe 1
        snap.inns.size shouldBe 1
        snap.other.size shouldBe 1
        snap.other[0].label shouldBe "Coneria entry"
        snap.other[0].x shouldBe 153
    }

    test("roundtrip — toSnapshot then applySnapshot rebuilds same kinds") {
        val src = LandmarkMemory(file =
            Files.createTempFile("src-", ".json").toFile().apply { deleteOnExit() })
        src.record(Landmark(id = "k1", kind = LandmarkKind.NPC_KING,
            mapId = 1, localX = 16, localY = 8, note = "King"))
        val snap = src.toSnapshot()
        val dst = LandmarkMemory(file =
            Files.createTempFile("dst-", ".json").toFile().apply { deleteOnExit() })
        dst.applySnapshot(snap)
        val all = dst.all()
        all.size shouldBe 1
        all[0].kind shouldBe LandmarkKind.NPC_KING
        all[0].localX shouldBe 16
    }
})
