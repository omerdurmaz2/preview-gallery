package com.devomer.previewgallery.editor

import com.android.tools.idea.common.editor.SplitEditor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collapses every editor showing [file] to code-only.
 *
 * Android Studio's Compose preview lives in a `SplitEditor`, whose `selectTextMode(true)` records that the user
 * asked for code-only — without the flag the preview's preferred visibility can re-open the pane. Files opened in
 * a plain platform split editor fall back to `setLayout(SHOW_EDITOR)`; anything else is left untouched.
 */
object SplitEditorSwitcher {

    fun switchToCodeOnly(project: Project, file: VirtualFile) {
        for (editor in FileEditorManager.getInstance(project).getAllEditors(file)) {
            val split = parentSplitEditor(editor) ?: continue
            if (!selectTextMode(split)) split.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR)
        }
    }

    private fun parentSplitEditor(editor: FileEditor): TextEditorWithPreview? =
        TextEditorWithPreview.getParentSplitEditor(editor)

    /** Returns false when the Android split editor class is unavailable, so the caller can use the platform path. */
    private fun selectTextMode(split: TextEditorWithPreview): Boolean = try {
        val androidSplit = split as? SplitEditor<*> ?: return false
        androidSplit.selectTextMode(true)
        true
    } catch (_: LinkageError) {
        false
    }
}
