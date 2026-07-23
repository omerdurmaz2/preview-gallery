package com.devomer.previewgallery.searcheverywhere

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor

class PreviewSearchEverywhereContributorTest : BasePlatformTestCase() {

    private fun fetch(pattern: String): List<PreviewEntry> {
        val found = mutableListOf<PreviewEntry>()
        PreviewSearchEverywhereContributor(project)
            .fetchElements(pattern, EmptyProgressIndicator(), Processor { found += it; true })
        return found
    }

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun TabsPreview() {}

            @Preview
            fun ButtonPreview() {}
            """.trimIndent(),
        )
    }

    fun `test a matching pattern finds the preview`() {
        assertEquals(listOf("TabsPreview"), fetch("tabs").map { it.indexed.displayName })
    }

    fun `test a non-matching pattern finds nothing`() {
        assertTrue(fetch("zzz").isEmpty())
    }

    fun `test an empty pattern returns every preview`() {
        assertEquals(2, fetch("").size)
    }
}
