package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupInjectorTest {

    private class NoopAction : AnAction() {
        override fun actionPerformed(event: AnActionEvent) = Unit
    }

    @Test fun `the action is added to an empty group`() {
        val group = DefaultActionGroup()
        val action = NoopAction()
        assertTrue(ActionGroupInjector.addOnce(group, action))
        assertEquals(listOf<AnAction>(action), group.childActionsOrStubs.toList())
    }

    @Test fun `a second injection of the same action is a no-op`() {
        val group = DefaultActionGroup()
        val action = NoopAction()
        ActionGroupInjector.addOnce(group, action)
        assertFalse(ActionGroupInjector.addOnce(group, action))
        assertEquals(1, group.childActionsOrStubs.size)
    }

    @Test fun `existing children are preserved`() {
        val existing = NoopAction()
        val group = DefaultActionGroup(existing)
        val action = NoopAction()
        ActionGroupInjector.addOnce(group, action)
        assertEquals(listOf<AnAction>(existing, action), group.childActionsOrStubs.toList())
    }
}
