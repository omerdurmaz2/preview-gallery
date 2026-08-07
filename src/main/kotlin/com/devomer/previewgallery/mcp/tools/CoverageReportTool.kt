package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.service.CoverageReport

/**
 * The same markdown the toolbar's export writes, so a number an agent quotes and a number pasted from the IDE
 * cannot disagree. The format lives in [CoverageReport] and is pinned by its own tests; this only adapts rows.
 */
object CoverageReportTool {

    const val NAME = "coverage_report"

    const val DESCRIPTION =
        "Returns the project's snapshot coverage as markdown: X/Y covered overall and per module, with the " +
            "uncovered composables listed by FQN. Filter with `module` (exact match). For machine-readable " +
            "output use list_previews with uncoveredOnly instead."

    fun execute(snapshot: ProjectSnapshot, module: String?): String {
        val rows = snapshot.previews
            .filter { module == null || it.moduleName == module }
            .map(::asRow)
        return CoverageReport.markdown(rows)
    }

    private fun asRow(facts: PreviewFacts): PreviewRow = ReportRow(
        indexed = IndexedPreview(
            displayName = facts.displayName,
            functionName = facts.composableFqn.substringAfterLast('.'),
            packageName = facts.packageName,
            jvmClassName = facts.composableFqn.substringBeforeLast('.'),
            composableFqn = facts.composableFqn,
            offset = 0,
            annotationKind = AnnotationKind.ANDROIDX,
            isPrivate = facts.isPrivate,
            hasPreviewParameter = facts.hasPreviewParameter,
            previewGroup = null,
            unsupportedReason = facts.unsupportedReason,
        ),
        moduleName = facts.moduleName,
        coverage = if (facts.covered) {
            SnapshotCoverage.Covered(facts.snapshots.size)
        } else {
            SnapshotCoverage.Uncovered
        },
    )

    private data class ReportRow(
        override val indexed: IndexedPreview,
        override val moduleName: String,
        override val coverage: SnapshotCoverage,
    ) : PreviewRow
}
