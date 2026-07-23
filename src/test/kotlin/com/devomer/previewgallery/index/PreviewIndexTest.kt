package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex

class PreviewIndexTest : BasePlatformTestCase() {

    private fun allValues(): List<IndexedPreview> {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        val result = mutableListOf<IndexedPreview>()
        index.processAllKeys(PreviewIndex.NAME, { key ->
            index.processValues(PreviewIndex.NAME, key, null, { _, values ->
                result += values
                true
            }, scope)
            true
        }, project)
        return result
    }

    fun `test a preview file is indexed`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val values = allValues()
        assertEquals(1, values.size)
        assertEquals("com.example.FooKt.BarPreview", values.single().composableFqn)
    }

    fun `test a file without previews contributes nothing`() {
        myFixture.addFileToProject(
            "Plain.kt",
            """
            package com.example

            fun plain() {}
            """.trimIndent(),
        )
        assertTrue(allValues().isEmpty())
    }

    fun `test the index key is the composable FQN`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        // Go through allValues(), which filters each key by scope: processAllKeys alone also returns keys left
        // in the persistent enumerator by other test methods in this class, which BasePlatformTestCase shares.
        assertEquals(listOf("com.example.FooKt.BarPreview"), allValues().map { it.composableFqn })
    }

    fun `test two previews in one file are both indexed`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun OnePreview() {}

            @Preview
            fun TwoPreview() {}
            """.trimIndent(),
        )
        assertEquals(2, allValues().size)
    }
}
