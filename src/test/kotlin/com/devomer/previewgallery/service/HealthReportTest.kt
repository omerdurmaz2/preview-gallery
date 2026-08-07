package com.devomer.previewgallery.service

import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReportTest {

    private val blank = GoldenInspector.BlankFinding(
        composableFqn = "com.example.SheetSnapshotsKt.Sheet_Collapsed_Snapshot",
        moduleName = "app.main",
        variant = "phone",
        path = "/src/reference/Sheet_Collapsed_0.png",
    )

    private val misnamed = SnapshotHealth.NameFinding(
        composableFqn = "com.example.DialogKt.DeleteSelectedProductsDialog_Preview",
        moduleName = "app.main",
        namedAfter = "DeleteSelectedProductsDialog",
        shows = listOf("PrimusDialog"),
    )

    @Test
    fun `both kinds render under their own heading`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(listOf(misnamed), skipped = 0),
            GoldenInspector.Result(listOf(blank), unreadable = 0),
        )

        assertTrue(markdown, markdown.startsWith("## Health"))
        assertTrue(markdown, markdown.contains("### Blank goldens"))
        assertTrue(markdown, markdown.contains("/src/reference/Sheet_Collapsed_0.png"))
        assertTrue(markdown, markdown.contains("### Named after something they do not show"))
        assertTrue(markdown, markdown.contains("named after `DeleteSelectedProductsDialog`, shows `PrimusDialog`"))
    }

    @Test
    fun `a clean project says so rather than going silent`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(emptyList(), skipped = 0),
            GoldenInspector.Result(emptyList(), unreadable = 0),
        )

        assertTrue(markdown, markdown.contains("No blank goldens, and every row shows what it is named after."))
        assertTrue(markdown, !markdown.contains("###"))
    }

    @Test
    fun `the skipped count is reported, because it is the report's own confidence`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(emptyList(), skipped = 14),
            GoldenInspector.Result(emptyList(), unreadable = 2),
        )

        assertTrue(markdown, markdown.contains("14 rows skipped (no call targets resolved)"))
        assertTrue(markdown, markdown.contains("2 reference images could not be read"))
    }

    @Test
    fun `one finding of each kind reads in the singular`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(listOf(misnamed), skipped = 0),
            GoldenInspector.Result(listOf(blank), unreadable = 0),
        )

        assertTrue(markdown, markdown.contains("1 blank golden ·"))
        assertTrue(markdown, markdown.contains("1 row named after something it does not show"))
    }
}
