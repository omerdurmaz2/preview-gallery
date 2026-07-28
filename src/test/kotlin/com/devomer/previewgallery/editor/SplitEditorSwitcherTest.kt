package com.devomer.previewgallery.editor

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
}
