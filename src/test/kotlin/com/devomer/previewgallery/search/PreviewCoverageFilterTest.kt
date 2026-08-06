package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.SnapshotCoverage
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewCoverageFilterTest {

    private val covered = testRow(displayName = "CoveredPreview").copy(coverage = SnapshotCoverage.Covered(2))
    private val uncovered = testRow(displayName = "UncoveredPreview")

    private val rows = listOf(covered, uncovered)

    @Test
    fun `disabled passes every row through unchanged`() {
        assertEquals(rows, PreviewCoverageFilter.apply(rows, enabled = false))
    }

    @Test
    fun `enabled keeps only the uncovered rows`() {
        assertEquals(listOf(uncovered), PreviewCoverageFilter.apply(rows, enabled = true))
    }

    @Test
    fun `a preview in a module that never adopted screenshot testing is work to do`() {
        val noScreenshotTestModule = testRow(displayName = "OtherModulePreview", moduleName = "legacy.main")

        assertEquals(
            listOf(noScreenshotTestModule),
            PreviewCoverageFilter.apply(listOf(noScreenshotTestModule), enabled = true),
        )
    }

    @Test
    fun `an empty input yields an empty result either way`() {
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(emptyList<TestPreviewRow>(), enabled = true))
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(emptyList<TestPreviewRow>(), enabled = false))
    }
}
