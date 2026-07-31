package com.devomer.previewgallery.service

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * The module directory a snapshot's reference images hang under.
 *
 * Two answers, in this order and never the other way round. [SnapshotSourceScanner.moduleDirectory] derives it
 * from the file's own path and is what Phase 14 put on this path precisely to stop the strip depending on the
 * project model: when the model is the failing half, the rows still appear and every one of them reads
 * `NO_REFERENCE`, with nothing on screen saying why.
 *
 * The model is consulted only where the path rule has no answer — a snapshot outside
 * `<moduleDir>/src/screenshotTest`, which is the layout Phase 14's index fallback exists for and which showed no
 * images at all. Strictly additive: where the path rule answers, nothing here runs; where the model has no
 * answer either, the result is the empty strip it already was.
 *
 * Callers must be under a read action — the fallback reads the project model.
 */
object ModuleDirectoryResolver {

    private const val SRC = "src"

    fun resolve(project: Project, file: VirtualFile): VirtualFile? =
        SnapshotSourceScanner.moduleDirectory(file) ?: fromModel(project, file)

    /**
     * The first content root holding a `src` directory, rather than the first content root outright: a
     * module-per-source-set import gives the holder module several roots, and only the module directory itself
     * has the `src` the reference layout is expressed against.
     */
    private fun fromModel(project: Project, file: VirtualFile): VirtualFile? {
        val module = ModuleUtilCore.findModuleForFile(file, project) ?: return null
        return ModuleRootManager.getInstance(module).contentRoots
            .firstOrNull { it.findChild(SRC)?.isDirectory == true }
    }
}
