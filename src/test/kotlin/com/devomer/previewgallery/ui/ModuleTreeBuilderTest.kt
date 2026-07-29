package com.devomer.previewgallery.ui

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleTreeBuilderTest {

    @Test fun `modules nest by their dotted path`() {
        // A second, unrelated top-level module ("design") keeps the forest at two roots, so the shared-root
        // drop rule does not fire and this test observes plain nesting/compaction in isolation.
        val modules = ModuleTreeBuilder.build(
            listOf(
                testRow(displayName = "A", moduleName = "features.buy.basket"),
                testRow(displayName = "B", moduleName = "features.buy.checkout"),
                testRow(displayName = "C", moduleName = "design"),
            ),
        )

        // "features" has a single child ("buy"), so it compacts into that child, exactly like a package chain
        // does — the fork only happens at "buy", where basket/checkout diverge.
        val featuresBuy = modules.single { it.segment == "features.buy" }
        assertEquals(listOf("basket", "checkout"), featuresBuy.modules.map { it.segment })
    }

    @Test fun `a colon-separated module name nests the same way`() {
        val modules = ModuleTreeBuilder.build(
            listOf(
                testRow(displayName = "A", moduleName = "features:buy:basket"),
                testRow(displayName = "B", moduleName = "features:buy:checkout"),
                testRow(displayName = "C", moduleName = "design"),
            ),
        )

        // The joined label always uses '.', regardless of the original separator in the raw module name.
        val featuresBuy = modules.single { it.segment == "features.buy" }
        assertEquals(listOf("basket", "checkout"), featuresBuy.modules.map { it.segment })
    }

    @Test fun `a single-child chain compacts into one joined label`() {
        val modules = ModuleTreeBuilder.build(listOf(testRow(moduleName = "compose.ui")))

        val module = modules.single()
        assertEquals("compose.ui", module.segment)
        assertTrue(module.modules.isEmpty())
    }

    @Test fun `a module with previews of its own is not compacted away even with one child`() {
        val modules = ModuleTreeBuilder.build(
            listOf(
                // Default package, so this row hangs directly off the module row as one of its own previews,
                // rather than under a package branch.
                testRow(displayName = "A", packageName = "", moduleName = "compose"),
                testRow(displayName = "B", moduleName = "compose.ui"),
            ),
        )

        val compose = modules.single()
        assertEquals("compose", compose.segment)
        assertEquals(listOf("A"), compose.previews.map { it.row.indexed.displayName })
        assertEquals(listOf("ui"), compose.modules.map { it.segment })
    }

    @Test fun `counts sum the whole subtree including nested modules`() {
        // A second, unrelated top-level module ("design") keeps the forest at two roots, so the shared-root
        // drop rule does not fire and "features" survives as its own row to assert the count on.
        val modules = ModuleTreeBuilder.build(
            listOf(
                testRow(displayName = "A", moduleName = "features.buy.basket"),
                testRow(displayName = "B", moduleName = "features.buy.checkout"),
                testRow(displayName = "C", moduleName = "features.sell"),
                testRow(displayName = "D", moduleName = "design"),
            ),
        )

        assertEquals(3, modules.single { it.segment == "features" }.count)
    }

    @Test fun `segments differing only in case stay separate siblings`() {
        val modules = ModuleTreeBuilder.build(
            listOf(
                testRow(displayName = "A", moduleName = "Buy"),
                testRow(displayName = "B", moduleName = "buy"),
            ),
        )

        assertEquals(listOf("Buy", "buy"), modules.map { it.segment })
    }

    @Test fun `a shared single project root is dropped when it has two or more children`() {
        val modules = ModuleTreeBuilder.build(
            listOf(
                testRow(displayName = "A", moduleName = "MyApp.features"),
                testRow(displayName = "B", moduleName = "MyApp.compose"),
            ),
        )

        // "MyApp" itself holds no previews and no package branches, and forks into two modules, so it is
        // dropped rather than shown as a single noisy row above everything else.
        assertEquals(listOf("compose", "features"), modules.map { it.segment })
    }

    @Test fun `each level sorts case-insensitively`() {
        val modules = ModuleTreeBuilder.build(
            listOf(
                testRow(displayName = "A", moduleName = "zebra"),
                testRow(displayName = "B", moduleName = "Apple"),
                testRow(displayName = "C", moduleName = "banana"),
            ),
        )

        assertEquals(listOf("Apple", "banana", "zebra"), modules.map { it.segment })
    }
}
