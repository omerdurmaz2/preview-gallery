package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import javax.swing.JPanel

class ToolbarLocatorTest : BasePlatformTestCase() {

    private fun toolbarIn(place: String) =
        ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(), true)

    fun `test a nested toolbar is found by its place`() {
        val toolbar = toolbarIn("NlRhsConfigToolbar")
        val root = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.EAST) }, BorderLayout.NORTH)
        }
        val found = ToolbarLocator.findByPlace(root, "NlRhsConfigToolbar")
        assertEquals(1, found.size)
        assertSame(toolbar, found.first())
    }

    fun `test a toolbar with another place is not returned`() {
        val root = JPanel(BorderLayout()).apply { add(toolbarIn("NlConfigToolbar").component, BorderLayout.NORTH) }
        assertTrue(ToolbarLocator.findByPlace(root, "NlRhsConfigToolbar").isEmpty())
    }

    fun `test a null root returns nothing`() {
        assertTrue(ToolbarLocator.findByPlace(null, "NlRhsConfigToolbar").isEmpty())
    }
}
