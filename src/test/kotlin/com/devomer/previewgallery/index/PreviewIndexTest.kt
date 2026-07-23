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
        // Collect keys straight from the index, but keep only those with a live value in scope. Raw
        // processAllKeys also returns keys left in the persistent enumerator by sibling test methods (the
        // light project's key store is shared); scope-filtering drops them while still asserting on the real
        // key the indexer produced - so a wrong key selector would still be caught.
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        val liveKeys = mutableListOf<String>()
        index.processAllKeys(PreviewIndex.NAME, { key ->
            var live = false
            index.processValues(PreviewIndex.NAME, key, null, { _, _ -> live = true; false }, scope)
            if (live) liveKeys += key
            true
        }, project)
        assertEquals(listOf("com.example.FooKt.BarPreview"), liveKeys)
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
