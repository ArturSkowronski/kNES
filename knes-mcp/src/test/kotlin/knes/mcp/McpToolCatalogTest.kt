package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class McpToolCatalogTest : FunSpec({

    test("catalog defines the complete MCP tool contract in stable order") {
        McpToolCatalog.all.map { it.name } shouldContainExactly listOf(
            "load_rom",
            "step",
            "tap",
            "sequence",
            "get_state",
            "get_screen",
            "apply_profile",
            "list_actions",
            "execute_action",
            "list_profiles",
            "press",
            "release",
            "reset"
        )
        McpToolCatalog.all.map { it.name }.toSet().size shouldBe McpToolCatalog.all.size
    }

    test("catalog owns input schemas for tools that accept arguments") {
        McpToolCatalog.loadRom.inputSchema?.required shouldBe listOf("path")
        McpToolCatalog.step.inputSchema?.required shouldBe emptyList()
        McpToolCatalog.tap.inputSchema?.required shouldBe listOf("button")
        McpToolCatalog.sequence.inputSchema?.required shouldBe listOf("steps")
        McpToolCatalog.applyProfile.inputSchema?.required shouldBe listOf("profile_id")
        McpToolCatalog.listActions.inputSchema?.required shouldBe listOf("profile_id")
        McpToolCatalog.executeAction.inputSchema?.required shouldBe listOf("profile_id", "action_id")
        McpToolCatalog.press.inputSchema?.required shouldBe listOf("buttons")
        McpToolCatalog.release.inputSchema?.required shouldBe listOf("buttons")

        McpToolCatalog.getState.inputSchema shouldBe null
        McpToolCatalog.getScreen.inputSchema shouldBe null
        McpToolCatalog.listProfiles.inputSchema shouldBe null
        McpToolCatalog.reset.inputSchema shouldBe null
    }
})
