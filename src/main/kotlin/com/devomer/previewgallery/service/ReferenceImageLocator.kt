package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ReferenceImage

/**
 * Finds the reference PNGs the Compose Preview Screenshot Testing plugin committed for a snapshot function.
 *
 * The layout is fully derivable from facts the index already holds, so nothing here is stored: under a root the
 * directory mirrors the package and then the JVM facade class, and the file name is
 * `<function>_<variant>_<configuration hash>_<index>.png`. The hash is a property of the `@Preview`
 * configuration, not of the function — `phone` hashes to the same value across every snapshot in the corpus —
 * so it is never computed, only skipped over. Which roots exist is [ReferenceRoots]' question, not this one's.
 */
object ReferenceImageLocator {

    private const val PNG_SUFFIX = ".png"
    private const val UNNAMED_VARIANT = "default"

    /** The directory a function's reference images live in, relative to a [ReferenceRoots.Root]. */
    fun packageDirectory(packageName: String, jvmClassName: String): String {
        val facade = jvmClassName.substringAfterLast('.')
        val packagePath = packageName.replace('.', '/')
        return if (packagePath.isEmpty()) facade else "$packagePath/$facade"
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
     * The committed reference images for [entry] across every root in [roots], sorted by source set and then by
     * variant so the strip's left-to-right order is stable across selections and one flavour's images stay
     * contiguous.
     *
     * Every root contributes. Picking one would hide a golden committed for a single flavour, which is the
     * failure this signature replaced: a module whose references live under `screenshotTestHuaweiDebug` reported
     * none at all while the root was the constant `screenshotTestDebug`.
     */
    fun locate(entry: PreviewEntry, roots: List<ReferenceRoots.Root>): List<ReferenceImage> =
        roots.flatMap { locate(entry, it) }
            .sortedWith(
                compareBy<ReferenceImage, String>(String.CASE_INSENSITIVE_ORDER) { it.sourceSet }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.variant },
            )

    private fun locate(entry: PreviewEntry, root: ReferenceRoots.Root): List<ReferenceImage> {
        val relative = packageDirectory(entry.indexed.packageName, entry.indexed.jvmClassName)
        val directory = root.directory.findFileByRelativePath(relative) ?: return emptyList()
        return directory.children.orEmpty().mapNotNull { file ->
            val variant = variantOf(file.name, entry.indexed.functionName) ?: return@mapNotNull null
            ReferenceImage(root.token, variant, file)
        }
    }

    /**
     * The strip labels for [images], in the same order.
     *
     * The source set is prefixed only when the strip spans more than one: with a single root the variant alone
     * is unambiguous, and prefixing it would add noise to every module that has no flavours. The rule lives here
     * rather than in the panel because only a merged result knows whether it spans one root or two.
     */
    fun labels(images: List<ReferenceImage>): List<String> {
        val qualify = images.map { it.sourceSet }.distinct().size > 1
        return images.map { if (qualify) "${it.sourceSet} · ${it.variant}" else it.variant }
    }
}
