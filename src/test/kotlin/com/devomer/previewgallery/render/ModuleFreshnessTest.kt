package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the parts of [ModuleFreshness] that are pure or filesystem-only and so need no IDE project at all:
 * the [ModuleFreshness.isFresh] comparison, and the bounded scan [ModuleFreshness.newestMtimeBounded] that
 * replaced the old unbounded, whole-tree walk (PG3-5). See [ModuleFreshnessModuleTest] for the
 * [ModuleFreshness.isModuleFresh] / [ModuleFreshness.invalidate] half, which needs a real [Module].
 */
class ModuleFreshnessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── isFresh ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no class output at all is stale`() {
        assertFalse(ModuleFreshness.isFresh(newestSourceMtime = 0L, newestClassMtime = 0L))
        assertFalse(ModuleFreshness.isFresh(newestSourceMtime = 100L, newestClassMtime = 0L))
    }

    @Test
    fun `a source newer than the newest class file is stale`() {
        assertFalse(ModuleFreshness.isFresh(newestSourceMtime = 200L, newestClassMtime = 100L))
    }

    @Test
    fun `a source no newer than the newest class file is fresh`() {
        assertTrue(ModuleFreshness.isFresh(newestSourceMtime = 100L, newestClassMtime = 200L))
    }

    @Test
    fun `equal mtimes count as fresh`() {
        assertTrue(ModuleFreshness.isFresh(newestSourceMtime = 100L, newestClassMtime = 100L))
    }

    // ── newestMtimeBounded ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a directory that does not exist has no mtime`() {
        val missing = File(tempFolder.root, "does-not-exist")
        assertEquals(0L, ModuleFreshness.newestMtimeBounded(missing))
    }

    @Test
    fun `picks up a file within the depth bound`() {
        val file = tempFolder.newFile("Shallow.class")
        file.setLastModified(DISTINCTIVE_MTIME)

        assertEquals(DISTINCTIVE_MTIME, ModuleFreshness.newestMtimeBounded(tempFolder.root, maxDepth = 1))
    }

    @Test
    fun `a file beyond the depth bound is not seen`() {
        val deep = nestedFile(depth = 4)
        deep.setLastModified(DISTINCTIVE_MTIME)

        val newest = ModuleFreshness.newestMtimeBounded(tempFolder.root, maxDepth = 2)

        assertTrue("expected the deep file's own mtime to be excluded", newest < DISTINCTIVE_MTIME)
        assertTrue("expected the scan to still find something (the visited directories)", newest > 0L)
    }

    @Test
    fun `the same file is seen once the depth bound reaches it`() {
        val deep = nestedFile(depth = 4)
        deep.setLastModified(DISTINCTIVE_MTIME)

        assertEquals(DISTINCTIVE_MTIME, ModuleFreshness.newestMtimeBounded(tempFolder.root, maxDepth = 4))
    }

    /** Creates a file exactly [depth] directory levels below [TemporaryFolder.getRoot] (root itself is depth 0)
     *  and returns it, e.g. `depth = 2` creates `root/l0/Deep.class`. */
    private fun nestedFile(depth: Int): File {
        var dir = tempFolder.root
        repeat(depth - 1) { i -> dir = File(dir, "l$i").apply { mkdirs() } }
        return File(dir, "Deep.class").apply { createNewFile() }
    }

    private companion object {
        /** Year 2065 in epoch millis, an exact multiple of 1000 (so a filesystem that only stores whole-second
         *  precision still round-trips it exactly) and far outside anything a test run could produce by accident. */
        const val DISTINCTIVE_MTIME = 3_000_000_000_000L
    }
}
