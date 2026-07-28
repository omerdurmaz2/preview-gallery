package com.devomer.previewgallery.editor

import com.android.tools.idea.common.editor.SplitEditor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collapses a Compose preview editor, or every editor showing a file, to code-only.
 *
 * Must be called on the EDT: both `SplitEditor.selectTextMode` and `FileEditor.setLayout` touch Swing state.
 *
 * Android Studio's Compose preview lives in a `SplitEditor`, whose `selectTextMode(true)` records that the user
 * asked for code-only — without the flag the preview's preferred visibility can re-open the pane. Files opened in
 * a plain platform split editor fall back to `setLayout(SHOW_EDITOR)`; anything else is left untouched.
 */
object SplitEditorSwitcher {

    /** Collapses [editor] alone to code-only. */
    fun switchToCodeOnly(editor: FileEditor) {
        val split = parentSplitEditor(editor) ?: return
        if (!selectTextMode(split)) split.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR)
    }

    /** Collapses every editor showing [file] to code-only. Use [switchToCodeOnly] with an editor when one is known. */
    fun switchToCodeOnly(project: Project, file: VirtualFile) {
        for (editor in FileEditorManager.getInstance(project).getAllEditors(file)) {
            switchToCodeOnly(editor)
        }
    }

    private fun parentSplitEditor(editor: FileEditor): TextEditorWithPreview? =
        TextEditorWithPreview.getParentSplitEditor(editor)

    /**
     * Returns false when [split] is not the Android split editor, so the caller can use the platform path instead.
     *
     * The plugin declares a MANDATORY `<depends>com.android.tools.design</depends>`, so `SplitEditor` is always
     * present at runtime — this is not guarding an optional dependency. The `catch (LinkageError)` is cheap
     * insurance against a future Studio release removing or relocating the class, nothing more.
     */
    private fun selectTextMode(split: TextEditorWithPreview): Boolean = try {
        val androidSplit = split as? SplitEditor<*> ?: return false
        androidSplit.selectTextMode(true)
        true
    } catch (_: LinkageError) {
        false
    }
}
