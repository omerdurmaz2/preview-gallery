package com.devomer.previewgallery.editor

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware

/**
 * Hands the user off from Android Studio's per-file preview to the project-wide gallery: the editor collapses to
 * code-only and the tool window opens on the preview the caret sits in.
 *
 * This class is the toolbar/Find Action route to that; [ShowAllPreviewsLineMarkerProvider] is the gutter route,
 * and both go through [PreviewGalleryNavigator] so they cannot drift apart. All this one adds is resolving *which*
 * editor the click came from, which a gutter click already knows.
 */
class ShowAllPreviewsAction : AnAction(
    PreviewGalleryBundle.message("action.showAllPreviews.text"),
    PreviewGalleryBundle.message("action.showAllPreviews.description"),
    AllIcons.Actions.ListFiles,
),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // The toolbar this button is injected into belongs to the design surface: CommonDataKeys.EDITOR carries no
        // editor there, but DesignSurface.uiDataSnapshot does sink PlatformCoreDataKeys.FILE_EDITOR — the exact
        // SplitEditor (a TextEditor) the clicked toolbar sits in. That is what makes this correct with the editor
        // split into panes: without it, the wrong pane's editor would be resolved and collapsed. The selected-editor
        // fallback only fires for the Find Action path, which has no toolbar data context at all.
        val eventEditor = event.getData(PlatformCoreDataKeys.FILE_EDITOR) as? TextEditor
        val selectedTextEditor = FileEditorManager.getInstance(project).selectedTextEditor
        val targetEditor = eventEditor ?: selectedTextEditor?.let(TextEditorProvider.getInstance()::getTextEditor)

        val editor = targetEditor?.editor ?: selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val caretOffset = editor?.caretModel?.offset ?: 0

        PreviewGalleryNavigator.openAt(project, targetEditor, file, caretOffset)
    }

    companion object {
        const val ID = "PreviewGallery.ShowAllPreviews"
    }
}
