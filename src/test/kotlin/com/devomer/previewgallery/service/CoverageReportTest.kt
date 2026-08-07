package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageReportTest {

    private fun covered(name: String, module: String = "app.main") =
        testRow(displayName = name, functionName = name, moduleName = module)
            .copy(coverage = SnapshotCoverage.Covered(1))

    private fun uncovered(name: String, module: String = "app.main") =
        testRow(displayName = name, functionName = name, moduleName = module)

    @Test
    fun `one module reports its totals and its uncovered rows`() {
        val markdown = CoverageReport.markdown(listOf(covered("APreview"), uncovered("BPreview")))

        assertEquals(
            """
            # Snapshot coverage

            **1/2 covered** across 1 module

            ## app.main — 1/2

            - `com.example.FooKt.BPreview`

            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `modules are listed alphabetically and counted independently`() {
        val markdown = CoverageReport.markdown(
            listOf(
                uncovered("ZPreview", module = "zeta.main"),
                covered("APreview", module = "alpha.main"),
                uncovered("BPreview", module = "alpha.main"),
            ),
        )

        assertTrue(markdown, markdown.contains("**1/3 covered** across 2 modules"))
        // zeta.main reads 0/1 rather than being left out: a module with no src/screenshotTest is the work the
        // report exists to expose (D6).
        assertTrue(markdown, markdown.indexOf("## alpha.main — 1/2") < markdown.indexOf("## zeta.main — 0/1"))
    }

    @Test
    fun `uncovered rows are listed by FQN and sorted`() {
        val markdown = CoverageReport.markdown(listOf(uncovered("ZPreview"), uncovered("APreview")))

        assertTrue(markdown, markdown.indexOf("`com.example.FooKt.APreview`") < markdown.indexOf("`com.example.FooKt.ZPreview`"))
    }

    @Test
    fun `a fully covered module keeps its heading and loses its bullets`() {
        val markdown = CoverageReport.markdown(listOf(covered("APreview"), covered("BPreview")))

        assertTrue(markdown, markdown.contains("## app.main — 2/2"))
        assertFalse(markdown, markdown.contains("- `"))
    }

    @Test
    fun `a project with no preview at all says so instead of emitting an empty report`() {
        val markdown = CoverageReport.markdown(emptyList())

        assertTrue(markdown, markdown.contains("No preview was found in this project."))
        assertFalse(markdown, markdown.contains("##"))
    }
}
