package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Starts a verify for the selected row's module, superseding whatever run is in flight (spec D2).
 *
 * Not `DumbAware` by accident — it is, because the runner itself refuses while indexing and reports why; a
 * disabled button during indexing would say less. Hidden rather than disabled when there is nothing to verify,
 * matching this panel's own convention.
 */
class VerifySnapshotsAction(
    private val onVerify: () -> Unit,
    private val isAvailable: () -> Boolean,
) : AnAction(
    PreviewGalleryBundle.message("action.verifySnapshots.text"),
    PreviewGalleryBundle.message("action.verifySnapshots.text"),
    AllIcons.Actions.Refresh,
), DumbAware {

    override fun actionPerformed(event: AnActionEvent) = onVerify()

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = isAvailable()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
