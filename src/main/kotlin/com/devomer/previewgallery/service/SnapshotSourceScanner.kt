package com.devomer.previewgallery.service

import com.devomer.previewgallery.index.PreviewPsiScanner
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import java.io.IOException

/**
 * Reads the snapshot functions of the Compose Preview Screenshot Testing plugin straight from disk.
 *
 * The VFS is the channel rather than `PreviewIndex` for two reasons, and the second is the load-bearing one.
 * First, the screenshot plugin is applied only under `-Pandroid.experimental.enableScreenshotTest=true`, so a
 * project synced without that flag need not model `src/screenshotTest` as a source set at all — and this walk
 * does not care whether it does. Second, and decisively, **attribution**: a module-per-source-set import puts the
 * previews in `…ui.main` while `src/screenshotTest` sits under the holder module `…ui`, so an index row would be
 * filed under a module name that matches nothing (see [pickOwningModule]). Probing from content roots lets the
 * directory be attributed to the module that owns the previews.
 *
 * The parsing itself is the **same** [PreviewPsiScanner] the index runs, so a preview and its snapshot can never
 * be read by two different sets of rules.
 *
 * Callers must invoke [scan] and [directories] under a read action and off the EDT — both touch PSI and the
 * project model.
 */
object SnapshotSourceScanner {

    private const val SRC = "src"
    private const val SCREENSHOT_TEST = "screenshotTest"
    private const val KOTLIN_EXTENSION = "kt"
    private const val MAIN_SUFFIX = ".main"

    /**
     * The same cheap text gate `PreviewIndex` applies before it builds PSI. `@PreviewTest` contains the
     * substring, so no snapshot file is gated out by it.
     */
    private const val MARKER = "Preview"

    /** One module's `screenshotTest` directory, already attributed to the module that owns its previews. */
    data class Source(val moduleName: String, val directory: VirtualFile)

    /**
     * Every `screenshotTest` directory in the project, one entry per directory. A module with one is applicable —
     * though not the only way to be: `PreviewIndexService` also keeps a module the index produced snapshot rows
     * for, so a layout this probe cannot recognise is not silenced by it.
     */
    fun directories(project: Project): List<Source> {
        val byDirectory = LinkedHashMap<VirtualFile, LinkedHashSet<String>>()
        for (module in ModuleManager.getInstance(project).modules) {
            for (root in ModuleRootManager.getInstance(module).contentRoots) {
                val directory = probe(root) ?: continue
                // A set, not a list: a module's flavour and build-type roots (`src/main`, `src/debug`, `src/gms`)
                // all have `src` as their parent, so one module probes to the same directory once per root.
                byDirectory.getOrPut(directory) { LinkedHashSet() }.add(module.name)
            }
        }
        return byDirectory.mapNotNull { (directory, modules) ->
            pickOwningModule(modules)?.let { Source(it, directory) }
        }
    }

    /**
     * `<root>/src/screenshotTest` when the content root is the module directory, `<root>/../screenshotTest` when
     * the import split the source sets and the root is `<moduleDir>/src/<sourceSet>`. Those are the two shapes
     * the Android/JVM Gradle importer produces; anything else — a custom `screenshotTest.srcDir`, a content root
     * that is neither — yields no directory here, and `PreviewIndexService` falls back to whatever the index
     * managed to see for it.
     */
    internal fun probe(root: VirtualFile): VirtualFile? {
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
     *
     * **Android and JVM Gradle imports only.** A Kotlin Multiplatform import names its source-set modules
     * `…ui.androidMain` / `…ui.commonMain`, none of which ends in `.main`, so they fall through to the
     * shortest-name rule and the holder module wins — previews in `…ui.androidMain` would then not match. No
     * module in the reference project has that shape and supporting it is deliberately out of scope.
     */
    fun pickOwningModule(moduleNames: Collection<String>): String? =
        moduleNames.firstOrNull { it.endsWith(MAIN_SUFFIX) } ?: moduleNames.minByOrNull { it.length }

    /**
     * The module directory a `screenshotTest` source file belongs to: the parent of the `src` directory that
     * holds its source set, or null for a file that is not under one.
     *
     * This is how the reference-image strip finds its content root without asking the project model. Deriving it
     * from the file keeps the answer available for a file the model does not place in any module — the very case
     * the rest of this object exists for — where `ProjectFileIndex.getModuleForFile` returns null and the strip
     * would silently show nothing.
     */
    fun moduleDirectory(file: VirtualFile): VirtualFile? {
        var directory = file.parent
        while (directory != null) {
            val parent = directory.parent
            if (directory.name == SCREENSHOT_TEST && parent != null && parent.name == SRC) return parent.parent
            directory = parent
        }
        return null
    }

    /**
     * Every `@PreviewTest` function under a `screenshotTest` directory, as rows the gallery can join.
     *
     * [sources] is a parameter so a caller that already ran [directories] does not pay for a second probe of
     * every module in the project.
     */
    fun scan(project: Project, sources: List<Source> = directories(project)): List<PreviewEntry> {
        val psiManager = PsiManager.getInstance(project)
        return sources.flatMap { source ->
            kotlinFiles(source.directory).flatMap { file ->
                if (!mentionsPreview(file)) return@flatMap emptyList()
                // A file the platform will not give us as a KtFile is skipped, never fatal to the batch.
                val ktFile = psiManager.findFile(file) as? KtFile ?: return@flatMap emptyList()
                PreviewPsiScanner.scan(ktFile)
                    .filter { it.isSnapshotTest }
                    .map { PreviewEntry(it, source.moduleName, file) }
            }
        }
    }

    /**
     * Whether [file]'s text is worth building PSI for. Without this gate every `.kt` file in the source set is
     * parsed into an AST on every `PsiModificationTracker.MODIFICATION_COUNT` bump — that is, after any keystroke
     * in any Kotlin file — and `PreviewSearchEverywhereContributor` pays for it inside a blocking read action.
     *
     * An unsaved editor document wins over the bytes on disk, so a `@PreviewTest` that has just been typed
     * produces a row before the file is saved; the index behaves the same way for its own gate.
     */
    private fun mentionsPreview(file: VirtualFile): Boolean {
        FileDocumentManager.getInstance().getCachedDocument(file)?.let {
            return it.charsSequence.contains(MARKER)
        }
        return try {
            VfsUtilCore.loadText(file).contains(MARKER)
        } catch (e: IOException) {
            thisLogger().warn("Could not read snapshot source ${file.path}", e)
            false
        }
    }

    /**
     * Symlinks are deliberately not followed: a link back up the tree under `src/screenshotTest` would otherwise
     * make this walk recurse until it exhausted the stack, and a source set has no legitimate need of one.
     */
    private fun kotlinFiles(directory: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(
            directory,
            object : VirtualFileVisitor<Unit>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory && file.extension == KOTLIN_EXTENSION) result += file
                    return true
                }
            },
        )
        return result
    }
}
