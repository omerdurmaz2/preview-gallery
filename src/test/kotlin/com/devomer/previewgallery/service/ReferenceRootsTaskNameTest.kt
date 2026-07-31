package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceRootsTaskNameTest {

    @Test
    fun `a flavoured variant names its own update task`() {
        assertEquals("updateGoogleDebugScreenshotTest", ReferenceRoots.updateTask("GoogleDebug"))
    }

    @Test
    fun `the plain debug variant names the task the skill documents`() {
        assertEquals("updateDebugScreenshotTest", ReferenceRoots.updateTask("Debug"))
    }

    @Test
    fun `an unknown variant names no task at all`() {
        assertNull(ReferenceRoots.updateTask(null))
    }
}
