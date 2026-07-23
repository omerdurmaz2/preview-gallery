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
}
