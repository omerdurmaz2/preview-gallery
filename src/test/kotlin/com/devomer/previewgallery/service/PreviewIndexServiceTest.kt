package com.devomer.previewgallery.service

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
}
