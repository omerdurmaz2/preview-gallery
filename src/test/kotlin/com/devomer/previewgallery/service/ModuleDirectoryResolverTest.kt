package com.devomer.previewgallery.service

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The two ways a snapshot file reaches its module directory, and the one case that has neither.
 *
 * Phase 14 deliberately removed `ProjectFileIndex.getModuleForFile` from this path; it comes back here as a
 * **fallback only**, so the first test pins that the path derivation still owns every file it can answer for.
 */
class ModuleDirectoryResolverTest : BasePlatformTestCase() {

    private val tempDirectories = mutableListOf<File>()

    override fun tearDown() {
        try {
            tempDirectories.forEach { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    fun `test a file under the snapshot source set resolves by path`() {
        val file = myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile

        assertEquals(
            SnapshotSourceScanner.moduleDirectory(file),
            ModuleDirectoryResolver.resolve(project, file),
        )
    }

    fun `test a file the path rule cannot read falls back to the module content root`() {
        myFixture.addFileToProject("src/main/kotlin/com/example/Widget.kt", "package com.example\n")
        val file = myFixture.addFileToProject(
            "snapshots/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile

        assertNull(SnapshotSourceScanner.moduleDirectory(file))
        val resolved = ModuleDirectoryResolver.resolve(project, file)
        assertNotNull(resolved)
        assertNotNull(resolved?.findChild("src"))
    }

    fun `test a file in no module at all resolves to nothing`() {
        val outside = FileUtil.createTempDirectory("preview-gallery-outside", null)
        try {
            val onDisk = File(outside, "WidgetSnapshots.kt")
            onDisk.writeText("package com.example\n")
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk))

            assertNull(ModuleDirectoryResolver.resolve(project, file))
        } finally {
            FileUtil.delete(outside)
        }
    }

    fun `test the later content root wins when an earlier one has no src child`() {
        val withoutSrc = contentRoot(hasSrc = false)
        val withSrc = contentRoot(hasSrc = true)

        assertEquals(withSrc, ModuleDirectoryResolver.firstContentRootWithSrc(arrayOf(withoutSrc, withSrc)))
    }

    fun `test a single content root with a src child resolves to itself`() {
        val withSrc = contentRoot(hasSrc = true)

        assertEquals(withSrc, ModuleDirectoryResolver.firstContentRootWithSrc(arrayOf(withSrc)))
    }

    fun `test no content root with a src child resolves to null`() {
        val first = contentRoot(hasSrc = false)
        val second = contentRoot(hasSrc = false)

        assertNull(ModuleDirectoryResolver.firstContentRootWithSrc(arrayOf(first, second)))
    }

    /**
     * A real temp directory on disk, deleted in [tearDown] — not `myFixture.addFileToProject`, whose light
     * fixture gives every module exactly one content root, so a case with several never arises through it.
     */
    private fun contentRoot(hasSrc: Boolean): VirtualFile {
        val directory = FileUtil.createTempDirectory("preview-gallery-content-root", null)
        tempDirectories += directory
        if (hasSrc) FileUtil.createDirectory(File(directory, "src"))
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory))
    }
}
