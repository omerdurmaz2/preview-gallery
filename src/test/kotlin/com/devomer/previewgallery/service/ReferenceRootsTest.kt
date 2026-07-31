package com.devomer.previewgallery.service

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Root discovery against a **real** directory tree rather than the fixture's in-memory one.
 *
 * The temp directory is deliberately outside the project: `ReferenceRoots` takes a plain `VirtualFile` and
 * asks the project model nothing, so a fixture module would add nothing but setup. The real filesystem is
 * what [ReferenceRootsRefreshTest] needs, and using it here too keeps both files on one fixture shape.
 */
class ReferenceRootsTest : BasePlatformTestCase() {

    private lateinit var moduleDirectory: File

    override fun setUp() {
        super.setUp()
        moduleDirectory = FileUtil.createTempDirectory("preview-gallery-roots", null)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(moduleDirectory)
        } finally {
            super.tearDown()
        }
    }

    private fun directoryOnDisk(relativePath: String) {
        FileUtil.createDirectory(File(moduleDirectory, relativePath))
    }

    private fun moduleVirtualFile(): VirtualFile = requireNotNull(
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleDirectory),
    ) { "The temp module directory must be visible in the VFS" }

    fun `test a single variant directory yields one root`() {
        directoryOnDisk("src/screenshotTestDebug/reference")

        val roots = ReferenceRoots.of(moduleVirtualFile())

        assertEquals(listOf("screenshotTestDebug"), roots.map { it.sourceSetName })
        assertEquals(listOf("Debug"), roots.map { it.variant })
        assertEquals("reference", roots.single().directory.name)
    }

    fun `test two flavour directories yield two roots sorted by source set`() {
        directoryOnDisk("src/screenshotTestHuaweiDebug/reference")
        directoryOnDisk("src/screenshotTestGoogleDebug/reference")

        val roots = ReferenceRoots.of(moduleVirtualFile())

        assertEquals(
            listOf("screenshotTestGoogleDebug", "screenshotTestHuaweiDebug"),
            roots.map { it.sourceSetName },
        )
        assertEquals(listOf("googleDebug", "huaweiDebug"), roots.map { it.token })
    }

    fun `test the source directory is not a root`() {
        directoryOnDisk("src/screenshotTest/kotlin/com/example")

        assertEquals(emptyList<String>(), ReferenceRoots.of(moduleVirtualFile()).map { it.sourceSetName })
    }

    fun `test a root with no variant suffix has no variant and falls back to its own name`() {
        directoryOnDisk("src/screenshotTest/reference")

        val root = ReferenceRoots.of(moduleVirtualFile()).single()

        assertNull(root.variant)
        assertEquals("screenshotTest", root.token)
    }

    fun `test a module with no src directory yields nothing`() {
        assertEquals(emptyList<String>(), ReferenceRoots.of(moduleVirtualFile()).map { it.sourceSetName })
    }

    fun `test a source set that is not a screenshot test one is ignored`() {
        directoryOnDisk("src/main/reference")

        assertEquals(emptyList<String>(), ReferenceRoots.of(moduleVirtualFile()).map { it.sourceSetName })
    }
}
