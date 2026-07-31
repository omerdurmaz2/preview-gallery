package com.devomer.previewgallery

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil

/**
 * Runs [block] with [directory] marked as an excluded root of [module], then unmarks it.
 *
 * This is how a fixture reproduces a project whose `screenshotTest` source set never reached the IDE's model: an
 * excluded directory is outside `GlobalSearchScope.projectScope`, nothing under it is indexed, and
 * `ProjectFileIndex.getModuleForFile` returns null for its files — while the VFS still holds every one of them.
 * Without it a `BasePlatformTestCase` cannot tell the index channel from the VFS channel at all, because its
 * single content root puts everything in scope; a test written without it asserts something its own fixture
 * cannot produce.
 *
 * Removing the root again is not optional: the light project, and its module's root model with it, is shared by
 * every test in the run.
 */
internal fun <T> withExcludedRoot(module: Module, directory: VirtualFile, block: () -> T): T {
    PsiTestUtil.addExcludedRoot(module, directory)
    try {
        return block()
    } finally {
        PsiTestUtil.removeExcludedRoot(module, directory)
    }
}
