package com.devomer.previewgallery.model

/**
 * File-local facts about one `@Preview` function. Everything here is serialized into the index, so it must not
 * depend on the project model: module membership and file paths are resolved at query time instead.
 */
data class IndexedPreview(
    val displayName: String,
    val functionName: String,
    val packageName: String,
    val jvmClassName: String,
    val composableFqn: String,
    val offset: Int,
    val annotationKind: AnnotationKind,
    val isPrivate: Boolean,
    val hasPreviewParameter: Boolean,
    val previewGroup: String?,
    /** Non-null when the preview cannot be rendered, e.g. because it is declared inside a class. */
    val unsupportedReason: String?,
)
