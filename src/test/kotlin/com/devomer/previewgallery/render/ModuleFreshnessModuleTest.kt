package com.devomer.previewgallery.render

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises [ModuleFreshness.isModuleFresh], [ModuleFreshness.newestModuleSourceMtime] and
 * [ModuleFreshness.invalidate] against a real [com.intellij.openapi.module.Module] — the project-model half
 * PG3-5 moved behind its own short read action.
 *
 * The source root is a **real directory on disk**, not the fixture's in-memory one. That is load-bearing rather
 * than incidental: [ModuleFreshness] resolves roots to `java.io.File` and reads their mtimes, so a `temp://`
 * root has no mtime at all and every assertion about the scan would hold no matter what the code did. The
 * PG20-8 review caught exactly that vacuity in the first version of these tests.
 *
 * The layout mirrors a module-per-source-set Gradle import — content root at `<moduleDir>/src/main`, snapshots
 * at the sibling `<moduleDir>/src/screenshotTest` that no module has as a source root — because that is the
 * shape the reference project has and the one the snapshot probe exists for.
 */
class ModuleFreshnessModuleTest : BasePlatformTestCase() {

    private lateinit var moduleDirectory: File
    private lateinit var mainSourceRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        moduleDirectory = FileUtil.createTempDirectory("preview-gallery-freshness", null)
        writeOnDisk("src/main/kotlin/com/example/Widget.kt", OLDER_MTIME)
        writeOnDisk("src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt", OLDER_MTIME)
        mainSourceRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(moduleDirectory, "src/main")),
        ) { "The temp source root must be visible in the VFS" }
        PsiTestUtil.addSourceContentToRoots(module, mainSourceRoot)
        ModuleFreshness.invalidate(module)
    }

    override fun tearDown() {
        try {
            PsiTestUtil.removeContentEntry(module, mainSourceRoot)
            ModuleFreshness.invalidate(module)
            FileUtil.delete(moduleDirectory)
        } finally {
            super.tearDown()
        }
    }

    private fun writeOnDisk(relativePath: String, mtime: Long): File {
        val file = File(moduleDirectory, relativePath)
        FileUtil.createParentDirs(file)
        file.writeText("")
        file.setLastModified(mtime)
        return file
    }

    fun `test a module with no linked Gradle project is conservatively stale`() {
        assertFalse(ModuleFreshness.isModuleFresh(module))
    }

    fun `test repeated calls agree with each other`() {
        val first = ModuleFreshness.isModuleFresh(module)
        val second = ModuleFreshness.isModuleFresh(module)

        assertEquals(first, second)
    }

    fun `test invalidate does not throw whether or not a result is cached`() {
        ModuleFreshness.invalidate(module) // nothing cached yet
        ModuleFreshness.isModuleFresh(module) // now something is
        ModuleFreshness.invalidate(module) // and it is cleared
    }

    fun `test the source clock reads a real file's mtime through the module's source roots`() {
        assertEquals(OLDER_MTIME, requireNotNull(ModuleFreshness.newestModuleSourceMtime(module)))
    }

    fun `test editing the snapshot test itself moves the source clock, though no module owns that root`() {
        // The PG20-8 review's Important: src/screenshotTest is not a source root of any module in a project
        // synced without the screenshot-test flag, so without the probe this edit was invisible and a completed
        // verify kept reading fresh — the dangerous direction.
        writeOnDisk("src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt", NEWER_MTIME)
        ModuleFreshness.invalidate(module)

        assertEquals(NEWER_MTIME, requireNotNull(ModuleFreshness.newestModuleSourceMtime(module)))
    }

    fun `test the source clock is cached within the TTL and recomputed after invalidate`() {
        // Without the cache this walk runs once per painted tree row, on the EDT, inside Swing's paint callback.
        assertEquals(OLDER_MTIME, requireNotNull(ModuleFreshness.newestModuleSourceMtime(module)))

        writeOnDisk("src/main/kotlin/com/example/Widget.kt", NEWER_MTIME)

        assertEquals(
            "expected the cached value while the TTL still holds",
            OLDER_MTIME,
            requireNotNull(ModuleFreshness.newestModuleSourceMtime(module)),
        )

        ModuleFreshness.invalidate(module)

        assertEquals(NEWER_MTIME, requireNotNull(ModuleFreshness.newestModuleSourceMtime(module)))
    }

    fun `test the non-blocking source clock reads unknown while cold and the real mtime once the refresh lands`() {
        val refreshed = CountDownLatch(1)

        assertNull(
            "a cold cache must not walk the tree on the caller's thread",
            ModuleFreshness.cachedModuleSourceMtime(module) { refreshed.countDown() },
        )
        assertTrue("the background refresh did not land", refreshed.await(10, TimeUnit.SECONDS))

        assertEquals(OLDER_MTIME, requireNotNull(ModuleFreshness.cachedModuleSourceMtime(module) {}))
    }

    fun `test the non-blocking source clock serves the expired value rather than unknown`() {
        assertEquals(OLDER_MTIME, requireNotNull(ModuleFreshness.newestModuleSourceMtime(module)))
        writeOnDisk("src/main/kotlin/com/example/Widget.kt", NEWER_MTIME)
        ModuleFreshness.expireCachesForTest(module)
        val refreshed = CountDownLatch(1)

        assertEquals(
            "an expired entry must still be served, or the badge flickers once every TTL",
            OLDER_MTIME,
            requireNotNull(ModuleFreshness.cachedModuleSourceMtime(module) { refreshed.countDown() }),
        )
        assertTrue("the background refresh did not land", refreshed.await(10, TimeUnit.SECONDS))

        assertEquals(
            "the refresh behind the expired value must land the new mtime",
            NEWER_MTIME,
            requireNotNull(ModuleFreshness.cachedModuleSourceMtime(module) {}),
        )
    }

    private companion object {
        /** Both far outside anything a test run could produce by accident, and exact multiples of 1000 so a
         *  filesystem storing only whole seconds still round-trips them. */
        const val OLDER_MTIME = 3_000_000_000_000L
        const val NEWER_MTIME = 3_000_000_060_000L
    }
}
