package com.devomer.previewgallery.ui

import com.devomer.previewgallery.service.PreviewIndexService
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Covers [IndexingCompletionTracker]'s wiring against a real project (PG11-2): the `DumbService.DUMB_MODE`
 * subscription, the [IndexStampGate] check in front of it, and the debounced hand-off to the panel's reload.
 * The gate's own decision table is unit-tested separately in [IndexStampGateTest].
 *
 * `exitDumbMode` is published directly on the project's message bus rather than by driving the platform into
 * and out of dumb mode: the listener's contract is "the IDE says indexes are queryable again", and publishing
 * the signal is the only part of that contract this class is responsible for reacting to.
 */
class IndexingCompletionTrackerTest : BasePlatformTestCase() {

    fun `test constructing the tracker does not fire a reload on its own`() {
        var reloads = 0
        newTracker { reloads++ }

        assertEquals(0, reloads)
    }

    fun `test leaving dumb mode with an unmoved index does not fire a reload`() {
        var reloads = 0
        newTracker { reloads++ }

        exitDumbMode()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(0, reloads)
    }

    fun `test an index change followed by leaving dumb mode fires exactly one reload`() {
        var reloads = 0
        newTracker { reloads++ }

        addPreviewFile()
        exitDumbMode()

        PlatformTestUtil.waitWithEventsDispatching("The tracker never fired a reload", { reloads > 0 }, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, reloads)
    }

    fun `test a burst of dumb mode exits collapses into one reload`() {
        var reloads = 0
        newTracker { reloads++ }

        addPreviewFile()
        exitDumbMode()
        exitDumbMode()
        exitDumbMode()

        PlatformTestUtil.waitWithEventsDispatching("The tracker never fired a reload", { reloads > 0 }, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, reloads)
    }

    private fun newTracker(onIndexingFinished: () -> Unit): IndexingCompletionTracker {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        return IndexingCompletionTracker(project, disposable, onIndexingFinished)
    }

    /** Adds a `@Preview` and queries the index, so `PreviewIndex` is actually built for it and its
     *  modification stamp moves — the tracker gates on that stamp, not on the file appearing. */
    private fun addPreviewFile() {
        myFixture.addFileToProject(
            "Added.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun AddedPreview() {}
            """.trimIndent(),
        )
        PreviewIndexService.getInstance(project).findAll()
    }

    private fun exitDumbMode() {
        project.messageBus.syncPublisher(DumbService.DUMB_MODE).exitDumbMode()
    }
}
