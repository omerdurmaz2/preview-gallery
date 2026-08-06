package com.devomer.previewgallery.service

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The proof of the defect this phase exists for: `updateDebugScreenshotTest` run from a terminal writes PNGs the
 * VFS has not heard about, and the panel then tells the user to run the command they just ran.
 *
 * Every file here is written with `java.io.File`, deliberately bypassing the VFS, and the tree is fully loaded
 * first — a directory whose children were never cached would answer from disk on demand and hide the bug.
 */
class ReferenceRootsRefreshTest : BasePlatformTestCase() {

    private val facadeDirectory = "reference/com/example/WidgetSnapshotsKt"

    private lateinit var moduleDirectory: File

    override fun setUp() {
        super.setUp()
        moduleDirectory = FileUtil.createTempDirectory("preview-gallery-refresh", null)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(moduleDirectory)
        } finally {
            super.tearDown()
        }
    }

    private fun fileOnDisk(relativePath: String) {
        val file = File(moduleDirectory, relativePath)
        FileUtil.createParentDirs(file)
        file.writeText("")
    }

    private fun moduleVirtualFile(): VirtualFile = requireNotNull(
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleDirectory),
    ) { "The temp module directory must be visible in the VFS" }

    private fun loadEveryChild(root: VirtualFile) {
        VfsUtilCore.visitChildrenRecursively(
            root,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean = true
            },
        )
    }

    private fun pngCount(module: VirtualFile): Int = ReferenceRoots.of(module).sumOf { root ->
        root.directory.findFileByRelativePath("com/example/WidgetSnapshotsKt")?.children.orEmpty().size
    }

    fun `test a PNG written outside the IDE is invisible until the refresh`() {
        fileOnDisk("src/screenshotTestDebug/$facadeDirectory/Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val module = moduleVirtualFile()
        loadEveryChild(module)

        fileOnDisk("src/screenshotTestDebug/$facadeDirectory/Widget_Default_Snapshot_small_72f29e0e_0.png")
        assertEquals(1, pngCount(module))

        ReferenceRoots.refresh(module)

        assertEquals(2, pngCount(module))
    }

    fun `test a variant directory created outside the IDE is invisible until the refresh`() {
        fileOnDisk("src/screenshotTestDebug/$facadeDirectory/Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val module = moduleVirtualFile()
        loadEveryChild(module)

        fileOnDisk("src/screenshotTestGoogleDebug/$facadeDirectory/Widget_Default_Snapshot_phone_eee23ffd_0.png")
        assertEquals(1, ReferenceRoots.of(module).size)

        ReferenceRoots.refresh(module)

        assertEquals(2, ReferenceRoots.of(module).size)
    }

    fun `test a module with no src directory survives the refresh`() {
        val module = moduleVirtualFile()

        ReferenceRoots.refresh(module)

        assertEquals(emptyList<String>(), ReferenceRoots.of(module).map { it.sourceSetName })
    }

    fun `test a src tree deleted outside the IDE survives the refresh`() {
        fileOnDisk("src/screenshotTestDebug/$facadeDirectory/Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val module = moduleVirtualFile()
        loadEveryChild(module)

        FileUtil.delete(File(moduleDirectory, "src"))

        ReferenceRoots.refresh(module)

        assertEquals(emptyList<String>(), ReferenceRoots.of(module).map { it.sourceSetName })
    }
}
