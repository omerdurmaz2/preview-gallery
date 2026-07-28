package com.devomer.previewgallery.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SplitEditorSwitcherTest : BasePlatformTestCase() {

    fun `test a plain text editor is left alone instead of failing`() {
        val file = myFixture.addFileToProject("Foo.kt", "package com.example\n").virtualFile
        myFixture.openFileInEditor(file)
        // No Compose preview exists in the test fixture, so there is no split editor to switch. The contract is
        // that this degrades to a no-op rather than throwing.
        SplitEditorSwitcher.switchToCodeOnly(project, file)
    }

    fun `test a file that is not open is a no-op`() {
        val file = myFixture.addFileToProject("Bar.kt", "package com.example\n").virtualFile
        SplitEditorSwitcher.switchToCodeOnly(project, file)
    }

    fun `test the single-editor overload is also a no-op for a plain text editor`() {
        val file = myFixture.addFileToProject("Baz.kt", "package com.example\n").virtualFile
        myFixture.openFileInEditor(file)
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(file)
        assertNotNull(editor)
        // Same degrade-to-no-op contract as the file-based overload, exercised directly on a single editor.
        if (editor != null) SplitEditorSwitcher.switchToCodeOnly(editor)
    }
}
