package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point

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

    /** Rebuilds the persistent top-right actions bar. Zoom/fit/hand-tool/export controls (PG5-3) appear whenever
     *  there is a live render image; Properties is kept separate so both survive every [show] without flicker. */
    private fun updateActionsBar(entry: PreviewEntry?) {
        actionsBar.removeAll()
        if (renderView.rawImage() != null) {
            renderControls().forEach { actionsBar.add(it) }
        }
        if (propertiesAvailable && entry != null) {
            actionsBar.add(propertiesAction(entry))
        }
        actionsBar.isVisible = actionsBar.componentCount > 0
    }

    /** Zoom out/in, fit, actual-size, hand-tool toggle, and export controls for the live [renderView] (PG5-3). */
    private fun renderControls(): List<javax.swing.JComponent> {
        val zoomOut = ActionLink("−") { renderView.zoomFactor = ZoomMath.stepOut(renderView.zoomFactor) }
        val zoomIn = ActionLink("+") { renderView.zoomFactor = ZoomMath.stepIn(renderView.zoomFactor) }
        val fit = ActionLink(PreviewGalleryBundle.message("render.fit")) { renderView.fitToViewport() }
        val actual = ActionLink(PreviewGalleryBundle.message("render.actualSize")) { renderView.zoomFactor = 1.0 }
        val hand = JBCheckBox(PreviewGalleryBundle.message("render.handTool")).apply {
            isSelected = renderView.handToolActive
            addActionListener { renderView.handToolActive = isSelected }
        }
        val save = ActionLink(PreviewGalleryBundle.message("render.savePng")) { savePng() }
        val copy = ActionLink(PreviewGalleryBundle.message("render.copyImage")) { copyImage() }
        return listOf(zoomOut, zoomIn, fit, actual, hand, save, copy)
    }

    private fun propertiesAction(entry: PreviewEntry): ActionLink {
        val link = ActionLink(PreviewGalleryBundle.message("render.properties"))
        // The anchor is derived from the link's own screen position once clicked (spec P4), not computed eagerly
        // at construction time, since the component is only laid out (and clickable) once actually shown.
        link.addActionListener { onProperties(entry, RelativePoint(link, Point(0, link.height))) }
        return link
    }

    private fun showImage(success: RenderOutcome.Success?) {
        val image = success?.image
        if (image == null) { center(JBLabel(PreviewGalleryBundle.message("render.failed"))); return }
        // Feed the hover/click overlay the plugin-owned view tree (empty when Feature B is unavailable — the
        // listeners then stay inert) and reset zoom to fit the current viewport.
        renderView.setContent(image, success.viewTree)
        centerPanel.add(renderScroll, BorderLayout.CENTER)
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
}
