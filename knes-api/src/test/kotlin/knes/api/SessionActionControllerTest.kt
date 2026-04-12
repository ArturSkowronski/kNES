package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SessionActionControllerTest : FunSpec({

    test("SessionActionController implements ActionController") {
        val session = EmulatorSession()
        val controller = SessionActionController(session)
        controller shouldNotBe null
    }

    test("readState returns watched addresses") {
        val session = EmulatorSession()
        session.setWatchedAddresses(mapOf("test" to 0x0000))
        val controller = SessionActionController(session)
        val state = controller.readState()
        state.containsKey("test") shouldBe true
    }
})
