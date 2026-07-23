package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind

/**
 * Identifies `@Preview` and `@PreviewParameter` annotations from the file's import list alone.
 *
 * A `FileBasedIndex` indexer must not resolve references outside the file it is indexing, so this never touches
 * anything but the text of the annotation reference and the imports declared in the same file.
 */
object PreviewAnnotationMatcher {

    const val ANDROIDX_PREVIEW = "androidx.compose.ui.tooling.preview.Preview"
    const val JETBRAINS_PREVIEW = "org.jetbrains.compose.ui.tooling.preview.Preview"
    const val ANDROIDX_PREVIEW_PARAMETER = "androidx.compose.ui.tooling.preview.PreviewParameter"
    const val JETBRAINS_PREVIEW_PARAMETER = "org.jetbrains.compose.ui.tooling.preview.PreviewParameter"

    private const val PREVIEW_SHORT_NAME = "Preview"
    private const val PREVIEW_PARAMETER_SHORT_NAME = "PreviewParameter"

    /** @return the annotation kind, or null when [reference] is not a Compose `@Preview`. */
    fun matchPreview(reference: String, imports: List<ImportInfo>): AnnotationKind? =
        match(reference, imports, PREVIEW_SHORT_NAME, ANDROIDX_PREVIEW, JETBRAINS_PREVIEW)

    fun isPreviewParameter(reference: String, imports: List<ImportInfo>): Boolean =
        match(
            reference,
            imports,
            PREVIEW_PARAMETER_SHORT_NAME,
            ANDROIDX_PREVIEW_PARAMETER,
            JETBRAINS_PREVIEW_PARAMETER,
        ) != null

    private fun match(
        reference: String,
        imports: List<ImportInfo>,
        shortName: String,
        androidxFqn: String,
        jetbrainsFqn: String,
    ): AnnotationKind? {
        kindOf(reference, androidxFqn, jetbrainsFqn)?.let { return it }
        if (reference.contains('.')) return null

        val boundByName = imports.filter { !it.isAllUnder && it.effectiveName() == reference }
        if (boundByName.isNotEmpty()) {
            val kinds = boundByName.mapNotNull { kindOf(it.importedFqn, androidxFqn, jetbrainsFqn) }.distinct()
            return when (kinds.size) {
                1 -> kinds.single()
                0 -> null // the name is bound to something else entirely
                else -> AnnotationKind.UNKNOWN
            }
        }

        if (reference != shortName) return null

        val starKinds = imports.filter { it.isAllUnder }
            .mapNotNull { kindOf("${it.importedFqn}.$shortName", androidxFqn, jetbrainsFqn) }
            .distinct()
        return if (starKinds.size == 1) starKinds.single() else AnnotationKind.UNKNOWN
    }

    private fun ImportInfo.effectiveName(): String = alias ?: importedFqn.substringAfterLast('.')

    private fun kindOf(fqn: String, androidxFqn: String, jetbrainsFqn: String): AnnotationKind? = when (fqn) {
        androidxFqn -> AnnotationKind.ANDROIDX
        jetbrainsFqn -> AnnotationKind.JETBRAINS
        else -> null
    }
}
