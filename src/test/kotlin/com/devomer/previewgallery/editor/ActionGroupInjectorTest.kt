package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ActionGroupInjectorTest : BasePlatformTestCase() {

    private class NoopAction : AnAction() {
        override fun actionPerformed(event: AnActionEvent) = Unit
    }

    fun `test the action is added to an empty group`() {
        val group = DefaultActionGroup()
        val action = NoopAction()
        assertTrue(ActionGroupInjector.addOnce(group, action))
        assertEquals(listOf<AnAction>(action), group.childActionsOrStubs.toList())
    }

    fun `test a second injection of the same action is a no-op`() {
        val group = DefaultActionGroup()
        val action = NoopAction()
        ActionGroupInjector.addOnce(group, action)
        assertFalse(ActionGroupInjector.addOnce(group, action))
        assertEquals(1, group.childActionsOrStubs.size)
    }

    fun `test existing children are preserved`() {
        val existing = NoopAction()
        val group = DefaultActionGroup(existing)
        val action = NoopAction()
        ActionGroupInjector.addOnce(group, action)
        assertEquals(listOf<AnAction>(existing, action), group.childActionsOrStubs.toList())
    }
}
