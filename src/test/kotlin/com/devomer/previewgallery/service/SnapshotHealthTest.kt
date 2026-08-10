package com.devomer.previewgallery.service

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The specimen is real: `DeleteSelectedProductsDialog_Preview` in the reference project rebuilds the dialog
 * with `PrimusDialog` instead of calling the component it is named after, and a sibling snapshot does call it.
 */
class SnapshotHealthTest {

    private val misnamedPreview = testRow(
        displayName = "DeleteSelectedProductsDialog_Preview",
        functionName = "DeleteSelectedProductsDialog_Preview",
        targets = listOf("PrimusDialog"),
    )

    private val honestSnapshot = testRow(
        displayName = "DeleteSelectedProductsDialog_Direct_Snapshot",
        functionName = "DeleteSelectedProductsDialog_Direct_Snapshot",
        isSnapshotTest = true,
        targets = listOf("DeleteSelectedProductsDialog"),
    )

    @Test
    fun `a row named after a component it does not call is flagged with both sides`() {
        val result = SnapshotHealth.check(listOf(misnamedPreview, honestSnapshot))

        val finding = result.findings.single()
        assertEquals("com.example.FooKt.DeleteSelectedProductsDialog_Preview", finding.composableFqn)
        assertEquals("DeleteSelectedProductsDialog", finding.namedAfter)
        assertEquals(listOf("PrimusDialog"), finding.shows)
    }

    @Test
    fun `the row that does call it is not flagged`() {
        val result = SnapshotHealth.check(listOf(misnamedPreview, honestSnapshot))

        assertTrue(result.findings.toString(), result.findings.none { it.composableFqn.endsWith("_Direct_Snapshot") })
    }

    @Test
    fun `a name that is not a component anywhere is a description, not a claim`() {
        // Nothing in the project renders `DarkTheme`, so `DarkThemePreview` is telling you about the
        // configuration rather than claiming to show a component.
        val row = testRow(
            displayName = "DarkThemePreview",
            functionName = "DarkThemePreview",
            targets = listOf("HomeScreen"),
        )

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), SnapshotHealth.check(listOf(row)).findings)
    }

    @Test
    fun `a preview that shows what it is named after passes`() {
        val row = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", targets = listOf("Widget"))

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), SnapshotHealth.check(listOf(row)).findings)
    }

    @Test
    fun `a longer stem clears the row when the shorter one would not`() {
        // A component really named `Foo_Bar` must not be reported for failing to call `Foo`.
        val row = testRow(
            displayName = "Foo_Bar_Default_Snapshot",
            functionName = "Foo_Bar_Default_Snapshot",
            isSnapshotTest = true,
            targets = listOf("Foo_Bar"),
        )
        val other = testRow(displayName = "FooPreview", functionName = "FooPreview", targets = listOf("Foo"))

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), SnapshotHealth.check(listOf(row, other)).findings)
    }

    @Test
    fun `the SideBarItemShimmer calibration finding, a preview that is its own component is not accused`() {
        // hepsi-android calibration: SideBarItemShimmer is an @Preview composable whose body renders its
        // own Column/Spacer/Box. It lands in the vocabulary only because a sibling row calls it, but its
        // own name carries no Preview/Snapshot suffix, so that full name is an identity, not a claim.
        val shimmer = testRow(
            displayName = "SideBarItemShimmer",
            functionName = "SideBarItemShimmer",
            targets = listOf("Column", "Spacer", "Box"),
        )
        val caller = testRow(
            displayName = "SideBarItemPreview",
            functionName = "SideBarItemPreview",
            targets = listOf("SideBarItemShimmer"),
        )

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), SnapshotHealth.check(listOf(shimmer, caller)).findings)
    }

    @Test
    fun `a row with no resolved targets is skipped and counted, not accused`() {
        val row = testRow(displayName = "MysteryPreview", functionName = "MysteryPreview")

        val result = SnapshotHealth.check(listOf(row))

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), result.findings)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `stems yield every prefix, so the longest can clear a row`() {
        assertEquals(listOf("Widget"), SnapshotHealth.stems("WidgetPreview"))
        assertEquals(listOf("DeleteSelectedProductsDialog"), SnapshotHealth.stems("DeleteSelectedProductsDialog_Preview"))
        assertEquals(
            listOf("Foo", "Foo_Bar", "Foo_Bar_Default"),
            SnapshotHealth.stems("Foo_Bar_Default_Snapshot"),
        )
    }

    @Test
    fun `stems drops the unsuffixed name's own full length, keeping shorter stems as real claims`() {
        assertEquals(emptyList<String>(), SnapshotHealth.stems("SideBarItemShimmer"))
        assertEquals(listOf("Foo"), SnapshotHealth.stems("Foo_Bar"))
    }

    @Test
    fun `findings are ordered so two runs of the same project diff cleanly`() {
        val z = testRow(displayName = "ZPreview", functionName = "ZPreview", targets = listOf("Other"))
        val a = testRow(displayName = "APreview", functionName = "APreview", targets = listOf("Other"))
        val vocabulary = testRow(displayName = "Seed", functionName = "Seed", targets = listOf("Z", "A", "Other"))

        val findings = SnapshotHealth.check(listOf(z, a, vocabulary)).findings

        assertEquals(findings.map { it.composableFqn }.sorted(), findings.map { it.composableFqn })
    }

    @Test
    fun `stems are checked shortest-first, so a row is named after the shortest stem the vocabulary knows`() {
        // The project's vocabulary contains both `Foo` and `Foo_Bar`. The row's own body calls neither
        // (it calls `Baz`), so it is accused — and `stems` puts `Foo` before `Foo_Bar`, so that is the
        // name pinned onto it, not the longer, more specific one.
        val row = testRow(
            displayName = "Foo_Bar_Preview",
            functionName = "Foo_Bar_Preview",
            targets = listOf("Baz"),
        )
        val vocabulary = testRow(displayName = "Seed", functionName = "Seed", targets = listOf("Foo", "Foo_Bar"))

        val finding = SnapshotHealth.check(listOf(row, vocabulary)).findings.single()

        assertEquals("Foo", finding.namedAfter)
    }
}
