package com.devomer.previewgallery.ui

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageTreeBuilderTest {

    @Test fun `a single chain collapses into one branch`() {
        val tree = PackageTreeBuilder.build(listOf(testRow(packageName = "com.trendyol.buy.basket")))

        val branch = tree.branches.single()
        assertEquals("com.trendyol.buy.basket", branch.segment)
        assertTrue(branch.branches.isEmpty())
        assertEquals(1, branch.previews.size)
    }

    @Test fun `compaction stops at the forking segment`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.trendyol.buy.basket"),
                testRow(displayName = "B", packageName = "com.trendyol.buy.checkout"),
            ),
        )

        val buy = tree.branches.single()
        assertEquals("com.trendyol.buy", buy.segment)
        assertEquals(listOf("basket", "checkout"), buy.branches.map { it.segment })
    }

    @Test fun `a branch holding its own previews is never compacted away`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.buy"),
                testRow(displayName = "B", packageName = "com.buy.basket"),
            ),
        )

        val buy = tree.branches.single()
        assertEquals("com.buy", buy.segment)
        assertEquals(listOf("A"), buy.previews.map { it.row.indexed.displayName })
        assertEquals(listOf("basket"), buy.branches.map { it.segment })
    }

    @Test fun `counts sum the whole subtree`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.buy"),
                testRow(displayName = "B", packageName = "com.buy.basket"),
                testRow(displayName = "C", packageName = "com.buy.checkout"),
            ),
        )

        assertEquals(3, tree.branches.single().count)
    }

    @Test fun `segments differing only in case stay separate`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.Buy"),
                testRow(displayName = "B", packageName = "com.buy"),
            ),
        )

        val root = tree.branches.single()
        assertEquals("com", root.segment)
        assertEquals(listOf("Buy", "buy"), root.branches.map { it.segment })
        assertEquals(2, root.count)
    }

    @Test fun `branches and previews are sorted case-insensitively`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "zebra", packageName = "com.root.zeta"),
                testRow(displayName = "Apple", packageName = "com.root.alpha"),
                testRow(displayName = "banana", packageName = "com.root.alpha"),
            ),
        )

        val root = tree.branches.single()
        assertEquals(listOf("alpha", "zeta"), root.branches.map { it.segment })
        assertEquals(
            listOf("Apple", "banana"),
            root.branches.first().previews.map { it.row.indexed.displayName },
        )
    }

    @Test fun `previews in the default package hang off the tree root`() {
        val tree = PackageTreeBuilder.build(listOf(testRow(displayName = "A", packageName = "")))

        assertTrue(tree.branches.isEmpty())
        assertEquals(listOf("A"), tree.previews.map { it.row.indexed.displayName })
    }

    @Test fun `no rows yields an empty tree`() {
        val tree = PackageTreeBuilder.build(emptyList<com.devomer.previewgallery.model.PreviewRow>())

        assertTrue(tree.branches.isEmpty())
        assertTrue(tree.previews.isEmpty())
    }
}
