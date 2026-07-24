package com.devomer.previewgallery.render

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.CompilerModuleExtension
import java.io.File

/**
 * A module's compiled output is "fresh" when no source file is newer than the newest class file. Cheap and
 * adequate (spec §6): a wrong answer costs at most one redundant build or one NEEDS_BUILD prompt.
 */
object ModuleFreshness {

    /** @return false (stale) when there is no class output at all, i.e. newestClassMtime <= 0. */
    fun isFresh(newestSourceMtime: Long, newestClassMtime: Long): Boolean =
        newestClassMtime > 0 && newestSourceMtime <= newestClassMtime

    fun isModuleFresh(module: Module): Boolean {
        val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots
        val output = CompilerModuleExtension.getInstance(module)?.compilerOutputPath
        val newestSource = sourceRoots.maxOfOrNull { newestMtime(File(it.path)) } ?: 0L
        val newestClass = output?.let { newestMtime(File(it.path)) } ?: 0L
        return isFresh(newestSource, newestClass)
    }

    private fun newestMtime(root: File): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
    }
}
