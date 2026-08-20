package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure [File] logic, so it needs no IDE project — [ModuleFreshnessTest] is the pattern this follows in the same
 * package. The [TemporaryFolder] rule gives every staleness case a real mtime on disk; a path that never touched
 * the filesystem would make [ScreenshotTestClasses.stateOf] pass no matter what it did with the clocks it was
 * handed, exactly the vacuity [ModuleFreshnessModuleTest] documents.
 */
class ScreenshotTestClassesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `directoryFor uses the lower-camel source set and the upper-camel task name for a plain variant`() {
        val moduleDirectory = tempFolder.newFolder("module")

        val directory = ScreenshotTestClasses.directoryFor(moduleDirectory, "Debug")

        assertEquals(
            File(moduleDirectory, "build/intermediates/built_in_kotlinc/debugScreenshotTest/compileDebugScreenshotTestKotlin/classes"),
            directory,
        )
    }

    @Test
    fun `directoryFor keeps the flavor prefix in both casings for a flavored variant`() {
        val moduleDirectory = tempFolder.newFolder("module")

        val directory = ScreenshotTestClasses.directoryFor(moduleDirectory, "GoogleDebug")

        assertEquals(
            File(
                moduleDirectory,
                "build/intermediates/built_in_kotlinc/googleDebugScreenshotTest/compileGoogleDebugScreenshotTestKotlin/classes",
            ),
            directory,
        )
    }

    @Test
    fun `stateOf reads Missing when the directory exists but holds no class file`() {
        val directory = tempFolder.newFolder("classes")
        File(directory, "readme.txt").apply { createNewFile() }.setLastModified(NEWER_MTIME)

        assertEquals(ScreenshotTestClasses.State.Missing, ScreenshotTestClasses.stateOf(directory, OLDER_MTIME))
    }

    @Test
    fun `a class file newer than the source clock reads Ready`() {
        val directory = tempFolder.newFolder("classes")
        File(directory, "Widget.class").apply { createNewFile() }.setLastModified(NEWER_MTIME)

        assertEquals(
            ScreenshotTestClasses.State.Ready(directory),
            ScreenshotTestClasses.stateOf(directory, OLDER_MTIME),
        )
    }

    @Test
    fun `a class file older than the source clock reads Stale`() {
        val directory = tempFolder.newFolder("classes")
        File(directory, "Widget.class").apply { createNewFile() }.setLastModified(OLDER_MTIME)

        assertEquals(
            ScreenshotTestClasses.State.Stale(directory),
            ScreenshotTestClasses.stateOf(directory, NEWER_MTIME),
        )
    }

    @Test
    fun `a null source clock reads Stale rather than Ready`() {
        val directory = tempFolder.newFolder("classes")
        File(directory, "Widget.class").apply { createNewFile() }.setLastModified(OLDER_MTIME)

        assertEquals(
            ScreenshotTestClasses.State.Stale(directory),
            ScreenshotTestClasses.stateOf(directory, null),
        )
    }

    @Test
    fun `newestClassMtime ignores a newer non-class file and finds a class nested several packages deep`() {
        val root = tempFolder.newFolder("classes")
        val nested = File(root, "com/example/deep/nested").apply { mkdirs() }
        File(nested, "Widget.class").apply { createNewFile() }.setLastModified(OLDER_MTIME)
        File(root, "manifest.txt").apply { createNewFile() }.setLastModified(NEWER_MTIME)

        assertEquals(OLDER_MTIME, ScreenshotTestClasses.newestClassMtime(root))
    }

    @Test
    fun `variantMatches accepts the same variant across the two casings it arrives in`() {
        assertTrue(ScreenshotTestClasses.variantMatches("GoogleDebug", "googleDebug"))
        assertTrue(ScreenshotTestClasses.variantMatches("Debug", "debug"))
    }

    @Test
    fun `variantMatches refuses another flavor of the same build type`() {
        assertFalse(ScreenshotTestClasses.variantMatches("GoogleDebug", "huaweiDebug"))
    }

    @Test
    fun `variantMatches refuses another build type of the same flavor`() {
        assertFalse(ScreenshotTestClasses.variantMatches("GoogleDebug", "googleRelease"))
    }

    @Test
    fun `variantMatches accepts an unknown selected variant rather than refusing the comparison`() {
        assertTrue(ScreenshotTestClasses.variantMatches("GoogleDebug", null))
    }

    private companion object {
        const val OLDER_MTIME = 3_000_000_000_000L
        const val NEWER_MTIME = 3_000_000_060_000L
    }
}
