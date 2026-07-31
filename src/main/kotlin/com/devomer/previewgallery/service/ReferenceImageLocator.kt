package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ReferenceImage
import com.intellij.openapi.vfs.VirtualFile

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
     * @return the variant segment of [fileName], or null when the name does not start with `<functionName>_` or
     * does not carry the trailing `_<hash>_<index>` the plugin appends. A name that fits neither shape is
     * rejected rather than half-parsed, since a half-parsed name would label an image with someone else's
     * variant.
     *
     * **Known limitation — a prefix is not an identity.** When one function's name is a prefix of another's, the
     * shorter one over-collects the longer one's files and reports the difference as a variant:
     * `variantOf("Row_Wide_phone_eee23ffd_0.png", "Row")` returns `"Wide_phone"`, not null. Both names satisfy
     * every rule this function can apply — `Row_` really is the prefix, and the trailing two segments really are
     * a hash and an index — so validating those segments harder does not discriminate; nothing in the file name
     * distinguishes the two cases. Only the shorter sibling over-collects, and only when such a sibling exists in
     * the same facade class: `Row`'s strip would then show `Row_Wide`'s images under a bogus `Wide_phone` label,
     * while `Row_Wide`'s own strip stays correct. Fixing it needs the caller's set of sibling function names,
     * which is a different API than this one; the cost is a mislabelled extra image in a rare naming collision,
     * never a wrong or missing snapshot row.
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
     * The committed reference images for [entry] under [moduleDirectory], sorted by variant so the strip's
     * left-to-right order is stable across selections.
     *
     * [moduleDirectory] is a plain directory rather than a `Module` on purpose: the snapshot rows this is called
     * for come from [SnapshotSourceScanner], which exists precisely because the project model need not place
     * those files in a module at all. Asking `ProjectFileIndex` for the module here would reintroduce the
     * dependency the scanner removed, and its failure would be an empty strip with no explanation.
     */
    fun locate(entry: PreviewEntry, moduleDirectory: VirtualFile): List<ReferenceImage> {
        val relative = relativeDirectory(entry.indexed.packageName, entry.indexed.jvmClassName)
        val directory = moduleDirectory.findFileByRelativePath(relative) ?: return emptyList()
        return directory.children.orEmpty()
            .mapNotNull { file ->
                val variant = variantOf(file.name, entry.indexed.functionName) ?: return@mapNotNull null
                ReferenceImage(variant, file)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.variant })
    }
}
