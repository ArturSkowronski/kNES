package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

class McpServerTest : FunSpec({

    test("createMcpServer returns a server instance") {
        val server = createMcpServer()
        server shouldNotBe null
    }
})
