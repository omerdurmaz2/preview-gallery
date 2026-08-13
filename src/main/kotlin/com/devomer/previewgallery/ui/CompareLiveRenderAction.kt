package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Renders the selected snapshot's composable inside the IDE and reports how far it is from the committed golden
 * (spec D4: the measurement runs when a human asks, never on its own — a false alarm the user triggered is a data
 * point, one that arrives unbidden accumulates behind them).
 *
 * Hidden rather than disabled when the selection is not a snapshot row, matching this panel's convention and
 * [VerifySnapshotsAction] beside it. `AllIcons.Actions.Diff` because this is the one control in the toolbar that
 * actually compares two images.
 */
class CompareLiveRenderAction(
    private val onCompare: () -> Unit,
    private val isAvailable: () -> Boolean,
) : AnAction(
    PreviewGalleryBundle.message("action.compareLiveRender.text"),
    PreviewGalleryBundle.message("action.compareLiveRender.text"),
    AllIcons.Actions.Diff,
), DumbAware {

    override fun actionPerformed(event: AnActionEvent) = onCompare()

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = isAvailable()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
