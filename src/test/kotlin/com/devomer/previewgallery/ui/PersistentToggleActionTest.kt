package com.devomer.previewgallery.ui

import com.intellij.openapi.project.Project
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Turns both toolbar filters off again.
 *
 * `BasePlatformTestCase` reuses one light project for the whole run, and these toggles live in that project's
 * [com.intellij.ide.util.PropertiesComponent] precisely so they survive a restart — so a toggle left on by one
 * test is still on in every later test, in every later class, and empties their trees. Called from `tearDown`
 * rather than at the end of the setting test, so a failing assertion cannot leave the run poisoned behind it.
 */
internal fun resetFilterToggles(project: Project) {
    listOf(ModuleFilterToggleAction(project) {}, CoverageFilterToggleAction(project) {}).forEach { action ->
        action.setSelected(TestActionEvent.createTestEvent(action), false)
    }
}

/**
 * The persistence both toolbar filters share. `ModuleFilterToggleAction` carried this behaviour before it was
 * extracted and had no test of its own, so this file is what keeps the extraction honest.
 */
class PersistentToggleActionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            resetFilterToggles(project)
        } finally {
            super.tearDown()
        }
    }

    fun `test a toggle defaults to off`() {
        assertFalse(ModuleFilterToggleAction.isEnabled(project))
        assertFalse(CoverageFilterToggleAction.isEnabled(project))
    }

    fun `test setting a toggle is readable through its own isEnabled`() {
        val action = ModuleFilterToggleAction(project) {}

        action.setSelected(createEvent(action), true)

        assertTrue(ModuleFilterToggleAction.isEnabled(project))
    }

    fun `test the two toggles do not see each other's state`() {
        val moduleFilter = ModuleFilterToggleAction(project) {}

        moduleFilter.setSelected(createEvent(moduleFilter), true)

        assertTrue(ModuleFilterToggleAction.isEnabled(project))
        assertFalse(CoverageFilterToggleAction.isEnabled(project))
    }

    fun `test toggling calls back`() {
        var calls = 0
        val action = CoverageFilterToggleAction(project) { calls++ }

        action.setSelected(createEvent(action), true)

        assertEquals(1, calls)
    }

    private fun createEvent(action: com.intellij.openapi.actionSystem.AnAction) =
        TestActionEvent.createTestEvent(action)
}
