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
            .copy(coverage = SnapshotCoverage.Uncovered)

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
        assertTrue(markdown, markdown.indexOf("## alpha.main — 1/2") < markdown.indexOf("## zeta.main — 0/1"))
    }

    @Test
    fun `uncovered rows are listed by FQN and sorted`() {
        val markdown = CoverageReport.markdown(listOf(uncovered("ZPreview"), uncovered("APreview")))

        assertTrue(markdown, markdown.indexOf("`com.example.FooKt.APreview`") < markdown.indexOf("`com.example.FooKt.ZPreview`"))
    }

    @Test
    fun `a module that never adopted screenshot testing is left out of the body and the totals`() {
        val markdown = CoverageReport.markdown(
            listOf(covered("APreview"), testRow(displayName = "CPreview", moduleName = "legacy.main")),
        )

        // NotApplicable modules would otherwise pin the percentage near zero forever: the reference project
        // has 1371 modules and one of them has adopted screenshot testing (spec D6).
        assertTrue(markdown, markdown.contains("**1/1 covered** across 1 module"))
        assertFalse(markdown, markdown.contains("legacy.main"))
    }

    @Test
    fun `a fully covered module keeps its heading and loses its bullets`() {
        val markdown = CoverageReport.markdown(listOf(covered("APreview"), covered("BPreview")))

        assertTrue(markdown, markdown.contains("## app.main — 2/2"))
        assertFalse(markdown, markdown.contains("- `"))
    }

    @Test
    fun `a project with no applicable module says so instead of emitting an empty report`() {
        val markdown = CoverageReport.markdown(listOf(testRow(displayName = "APreview")))

        assertTrue(markdown, markdown.contains("No module in this project has a `src/screenshotTest` source set."))
        assertFalse(markdown, markdown.contains("##"))
    }

    @Test
    fun `no rows at all is the same as no applicable module`() {
        assertEquals(CoverageReport.markdown(emptyList()), CoverageReport.markdown(listOf(testRow())))
    }
}
