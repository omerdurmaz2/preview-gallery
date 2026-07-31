package com.devomer.previewgallery.service

import com.devomer.previewgallery.service.ScreenshotModuleDetector.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The corroboration rule, without a project: the disk half ([Candidate]) is supplied directly, so both sides of
 * the disagreement it exists to resolve can be stated as data.
 */
class ScreenshotModuleDetectorTest {

    @Test
    fun `a directory the index corroborates is applicable`() {
        val applicable = ScreenshotModuleDetector.applicableModules(
            listOf(Candidate("app", hasKotlinSources = true)),
            modulesWithIndexedSnapshots = setOf("app"),
        )

        assertEquals(setOf("app"), applicable)
    }

    @Test
    fun `a directory full of Kotlin with no indexed snapshot is not applicable`() {
        val applicable = ScreenshotModuleDetector.applicableModules(
            listOf(Candidate("app", hasKotlinSources = true)),
            modulesWithIndexedSnapshots = emptySet(),
        )

        // The indexing gap (spec Risk 1): the sources are on disk but never reached the index. Badging every row
        // `· no snapshot` would paint the whole module as failing; D10 says degrade to NotApplicable instead.
        assertEquals(emptySet<String>(), applicable)
    }

    @Test
    fun `an empty screenshotTest directory is a genuine zero and stays applicable`() {
        val applicable = ScreenshotModuleDetector.applicableModules(
            listOf(Candidate("app", hasKotlinSources = false)),
            modulesWithIndexedSnapshots = emptySet(),
        )

        // Adopted, nothing written yet: zero indexed rows is the truth here, so `· no snapshot` is correct.
        assertEquals(setOf("app"), applicable)
    }

    @Test
    fun `a module with no screenshotTest directory is not applicable`() {
        val applicable = ScreenshotModuleDetector.applicableModules(
            emptyList(),
            modulesWithIndexedSnapshots = emptySet(),
        )

        assertEquals(emptySet<String>(), applicable)
    }

    @Test
    fun `indexed snapshot rows count even when the directory was not recognised`() {
        val applicable = ScreenshotModuleDetector.applicableModules(
            emptyList(),
            modulesWithIndexedSnapshots = setOf("app"),
        )

        // Rows the index actually produced are evidence in their own right — a source layout this detector does
        // not recognise (a flavoured variant, say) must not hide snapshots that were indexed anyway.
        assertEquals(setOf("app"), applicable)
    }

    @Test
    fun `each module is judged on its own evidence`() {
        val applicable = ScreenshotModuleDetector.applicableModules(
            listOf(
                Candidate("indexed", hasKotlinSources = true),
                Candidate("gap", hasKotlinSources = true),
                Candidate("empty", hasKotlinSources = false),
            ),
            modulesWithIndexedSnapshots = setOf("indexed"),
        )

        assertEquals(setOf("indexed", "empty"), applicable)
    }
}
