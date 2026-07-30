package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ReferenceImage
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager

/**
 * Finds the reference PNGs the Compose Preview Screenshot Testing plugin committed for a snapshot function.
 *
 * The layout is fully derivable from facts the index already holds, so nothing here is stored: the directory
 * mirrors the package and then the JVM facade class, and the file name is
 * `<function>_<variant>_<configuration hash>_<index>.png`. The hash is a property of the `@Preview`
 * configuration, not of the function — `phone` hashes to the same value across every snapshot in the corpus —
 * so it is never computed, only skipped over.
 */
object ReferenceImageLocator {

    private const val REFERENCE_ROOT = "src/screenshotTestDebug/reference"
    private const val PNG_SUFFIX = ".png"
    private const val UNNAMED_VARIANT = "default"

    /** The directory a function's reference images live in, relative to a content root. */
    fun relativeDirectory(packageName: String, jvmClassName: String): String {
        val facade = jvmClassName.substringAfterLast('.')
        val packagePath = packageName.replace('.', '/')
        return if (packagePath.isEmpty()) "$REFERENCE_ROOT/$facade" else "$REFERENCE_ROOT/$packagePath/$facade"
    }

    /**
     * @return the variant segment of [fileName], or null when the name does not belong to [functionName] or does
     * not carry the trailing `_<hash>_<index>` the plugin appends. Rejecting rather than half-parsing is
     * deliberate: a half-parsed name would label an image with someone else's variant.
     */
    fun variantOf(fileName: String, functionName: String): String? {
        if (!fileName.endsWith(PNG_SUFFIX)) return null
        val stem = fileName.removeSuffix(PNG_SUFFIX)
        val prefix = "${functionName}_"
        if (!stem.startsWith(prefix)) return null
        val rest = stem.removePrefix(prefix).split('_')
        if (rest.size < 3) return null
        return rest.dropLast(2).joinToString("_").ifEmpty { UNNAMED_VARIANT }
    }

    /**
     * The committed reference images for [entry], sorted by variant so the strip's left-to-right order is
     * stable across selections.
     */
    fun locate(entry: PreviewEntry, module: Module): List<ReferenceImage> {
        val relative = relativeDirectory(entry.indexed.packageName, entry.indexed.jvmClassName)
        return ModuleRootManager.getInstance(module).contentRoots
            .mapNotNull { it.findFileByRelativePath(relative) }
            .flatMap { directory -> directory.children.orEmpty().toList() }
            .mapNotNull { file ->
                val variant = variantOf(file.name, entry.indexed.functionName) ?: return@mapNotNull null
                ReferenceImage(variant, file)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.variant })
    }
}
