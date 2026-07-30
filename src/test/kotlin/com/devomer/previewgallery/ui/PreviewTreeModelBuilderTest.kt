package com.devomer.previewgallery.ui

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTreeModelBuilderTest {

    @Test
    fun `groups by module then package branch`() {
        val rows = listOf(
            testRow(displayName = "A", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "B", packageName = "com.b", moduleName = "app"),
            testRow(displayName = "C", packageName = "com.c", moduleName = "design"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, emptyList(), "")

        assertEquals(listOf("app", "design"), modules.map { it.segment })
        // com forks into a and b, so the shared prefix becomes one row with two children.
        val app = modules.first().branches.single()
        assertEquals("com", app.segment)
        assertEquals(listOf("a", "b"), app.branches.map { it.segment })
        // design has a single chain, so it compacts to one row.
        assertEquals(listOf("com.c"), modules.last().branches.map { it.segment })
    }

    @Test
    fun `module count is the number of previews below it`() {
        val rows = listOf(
            testRow(displayName = "A", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "B", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "C", packageName = "com.b", moduleName = "app"),
        )

        assertEquals(3, PreviewTreeModelBuilder.build(rows, emptyList(), "").single().count)
    }

    @Test
    fun `everything is sorted alphabetically`() {
        val rows = listOf(
            testRow(displayName = "Zebra", packageName = "com.z", moduleName = "zeta"),
            testRow(displayName = "Apple", packageName = "com.a", moduleName = "alpha"),
            testRow(displayName = "Banana", packageName = "com.a", moduleName = "alpha"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, emptyList(), "")

        assertEquals(listOf("alpha", "zeta"), modules.map { it.segment })
        assertEquals(
            listOf("Apple", "Banana"),
            modules.first().branches.single().previews.map { it.row.indexed.displayName },
        )
    }

    @Test
    fun `previews in the default package hang off the module row`() {
        val rows = listOf(testRow(displayName = "A", packageName = "", moduleName = "app"))

        val module = PreviewTreeModelBuilder.build(rows, emptyList(), "").single()

        assertTrue(module.branches.isEmpty())
        assertEquals(listOf("A"), module.previews.map { it.row.indexed.displayName })
    }

    @Test
    fun `the query prunes branches with no surviving leaves`() {
        val rows = listOf(
            testRow(displayName = "TabsPreview", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "ButtonPreview", packageName = "com.b", moduleName = "design"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, emptyList(), "tabs")

        assertEquals(1, modules.size)
        assertEquals("app", modules.single().segment)
        assertEquals(1, modules.single().count)
    }

    @Test
    fun `a query matching nothing yields no modules`() {
        assertTrue(PreviewTreeModelBuilder.build(listOf(testRow()), emptyList(), "zzz").isEmpty())
    }

    @Test
    fun `an empty input yields no modules`() {
        assertTrue(PreviewTreeModelBuilder.build(emptyList(), emptyList(), "").isEmpty())
    }

    @Test
    fun `a preview carries its snapshots as child leaves`() {
        val snapshot = testRow(
            displayName = "Widget_Default_Snapshot",
            functionName = "Widget_Default_Snapshot",
            isSnapshotTest = true,
            targets = listOf("Widget"),
        )
        val preview = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", targets = listOf("Widget"))
            .copy(snapshots = listOf(snapshot))
        val modules = PreviewTreeModelBuilder.build(listOf(preview), emptyList(), "")
        val leaf = modules.single().branches.single().previews.single()
        assertEquals(1, leaf.snapshots.size)
        assertEquals("Widget_Default_Snapshot", leaf.snapshots.single().row.indexed.functionName)
    }

    @Test
    fun `orphan snapshots land under their module's own branch`() {
        val preview = testRow(moduleName = "app")
        val orphan = testRow(
            displayName = "NoResultRenderer_Snapshot",
            functionName = "NoResultRenderer_Snapshot",
            moduleName = "app",
            isSnapshotTest = true,
            targets = listOf("NoResultRenderer"),
        )
        val modules = PreviewTreeModelBuilder.build(listOf(preview), listOf(orphan), "")
        val orphans = modules.single().orphans
        assertNotNull(orphans)
        assertEquals(1, orphans?.count)
    }

    @Test
    fun `the query filters previews and orphans independently`() {
        val preview = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", moduleName = "app")
        val orphan = testRow(
            displayName = "NoResultRenderer_Snapshot",
            functionName = "NoResultRenderer_Snapshot",
            moduleName = "app",
            isSnapshotTest = true,
        )
        val modules = PreviewTreeModelBuilder.build(listOf(preview), listOf(orphan), "NoResult")
        assertEquals(1, modules.single().orphans?.count)
        assertEquals(0, modules.single().branches.sumOf { it.count })
    }
}
