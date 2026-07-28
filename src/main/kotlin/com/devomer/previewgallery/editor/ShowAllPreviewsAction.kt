package com.devomer.previewgallery.editor

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.ui.PreviewGalleryPanel
import com.devomer.previewgallery.ui.PreviewGalleryToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Hands the user off from Android Studio's per-file preview to the project-wide gallery: the editor collapses to
 * code-only and the tool window opens on the preview the caret sits in.
 *
 * The three effects are independent on purpose (design D7): a file whose previews are not indexed yet still gets
 * the code-only switch and the gallery, just without a selection.
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
        // Read the caret from the selected text editor rather than the event: the toolbar this button is injected
        // into belongs to the design surface, whose data context carries no editor.
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val caretOffset = editor?.caretModel?.offset ?: 0

        if (file != null) SplitEditorSwitcher.switchToCodeOnly(project, file)

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(PreviewGalleryToolWindowFactory.ID)
            ?: return
        toolWindow.activate({ if (file != null) revealCaretPreview(project, toolWindow, file, caretOffset) }, false)
    }

    /** Resolves the caret's preview off the EDT (the index read must not block it) and reveals it on the EDT. */
    private fun revealCaretPreview(project: Project, toolWindow: ToolWindow, file: VirtualFile, caretOffset: Int) {
        ReadAction.nonBlocking<String?> {
            CaretPreviewResolver.resolve(PreviewIndexService.getInstance(project).findAll(), file, caretOffset)?.id
        }
            .expireWith(toolWindow.disposable)
            .finishOnUiThread(ModalityState.defaultModalityState()) { entryId ->
                if (entryId == null) return@finishOnUiThread
                toolWindow.contentManager.contents
                    .firstNotNullOfOrNull { it.component as? PreviewGalleryPanel }
                    ?.revealEntry(entryId)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    companion object {
        const val ID = "PreviewGallery.ShowAllPreviews"
    }
}
