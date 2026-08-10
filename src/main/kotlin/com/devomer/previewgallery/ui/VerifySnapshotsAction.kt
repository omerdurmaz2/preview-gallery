package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Starts a verify for the selected row's module, superseding whatever run is in flight (spec D2).
 *
 * `DumbAware`: [SnapshotVerifyRunner.verify] refuses outright while the project is indexing, logging at debug
 * and reporting [SnapshotVerifyRunner.Outcome.NOT_RUN] rather than throwing — a disabled button here would say
 * no more than an enabled one that quietly does nothing. Surfacing that refusal, and `NOT_RUN` in general, to
 * the user is the display layer's job, not this action's. Hidden rather than disabled when there is nothing to
 * verify, matching this panel's own convention.
 *
 * `AllIcons.Actions.Execute` rather than `Refresh` or `Diff`, both of which are already on screen next to it:
 * [RefreshAction] three slots away in this toolbar, and `PreviewRenderPanel.ShowReferenceAction` in the render
 * pane's toolbar, which the tool window shows at the same time. Two buttons that look identical and do unrelated
 * things is worse than a less obvious glyph — and running a Gradle task is what this literally does.
 */
class VerifySnapshotsAction(
    private val onVerify: () -> Unit,
    private val isAvailable: () -> Boolean,
) : AnAction(
    PreviewGalleryBundle.message("action.verifySnapshots.text"),
    PreviewGalleryBundle.message("action.verifySnapshots.text"),
    AllIcons.Actions.Execute,
), DumbAware {

    override fun actionPerformed(event: AnActionEvent) = onVerify()

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = isAvailable()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
