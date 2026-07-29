package com.devomer.previewgallery.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ActiveModuleTrackerTest : BasePlatformTestCase() {

    fun `test no open file means no active module`() {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        val tracker = ActiveModuleTracker(project, disposable) {}
        assertNull(tracker.activeModuleName)
    }

    fun `test an open file reports its module`() {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        val tracker = ActiveModuleTracker(project, disposable) {}

        val file = myFixture.addFileToProject("Foo.kt", "package com.example")
        FileEditorManager.getInstance(project).openFile(file.virtualFile, true)

        assertNotNull(tracker.activeModuleName)
    }

    // NOTE: BasePlatformTestCase's light fixture project has a single test module, so there is no way to open
    // two files that genuinely live in two different modules here. This test therefore proves the "same module"
    // half of the fix directly (opening a second file that resolves to the same module name as the first does
    // not invoke onChange again), and the "module name changed" half only via the no-file -> first-file
    // transition (activeModuleName going from null to a concrete name), not via two distinct named modules.
    fun `test a selection change within the same module does not invoke the callback again`() {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        var invocationCount = 0
        val tracker = ActiveModuleTracker(project, disposable) { invocationCount++ }

        val first = myFixture.addFileToProject("First.kt", "package com.example")
        FileEditorManager.getInstance(project).openFile(first.virtualFile, true)
        // The module name changed (null -> a concrete name), so the callback fires once.
        assertEquals(1, invocationCount)
        val moduleAfterFirst = tracker.activeModuleName
        assertNotNull(moduleAfterFirst)

        val second = myFixture.addFileToProject("second/Second.kt", "package com.example.second")
        FileEditorManager.getInstance(project).openFile(second.virtualFile, true)

        // Both files resolve to the fixture's single test module, so the reported module name is unchanged and
        // the callback must not fire again.
        assertEquals(moduleAfterFirst, tracker.activeModuleName)
        assertEquals(1, invocationCount)
    }
}
