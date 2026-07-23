package com.devomer.previewgallery.ui

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTreeModelBuilderTest {

    @Test
    fun `groups by module then package`() {
        val rows = listOf(
            testRow(displayName = "A", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "B", packageName = "com.b", moduleName = "app"),
            testRow(displayName = "C", packageName = "com.c", moduleName = "design"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "")

        assertEquals(listOf("app", "design"), modules.map { it.moduleName })
        assertEquals(listOf("com.a", "com.b"), modules.first().packages.map { it.packageName })
        assertEquals(listOf("com.c"), modules.last().packages.map { it.packageName })
    }

    @Test
    fun `module count is the number of previews below it`() {
        val rows = listOf(
            testRow(displayName = "A", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "B", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "C", packageName = "com.b", moduleName = "app"),
        )

        assertEquals(3, PreviewTreeModelBuilder.build(rows, "").single().count)
    }

    @Test
    fun `everything is sorted alphabetically`() {
        val rows = listOf(
            testRow(displayName = "Zebra", packageName = "com.z", moduleName = "zeta"),
            testRow(displayName = "Apple", packageName = "com.a", moduleName = "alpha"),
            testRow(displayName = "Banana", packageName = "com.a", moduleName = "alpha"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "")

        assertEquals(listOf("alpha", "zeta"), modules.map { it.moduleName })
        assertEquals(
            listOf("Apple", "Banana"),
            modules.first().packages.single().previews.map { it.row.indexed.displayName },
        )
    }

    @Test
    fun `the query prunes branches with no surviving leaves`() {
        val rows = listOf(
            testRow(displayName = "TabsPreview", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "ButtonPreview", packageName = "com.b", moduleName = "design"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "tabs")

        assertEquals(1, modules.size)
        assertEquals("app", modules.single().moduleName)
        assertEquals(1, modules.single().count)
    }

    @Test
    fun `a query matching nothing yields no modules`() {
        assertTrue(PreviewTreeModelBuilder.build(listOf(testRow()), "zzz").isEmpty())
    }

    @Test
    fun `an empty input yields no modules`() {
        assertTrue(PreviewTreeModelBuilder.build(emptyList(), "").isEmpty())
    }
}
