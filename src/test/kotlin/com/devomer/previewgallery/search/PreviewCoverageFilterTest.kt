package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.SnapshotCoverage
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewCoverageFilterTest {

    private val covered = testRow(displayName = "CoveredPreview").copy(coverage = SnapshotCoverage.Covered(2))
    private val uncovered = testRow(displayName = "UncoveredPreview").copy(coverage = SnapshotCoverage.Uncovered)
    private val notApplicable = testRow(displayName = "OtherModulePreview")

    private val rows = listOf(covered, uncovered, notApplicable)

    @Test
    fun `disabled passes every row through unchanged`() {
        assertEquals(rows, PreviewCoverageFilter.apply(rows, enabled = false))
    }

    @Test
    fun `enabled keeps only the uncovered rows`() {
        assertEquals(listOf(uncovered), PreviewCoverageFilter.apply(rows, enabled = true))
    }

    @Test
    fun `a module that never adopted screenshot testing is not work to do`() {
        // NotApplicable means the module has no src/screenshotTest at all, so the question has no answer for
        // it — a work queue holding every such module is one nobody reads (spec D2).
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(listOf(notApplicable), enabled = true))
    }

    @Test
    fun `an empty input yields an empty result either way`() {
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(emptyList<TestPreviewRow>(), enabled = true))
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(emptyList<TestPreviewRow>(), enabled = false))
    }
}
