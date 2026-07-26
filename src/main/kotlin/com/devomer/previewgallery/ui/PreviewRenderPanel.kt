package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.Icon

/** The right side of the tool window's split. Shows the six [RenderState]s plus a persistent actions bar. */
class PreviewRenderPanel(private val project: Project) : JBPanel<PreviewRenderPanel>(BorderLayout()) {

    var onRender: (PreviewEntry) -> Unit = {}
    var onOpenFile: (PreviewEntry) -> Unit = {}

    /** Fires when the user clicks Properties, with a screen anchor for the popup to open next to the button
     *  (design/spec P4). Only ever wired to fire when [propertiesAvailable] is true — see [updateActionsBar]. */
    var onProperties: (PreviewEntry, RelativePoint) -> Unit = { _, _ -> }

    /** Fires when the user clicks a composable in the rendered image (PG4-5): the hit-tested node's source
     *  location. [PreviewGalleryPanel] resolves it to an editor open. Only ever fires for a [RenderState.LIVE]
     *  render whose [RenderOutcome.Success.viewTree] is non-empty (Feature B available); otherwise inert. */
    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}

    /** Whether Android Studio's picker API is available on this build (design D4/§5). Set once by the owner
     *  ([com.devomer.previewgallery.ui.PreviewGalleryPanel], from `PreviewPickerBridge.isAvailable()`) before
     *  the first [show] call. When false, the Properties action is never added — a missing API is invisible,
     *  not a dead control. */
    var propertiesAvailable: Boolean = false

    private val renderView = ZoomableRenderView()
    private val renderScroll = JBScrollPane(renderView)
    private val actionsBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0))
    private val centerPanel = JBPanel<JBPanel<*>>(BorderLayout())

    init {
        border = JBUI.Borders.empty(8)
        renderView.onNavigateToSource = { onNavigateToSource(it) }
        actionsBar.isOpaque = false
        add(actionsBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
    }

    fun show(view: RenderResultView, entry: PreviewEntry?) {
        centerPanel.removeAll()
        renderView.clearContent()
        when (view.state) {
            RenderState.IDLE -> center(idle())
            RenderState.RENDERING -> center(JBLabel(PreviewGalleryBundle.message("render.rendering")))
            RenderState.LIVE -> showImage(view.outcome as? RenderOutcome.Success)
            RenderState.NEEDS_BUILD -> center(JBLabel(PreviewGalleryBundle.message("render.building")))
            RenderState.FAILED -> center(failed(view.outcome as? RenderOutcome.Failure, entry))
            RenderState.UNSUPPORTED -> center(unsupported(view.outcome as? RenderOutcome.Unsupported, entry))
        }
        updateActionsBar(entry)
        revalidate(); repaint()
    }

    /** Rebuilds the persistent top-right actions bar as a native icon [com.intellij.openapi.actionSystem.ActionToolbar]
     *  (PG5-3, iconified): zoom/fit/hand-tool/export controls appear whenever there is a live render image; Properties
     *  is appended when the picker API is available. Rebuilt per [show] so the Properties anchor tracks the current
     *  entry and the toolbar reflects the current live/idle state. */
    private fun updateActionsBar(entry: PreviewEntry?) {
        actionsBar.removeAll()
        val group = DefaultActionGroup()
        if (renderView.rawImage() != null) {
            group.add(ZoomOutAction())
            group.add(ZoomInAction())
            group.add(FitAction())
            group.add(ActualSizeAction())
            group.addSeparator()
            group.add(HandToolAction())
            group.addSeparator()
            group.add(SavePngAction())
            group.add(CopyImageAction())
        }
        if (propertiesAvailable && entry != null) {
            if (group.childrenCount > 0) group.addSeparator()
            group.add(PropertiesAction(entry))
        }
        if (group.childrenCount > 0) {
            val toolbar = ActionManager.getInstance().createActionToolbar(ACTIONS_PLACE, group, true)
            toolbar.targetComponent = renderView
            toolbar.component.isOpaque = false
            actionsBar.add(toolbar.component)
            actionsBar.isVisible = true
        } else {
            actionsBar.isVisible = false
        }
    }

    private inner class ZoomOutAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.zoomOut"), null, AllIcons.General.ZoomOut,
    ) {
        override fun actionPerformed(e: AnActionEvent) { renderView.zoomFactor = ZoomMath.stepOut(renderView.zoomFactor) }
    }

    private inner class ZoomInAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.zoomIn"), null, AllIcons.General.ZoomIn,
    ) {
        override fun actionPerformed(e: AnActionEvent) { renderView.zoomFactor = ZoomMath.stepIn(renderView.zoomFactor) }
    }

    private inner class FitAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.fit"), null, AllIcons.General.FitContent,
    ) {
        override fun actionPerformed(e: AnActionEvent) { renderView.fitToViewport() }
    }

    private inner class ActualSizeAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.actualSize"), null, AllIcons.General.ActualZoom,
    ) {
        override fun actionPerformed(e: AnActionEvent) { renderView.zoomFactor = 1.0 }
    }

    /** Hand-tool as a real toggle so the toolbar shows its pressed state; mirrors [ZoomableRenderView.handToolActive]. */
    private inner class HandToolAction : ToggleAction(
        PreviewGalleryBundle.message("render.handTool"), null, HAND_ICON,
    ), DumbAware {
        override fun isSelected(e: AnActionEvent): Boolean = renderView.handToolActive
        override fun setSelected(e: AnActionEvent, state: Boolean) { renderView.handToolActive = state }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class SavePngAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.savePng"), null, AllIcons.ToolbarDecorator.Export,
    ) {
        override fun actionPerformed(e: AnActionEvent) { savePng() }
    }

    private inner class CopyImageAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.copyImage"), null, AllIcons.Actions.Copy,
    ) {
        override fun actionPerformed(e: AnActionEvent) { copyImage() }
    }

    /** Opens the Android Studio picker next to the clicked button (spec P4). The anchor is taken from the toolbar
     *  button that fired the action, falling back to the actions bar when the event carries no component. */
    private inner class PropertiesAction(private val entry: PreviewEntry) : DumbAwareAction(
        PreviewGalleryBundle.message("render.properties"), null, AllIcons.General.Settings,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val anchor = e.inputEvent?.component ?: actionsBar
            onProperties(entry, RelativePoint(anchor, Point(0, anchor.height)))
        }
    }

    private fun showImage(success: RenderOutcome.Success?) {
        val image = success?.image
        if (image == null) { center(JBLabel(PreviewGalleryBundle.message("render.failed"))); return }
        // Feed the hover/click overlay the plugin-owned view tree (empty when Feature B is unavailable — the
        // listeners then stay inert) and reset zoom to fit the current viewport.
        renderView.setContent(image, success.viewTree)
        centerPanel.add(renderScroll, BorderLayout.CENTER)
        // The scroll pane is only now placed in the visible hierarchy, and add() does not lay it out synchronously,
        // so its viewport still reports a 0x0 extent. Validate the subtree first so the viewport has its real size,
        // then fit — otherwise the first render (before any later revalidate) would show at 100% instead of Fit.
        centerPanel.validate()
        renderView.fitToViewport()
    }

    /** Writes the current raw render (no overlay) to a user-chosen PNG file (PG5-3/V2). No-op if there is no live
     *  image, or if the user cancels the save dialog. */
    private fun savePng() {
        val image = renderView.rawImage() ?: return
        val descriptor = FileSaverDescriptor(PreviewGalleryBundle.message("render.savePng"), "", "png")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, "preview.png") ?: return
        if (!RenderImageExporter.savePng(image, wrapper.file)) {
            notify(PreviewGalleryBundle.message("render.saveFailed"))
        }
    }

    /** Copies the current raw render (no overlay) to the system clipboard (PG5-3/V2). No-op if there is no live
     *  image. */
    private fun copyImage() {
        val image = renderView.rawImage() ?: return
        try {
            RenderImageExporter.copyToClipboard(image)
        } catch (e: Exception) {
            notify(PreviewGalleryBundle.message("render.copyFailed"))
        }
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Compose Preview Gallery")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    /** Nothing selected. Quiet by design: not an error, so no styling and no action. */
    private fun idle(): JBLabel = JBLabel(PreviewGalleryBundle.message("render.idle")).apply {
        foreground = UIUtil.getInactiveTextColor()
    }

    /** The Render button now appears only here, as a retry — selecting a stale module builds it automatically
     *  (D3/B3), so there is nothing left for the button to do on the automatic [RenderState.NEEDS_BUILD] path. */
    private fun failed(outcome: RenderOutcome.Failure?, entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel("${PreviewGalleryBundle.message("render.failed")}: ${outcome?.message ?: ""}"), BorderLayout.NORTH)
        if (entry != null) {
            add(ActionLink(PreviewGalleryBundle.message("render.render")) { onRender(entry) }, BorderLayout.CENTER)
            add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
        }
    }

    private fun unsupported(outcome: RenderOutcome.Unsupported?, entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel(outcome?.reason ?: PreviewGalleryBundle.message("render.unsupported")), BorderLayout.NORTH)
        if (entry != null) add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
    }

    private fun center(component: javax.swing.JComponent) {
        centerPanel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply { add(component, BorderLayout.CENTER) }, BorderLayout.CENTER)
    }

    private companion object {
        private const val ACTIONS_PLACE = "PreviewGalleryRenderToolbar"
        private val HAND_ICON: Icon = IconLoader.getIcon("/icons/hand.svg", PreviewRenderPanel::class.java)
    }
}
