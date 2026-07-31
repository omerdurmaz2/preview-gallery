package com.devomer.previewgallery.service

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SnapshotSourceScannerTest : BasePlatformTestCase() {

    private fun addSnapshotFile() {
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
    }

    /** The directory holding [path]'s placeholder — the only way to get a bare `VirtualFile` directory here. */
    private fun directory(path: String): VirtualFile =
        myFixture.addFileToProject("$path/.gitkeep", "").virtualFile.parent

    fun `test a screenshotTest file outside every source root still produces a row`() {
        addSnapshotFile()
        val rows = SnapshotSourceScanner.scan(project)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertTrue(row.indexed.isSnapshotTest)
        assertEquals("Widget_Default_Snapshot", row.indexed.functionName)
        assertEquals(listOf("Widget"), row.indexed.targets)
    }

    fun `test a directory with no kotlin files still marks the module applicable`() {
        myFixture.addFileToProject("src/screenshotTest/README.md", "no snapshots yet")
        assertEquals(1, SnapshotSourceScanner.directories(project).size)
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.scan(project))
    }

    fun `test a project with no screenshotTest directory yields nothing`() {
        myFixture.addFileToProject("src/main/kotlin/com/example/Widgets.kt", "package com.example")
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.directories(project))
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.scan(project))
    }

    fun `test a file with no PreviewTest function contributes nothing`() {
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/Helpers.kt",
            """
            package com.example

            fun fakeState() = Unit
            """.trimIndent(),
        )
        assertEquals(1, SnapshotSourceScanner.directories(project).size)
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.scan(project))
    }

    fun `test a file whose text never mentions Preview is gated out before PSI is built`() {
        // Created through the temp dir, not addFileToProject: the latter returns a PsiFile and so builds the very
        // thing this asserts is never built. Without the text gate, every .kt file in the source set is parsed
        // into an AST on every PsiModificationTracker bump — after any keystroke in any Kotlin file.
        val fixtures = myFixture.tempDirFixture.createFile(
            "src/screenshotTest/kotlin/com/example/Fixtures.kt",
            "package com.example\n\ninternal object Fixtures\n",
        )
        addSnapshotFile()

        val functions = SnapshotSourceScanner.scan(project).map { it.indexed.functionName }

        assertEquals(listOf("Widget_Default_Snapshot"), functions)
        val fileManager = PsiManagerEx.getInstanceEx(project).fileManager
        assertNull(fileManager.findCachedViewProvider(fixtures))
    }

    fun `test probing a module directory root finds its screenshotTest source set`() {
        val screenshotTest = directory("src/screenshotTest")
        val moduleDirectory = screenshotTest.parent.parent

        assertEquals(screenshotTest, SnapshotSourceScanner.probe(moduleDirectory))
    }

    fun `test probing a source-set content root finds the sibling screenshotTest directory`() {
        // The shape the reference project depends on: a module-per-source-set import gives `…ui.main` a content
        // root of `<moduleDir>/src/main`, where `src/screenshotTest` is the root's *sibling*, not its child. The
        // fixture's own content root is the module directory, so no test that goes through `directories` can
        // reach this branch — it has to be probed directly.
        val screenshotTest = directory("src/screenshotTest")
        val sourceSetRoot = directory("src/main")

        assertEquals("src", sourceSetRoot.parent.name)
        assertEquals(screenshotTest, SnapshotSourceScanner.probe(sourceSetRoot))
    }

    fun `test probing a root that is neither shape finds nothing`() {
        directory("src/screenshotTest")
        val unrelated = directory("code")

        // Neither `code/src/screenshotTest` nor a parent named `src`: the module is simply not recognised here,
        // and PreviewIndexService falls back to whatever the index saw for it.
        assertNull(SnapshotSourceScanner.probe(unrelated))
    }

    fun `test the module directory of a snapshot file is the parent of its src directory`() {
        val file = myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile
        val moduleDirectory = directory("src/screenshotTest").parent.parent

        // What the reference strip resolves its content root with, without asking the project model.
        assertEquals(moduleDirectory, SnapshotSourceScanner.moduleDirectory(file))
    }

    fun `test a file outside a screenshotTest source set has no module directory`() {
        val file = myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            "package com.example\n",
        ).virtualFile

        assertNull(SnapshotSourceScanner.moduleDirectory(file))
    }

    fun `test a screenshotTest directory not under src has no module directory`() {
        val file = myFixture.addFileToProject(
            "custom/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile

        // The probe's rule read backwards: only `<moduleDir>/src/screenshotTest` is recognised, so the two can
        // never disagree about which directory a snapshot file's module is.
        assertNull(SnapshotSourceScanner.moduleDirectory(file))
    }
}
