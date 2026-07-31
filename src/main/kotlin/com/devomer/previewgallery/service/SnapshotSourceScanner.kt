package com.devomer.previewgallery.service

import com.devomer.previewgallery.index.PreviewPsiScanner
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile

/**
 * Reads the snapshot functions of the Compose Preview Screenshot Testing plugin straight from disk.
 *
 * `PreviewIndex` cannot be relied on for them: the screenshot plugin is applied only under
 * `-Pandroid.experimental.enableScreenshotTest=true`, so a project synced without that flag may leave
 * `src/screenshotTest` outside `GlobalSearchScope.projectScope` — verified against the reference project, where
 * the index produced zero snapshot rows while the files were plainly present and readable. So this walks the
 * VFS and parses the files itself, running the **same** [PreviewPsiScanner] the index runs, so a preview and its
 * snapshot can never be read by two different sets of rules.
 *
 * Callers must invoke [scan] and [directories] under a read action and off the EDT — both touch PSI and the
 * project model.
 */
object SnapshotSourceScanner {

    private const val SRC = "src"
    private const val SCREENSHOT_TEST = "screenshotTest"
    private const val KOTLIN_EXTENSION = "kt"
    private const val MAIN_SUFFIX = ".main"

    /** One module's `screenshotTest` directory, already attributed to the module that owns its previews. */
    data class Source(val moduleName: String, val directory: VirtualFile)

    /** Every `screenshotTest` directory in the project. A module with one is what "applicable" now means. */
    fun directories(project: Project): List<Source> {
        val byDirectory = LinkedHashMap<VirtualFile, MutableList<String>>()
        for (module in ModuleManager.getInstance(project).modules) {
            for (root in ModuleRootManager.getInstance(module).contentRoots) {
                val directory = probe(root) ?: continue
                byDirectory.getOrPut(directory) { mutableListOf() }.add(module.name)
            }
        }
        return byDirectory.mapNotNull { (directory, modules) ->
            pickOwningModule(modules)?.let { Source(it, directory) }
        }
    }

    /**
     * `<root>/src/screenshotTest` when the content root is the module directory, `<root>/../screenshotTest` when
     * the import split the source sets and the root is `<moduleDir>/src/main`. Those are the two layouts the
     * Gradle importer produces; anything else yields no directory, and the module is simply not applicable.
     */
    private fun probe(root: VirtualFile): VirtualFile? {
        root.findFileByRelativePath("$SRC/$SCREENSHOT_TEST")?.takeIf { it.isDirectory }?.let { return it }
        val parent = root.parent ?: return null
        if (parent.name != SRC) return null
        return parent.findChild(SCREENSHOT_TEST)?.takeIf { it.isDirectory }
    }

    /**
     * The module the rows are attributed to when several probe to the same directory.
     *
     * A module-per-source-set import gives `…ui` and `…ui.main` the same module directory, and the previews live
     * in `…ui.main`. Attributing the snapshots there is what makes them match: [SnapshotCoverageResolver] pairs
     * within one module name, so a snapshot filed under the holder module would never find its preview.
     */
    fun pickOwningModule(moduleNames: List<String>): String? =
        moduleNames.firstOrNull { it.endsWith(MAIN_SUFFIX) } ?: moduleNames.minByOrNull { it.length }

    /** Every `@PreviewTest` function under a `screenshotTest` directory, as rows the gallery can join. */
    fun scan(project: Project): List<PreviewEntry> {
        val psiManager = PsiManager.getInstance(project)
        return directories(project).flatMap { source ->
            kotlinFiles(source.directory).flatMap { file ->
                // A file the platform will not give us as a KtFile is skipped, never fatal to the batch.
                val ktFile = psiManager.findFile(file) as? KtFile ?: return@flatMap emptyList()
                PreviewPsiScanner.scan(ktFile)
                    .filter { it.isSnapshotTest }
                    .map { PreviewEntry(it, source.moduleName, file) }
            }
        }
    }

    private fun kotlinFiles(directory: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        VfsUtilCore.processFilesRecursively(directory) { file ->
            if (!file.isDirectory && file.extension == KOTLIN_EXTENSION) result += file
            true
        }
        return result
    }
}
