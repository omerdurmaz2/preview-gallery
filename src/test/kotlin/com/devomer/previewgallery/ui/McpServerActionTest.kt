package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.McpServerService
import com.intellij.icons.AllIcons
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The toolbar button doubles as the server's status light, so which state it reports is behaviour.
 *
 * What is **not** asserted here is the green dot itself: `ExecutionUtil.withLiveIndicator` composes the badge
 * through the icon subsystem, which a headless test does not start, so it hands back the base icon unchanged
 * and no assertion here could tell a working badge from a missing one. That one is the manual gate's to check.
 *
 * Neither test starts the server: binding the real port would make the suite depend on no other IDE holding it.
 */
class McpServerActionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            McpServerService.getInstance().stop()
        } finally {
            super.tearDown()
        }
    }

    fun `test a stopped server keeps the plain icon`() {
        assertSame(AllIcons.General.Web, McpServerAction.iconFor(running = false))
    }

    fun `test a stopped server leaves the plain icon and the plain tooltip on the action`() {
        McpServerService.getInstance().stop()
        val action = McpServerAction(project)
        val event = TestActionEvent.createTestEvent(action)

        action.update(event)

        assertSame(AllIcons.General.Web, event.presentation.icon)
        // Text as well as description: the toolbar's tooltip leads with the text and may show nothing else.
        assertEquals(PreviewGalleryBundle.message("action.mcpServer.text"), event.presentation.text)
        assertEquals(PreviewGalleryBundle.message("action.mcpServer.text"), event.presentation.description)
    }

    fun `test the running tooltip carries the address an agent connects to`() {
        val running = PreviewGalleryBundle.message(
            "action.mcpServer.running",
            McpServerService.getInstance().port.toString(),
        )

        // The point of the tooltip is that the URL is readable without opening the dialog, and the port has to
        // survive MessageFormat rather than come back grouped as "7,891".
        assertTrue(running, running.contains("http://localhost:7891/mcp"))
    }
}
