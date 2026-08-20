package com.devomer.previewgallery.editor

import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.ui.PreviewGalleryPanel
import com.devomer.previewgallery.ui.PreviewGalleryToolWindowFactory
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Opens the gallery on the preview at one editor position — the whole behaviour of
 * [ShowAllPreviewsAction], extracted (PG24-3) so the gutter marker
 * ([ShowAllPreviewsLineMarkerProvider]) does the same thing rather than a similar thing.
 *
 * The three effects stay independent, exactly as design D7 requires of the action: a file whose previews are not
 * indexed yet still gets the code-only switch and the gallery, just without a selection.
 */
object PreviewGalleryNavigator {

    /**
     * Collapses [editorToCollapse] to code-only when there is one, opens the gallery tool window, and reveals the
     * preview [caretOffset] sits in.
     *
     * [editorToCollapse] is null for a caller with no editor to act on — the gutter marker has the click's own
     * editor and passes it; a Find Action invocation with no open split editor has none. A null skips the
     * collapse and changes nothing else.
     */
    fun openAt(project: Project, editorToCollapse: TextEditor?, file: VirtualFile?, caretOffset: Int) {
        if (editorToCollapse != null) SplitEditorSwitcher.switchToCodeOnly(editorToCollapse)
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
}
