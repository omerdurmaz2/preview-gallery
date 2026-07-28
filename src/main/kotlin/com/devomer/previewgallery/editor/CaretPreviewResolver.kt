package com.devomer.previewgallery.editor

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.vfs.VirtualFile

/**
 * Picks the preview a caret is "in" using only indexed data.
 *
 * [IndexedPreview.offset] marks a preview's declaration, not its body range, so this approximates containment:
 * the last preview declared at or before the caret wins. A caret above the first preview (imports, package
 * statement) falls back to the file's first preview rather than resolving to nothing.
 */
object CaretPreviewResolver {

    fun resolve(entries: List<PreviewEntry>, file: VirtualFile, caretOffset: Int): PreviewEntry? {
        val inFile = entries.filter { it.file == file }
        if (inFile.isEmpty()) return null
        return inFile.filter { it.indexed.offset <= caretOffset }.maxByOrNull { it.indexed.offset }
            ?: inFile.minByOrNull { it.indexed.offset }
    }
}
