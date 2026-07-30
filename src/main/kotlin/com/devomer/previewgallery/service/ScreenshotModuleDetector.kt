package com.devomer.previewgallery.service

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager

/**
 * The modules that have adopted Compose Preview Screenshot Testing, identified by a `src/screenshotTest`
 * directory under a content root.
 *
 * The directory is looked for on disk rather than in the Gradle model on purpose: the screenshot plugin is
 * applied only under `-Pandroid.experimental.enableScreenshotTest=true`, so a project synced without that flag
 * may not report `screenshotTest` as a source root at all, while the sources are still committed and present.
 */
object ScreenshotModuleDetector {

    private const val SCREENSHOT_TEST_PATH = "src/screenshotTest"

    fun modulesWithSnapshots(project: Project): Set<String> =
        ModuleManager.getInstance(project).modules
            .filter { hasScreenshotTestDirectory(it) }
            .map { it.name }
            .toSet()

    private fun hasScreenshotTestDirectory(module: Module): Boolean =
        ModuleRootManager.getInstance(module).contentRoots.any { root ->
            root.findFileByRelativePath(SCREENSHOT_TEST_PATH)?.isDirectory == true
        }
}
