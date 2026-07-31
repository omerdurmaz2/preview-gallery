package com.devomer.previewgallery.service

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * The modules that have adopted Compose Preview Screenshot Testing — the modules whose preview rows carry a
 * coverage badge at all (spec D5).
 *
 * The `src/screenshotTest` directory is looked for on disk rather than in the Gradle model on purpose: the
 * screenshot plugin is applied only under `-Pandroid.experimental.enableScreenshotTest=true`, so a project synced
 * without that flag may not report `screenshotTest` as a source root at all, while the sources are still
 * committed and present.
 *
 * That is also why the verdict is **corroborated against the index** rather than taken from disk alone. The two
 * inputs are independent: the directory comes from the VFS, the snapshot rows come from `FileBasedIndex` over
 * `projectScope`. When they disagree — a directory full of `.kt` files that produced no indexed `@PreviewTest`
 * row — the index is the one that is wrong (spec Risk 1), and trusting the disk would badge every preview in the
 * module `· no snapshot`: the loudest possible wrong signal, and the exact opposite of D10's "degrade to
 * `NotApplicable`, behave exactly as today". So a disagreement yields no badge at all, and the plan's manual
 * diagnostic ("if no badges appear anywhere, the source set is not reaching the index") keeps its meaning.
 */
object ScreenshotModuleDetector {

    private const val SCREENSHOT_TEST_PATH = "src/screenshotTest"
    private const val KOTLIN_EXTENSION = "kt"

    /**
     * What the filesystem says about one module: it has a `src/screenshotTest` directory, and whether that
     * directory holds any Kotlin source at all. An empty (or Kotlin-free) directory is a module that has adopted
     * screenshot testing and written no snapshots yet — a genuine zero, not an indexing gap.
     */
    data class Candidate(val moduleName: String, val hasKotlinSources: Boolean)

    /** Every module with a `src/screenshotTest` directory under one of its content roots. Needs a read action. */
    fun candidates(project: Project): List<Candidate> =
        ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val directory = screenshotTestDirectory(module) ?: return@mapNotNull null
            Candidate(module.name, containsKotlinSources(directory))
        }

    /**
     * The modules whose rows may carry a badge: those [candidates] the index corroborates, plus those whose
     * `src/screenshotTest` holds no Kotlin at all (nothing to corroborate — zero indexed rows is the truth
     * there, not a symptom).
     *
     * Pure, so both halves of the disagreement are unit-testable without a project. A module with indexed
     * snapshot rows is applicable whether or not it turned up as a candidate: rows the index actually produced
     * are evidence in their own right, and a layout this detector does not recognise must not hide them.
     */
    fun applicableModules(
        candidates: List<Candidate>,
        modulesWithIndexedSnapshots: Set<String>,
    ): Set<String> {
        val corroborated = candidates
            .filter { it.moduleName in modulesWithIndexedSnapshots || !it.hasKotlinSources }
            .map { it.moduleName }
        return (corroborated + modulesWithIndexedSnapshots).toSet()
    }

    private fun screenshotTestDirectory(module: Module): VirtualFile? =
        ModuleRootManager.getInstance(module).contentRoots
            .firstNotNullOfOrNull { root ->
                root.findFileByRelativePath(SCREENSHOT_TEST_PATH)?.takeIf { it.isDirectory }
            }

    /** Stops at the first `.kt` file: this only ever answers "any at all", never "how many". */
    private fun containsKotlinSources(directory: VirtualFile): Boolean {
        var found = false
        VfsUtilCore.processFilesRecursively(directory) { file ->
            if (!file.isDirectory && file.extension == KOTLIN_EXTENSION) found = true
            !found
        }
        return found
    }
}
