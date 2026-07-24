package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Image
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.SwingConstants

/** The right side of the tool window's split. Shows the six [RenderState]s plus a persistent actions bar. */
class PreviewRenderPanel(private val project: Project) : JBPanel<PreviewRenderPanel>(BorderLayout()) {

    var onRender: (PreviewEntry) -> Unit = {}
    var onOpenFile: (PreviewEntry) -> Unit = {}

    /** Fires when the user clicks Properties, with a screen anchor for the popup to open next to the button
     *  (design/spec P4). Only ever wired to fire when [propertiesAvailable] is true — see [updateActionsBar]. */
    var onProperties: (PreviewEntry, RelativePoint) -> Unit = { _, _ -> }

    /** Whether Android Studio's picker API is available on this build (design D4/§5). Set once by the owner
     *  ([com.devomer.previewgallery.ui.PreviewGalleryPanel], from `PreviewPickerBridge.isAvailable()`) before
     *  the first [show] call. When false, the Properties action is never added — a missing API is invisible,
     *  not a dead control. */
    var propertiesAvailable: Boolean = false

    private val imageLabel = JBLabel("", SwingConstants.CENTER)
    private var currentImage: BufferedImage? = null
    private val actionsBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0))
    private val centerPanel = JBPanel<JBPanel<*>>(BorderLayout())

    init {
        border = JBUI.Borders.empty(8)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = rescale()
        })
        actionsBar.isOpaque = false
        add(actionsBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
    }

    fun show(view: RenderResultView, entry: PreviewEntry?) {
        centerPanel.removeAll()
        currentImage = null
        when (view.state) {
            RenderState.IDLE -> center(idle())
            RenderState.RENDERING -> center(JBLabel(PreviewGalleryBundle.message("render.rendering")))
            RenderState.LIVE -> showImage((view.outcome as? RenderOutcome.Success)?.image)
            RenderState.NEEDS_BUILD -> center(JBLabel(PreviewGalleryBundle.message("render.building")))
            RenderState.FAILED -> center(failed(view.outcome as? RenderOutcome.Failure, entry))
            RenderState.UNSUPPORTED -> center(unsupported(view.outcome as? RenderOutcome.Unsupported, entry))
        }
        updateActionsBar(entry)
        revalidate(); repaint()
    }

    /** Rebuilds the persistent top-right actions bar. Currently just Properties, but kept separate from the
     *  state-specific center content so it survives every [show] without flicker. */
    private fun updateActionsBar(entry: PreviewEntry?) {
        actionsBar.removeAll()
        if (propertiesAvailable && entry != null) {
            actionsBar.add(propertiesAction(entry))
        }
        actionsBar.isVisible = actionsBar.componentCount > 0
    }

    private fun propertiesAction(entry: PreviewEntry): ActionLink {
        val link = ActionLink(PreviewGalleryBundle.message("render.properties"))
        // The anchor is derived from the link's own screen position once clicked (spec P4), not computed eagerly
        // at construction time, since the component is only laid out (and clickable) once actually shown.
        link.addActionListener { onProperties(entry, RelativePoint(link, Point(0, link.height))) }
        return link
    }

    private fun showImage(image: BufferedImage?) {
        if (image == null) { center(JBLabel(PreviewGalleryBundle.message("render.failed"))); return }
        currentImage = image
        centerPanel.add(imageLabel, BorderLayout.CENTER)
        rescale()
    }

    private fun rescale() {
        val img = currentImage ?: return
        val w = (width - 16).coerceAtLeast(1); val h = (height - 16).coerceAtLeast(1)
        val scale = minOf(w.toDouble() / img.width, h.toDouble() / img.height, 1.0)
        val sw = (img.width * scale).toInt().coerceAtLeast(1); val sh = (img.height * scale).toInt().coerceAtLeast(1)
        imageLabel.icon = ImageIcon(img.getScaledInstance(sw, sh, Image.SCALE_SMOOTH))
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
