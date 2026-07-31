package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.SnapshotCoverage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PreviewIndexServiceTest : BasePlatformTestCase() {

    fun `test entries carry the resolved module and file`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )

        val entries = PreviewIndexService.getInstance(project).findAll()

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("com.example.FooKt.BarPreview", entry.indexed.composableFqn)
        assertEquals("Foo.kt", entry.file.name)
        assertTrue(entry.moduleName.isNotBlank())
        assertEquals("com.example.FooKt.BarPreview#BarPreview", entry.id)
    }

    fun `test entries are sorted by module then package then display name`() {
        myFixture.addFileToProject(
            "Zeta.kt",
            """
            package com.example.zeta

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun ZPreview() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Alpha.kt",
            """
            package com.example.alpha

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun APreview() {}
            """.trimIndent(),
        )

        val packages = PreviewIndexService.getInstance(project).findAll().map { it.indexed.packageName }

        assertEquals(listOf("com.example.alpha", "com.example.zeta"), packages)
    }

    fun `test entries are sorted case-insensitively`() {
        myFixture.addFileToProject(
            "Banana.kt",
            """
            package com.example.Banana

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BPreview() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Apple.kt",
            """
            package com.example.apple

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun APreview() {}
            """.trimIndent(),
        )

        val packages = PreviewIndexService.getInstance(project).findAll().map { it.indexed.packageName }

        // A case-sensitive sort would put uppercase "Banana" before lowercase "apple"; case-insensitive puts
        // "apple" first.
        assertEquals(listOf("com.example.apple", "com.example.Banana"), packages)
    }

    fun `test an empty project yields no entries`() {
        assertTrue(PreviewIndexService.getInstance(project).findAll().isEmpty())
    }

    fun `test snapshot rows do not appear as previews`() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun WidgetPreview() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )

        val previews = PreviewIndexService.getInstance(project).findAll()

        assertEquals(1, previews.size)
        assertEquals("WidgetPreview", previews.single().indexed.functionName)
        assertEquals(1, previews.single().snapshots.size)
        // The corroborated case: a `src/screenshotTest` directory on disk AND indexed `@PreviewTest` rows from
        // it, so the badge is trustworthy and gets drawn.
        assertEquals(SnapshotCoverage.Covered(1), previews.single().coverage)
    }

    fun `test a screenshotTest directory the index cannot see degrades to NotApplicable`() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun WidgetPreview() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        // Kotlin under src/screenshotTest that yields no indexed snapshot row — the shape spec Risk 1 produces
        // when the source set never reaches `projectScope`. Disk says "this module has adopted screenshot
        // testing", the index says "it has no snapshots"; they disagree, so no badge is drawn at all.
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/Fixtures.kt",
            """
            package com.example

            internal object Fixtures
            """.trimIndent(),
        )

        val previews = PreviewIndexService.getInstance(project).findAll()

        assertEquals(1, previews.size)
        // Not Uncovered: `· no snapshot` on every row of an adopted module is the loudest possible wrong signal.
        assertEquals(SnapshotCoverage.NotApplicable, previews.single().coverage)
    }

    fun `test an empty screenshotTest directory reports its previews as uncovered`() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun WidgetPreview() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        // A directory holding no Kotlin at all: adopted, nothing written yet. Zero indexed rows is the truth
        // here, not a symptom, so the badge is drawn and says so.
        myFixture.addFileToProject("src/screenshotTest/kotlin/com/example/.gitkeep", "")

        val previews = PreviewIndexService.getInstance(project).findAll()

        assertEquals(SnapshotCoverage.Uncovered, previews.single().coverage)
    }
}
