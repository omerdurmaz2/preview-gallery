package com.devomer.previewgallery.service

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
}
