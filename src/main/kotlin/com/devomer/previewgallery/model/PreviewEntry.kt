package com.devomer.previewgallery.model

import com.intellij.openapi.vfs.VirtualFile

/** An index value joined with the project-model context resolved at query time. */
data class PreviewEntry(
    override val indexed: IndexedPreview,
    override val moduleName: String,
    val file: VirtualFile,
) : PreviewRow {

    val id: String get() = "${indexed.composableFqn}#${indexed.displayName}"
}
