package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
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

    /** Fires when the user clicks a composable in the rendered image (PG4-5): the hit-tested node's source
     *  location. [PreviewGalleryPanel] resolves it to an editor open. Only ever fires for a [RenderState.LIVE]
     *  render whose [RenderOutcome.Success.viewTree] is non-empty (Feature B available); otherwise inert. */
    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}

    /** Whether Android Studio's picker API is available on this build (design D4/§5). Set once by the owner
     *  ([com.devomer.previewgallery.ui.PreviewGalleryPanel], from `PreviewPickerBridge.isAvailable()`) before
     *  the first [show] call. When false, the Properties action is never added — a missing API is invisible,
     *  not a dead control. */
    var propertiesAvailable: Boolean = false

    private val imageLabel = RenderImageLabel()
    private var currentImage: BufferedImage? = null
    private val actionsBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0))
    private val centerPanel = JBPanel<JBPanel<*>>(BorderLayout())

    init {
        border = JBUI.Borders.empty(8)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = rescale()
        })
        imageLabel.onNavigate = { onNavigateToSource(it) }
        actionsBar.isOpaque = false
        add(actionsBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
    }

    fun show(view: RenderResultView, entry: PreviewEntry?) {
        centerPanel.removeAll()
        currentImage = null
        imageLabel.clearContent()
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

    private fun showImage(success: RenderOutcome.Success?) {
        val image = success?.image
        if (image == null) { center(JBLabel(PreviewGalleryBundle.message("render.failed"))); return }
        currentImage = image
        // Feed the hover/click overlay the render-pixel size + the plugin-owned view tree (empty when Feature B
        // is unavailable — the listeners then stay inert).
        imageLabel.setContent(Dimension(image.width, image.height), success.viewTree)
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

    /**
     * The image label plus the interactive overlay (PG4-5): hover-highlights the innermost composable under the
     * cursor and reports a click on it as a navigation request. All geometry is plugin-owned — [PreviewViewNode]
     * bounds are in render-pixel space and [PreviewViewHitTester] maps between panel and render coordinates; no
     * `com.android.tools.*` here (design D3).
     *
     * ## Resize-safety
     *
     * [rescale] re-scales the icon on every [componentResized], and this component repaints on resize. Both the
     * mouse handlers and [paintComponent] derive the image's on-screen rectangle from the LIVE label size and the
     * CURRENT icon size ([currentDrawRect]) each time — never a cached rectangle — so the outline keeps tracking
     * the cursor after the window is resized. The rectangle is taken from the actual (already scaled, capped at
     * 1.0 by [rescale]) icon rather than [PreviewViewHitTester.imageDrawRect] precisely so it matches what is
     * painted, including the no-upscale cap.
     */
    private inner class RenderImageLabel : JBLabel("", SwingConstants.CENTER) {

        private var renderImageSize: Dimension? = null
        private var viewTree: List<PreviewViewNode> = emptyList()
        @Volatile private var hovered: PreviewViewNode? = null

        var onNavigate: (List<PreviewSourceLocation>) -> Unit = {}

        init {
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) = updateHover(e.point)
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick(e.point)
                override fun mouseExited(e: MouseEvent) = clearHover()
            })
        }

        /** A new render's image size + view tree; resets any lingering hover from the previous preview. */
        fun setContent(imageSize: Dimension, tree: List<PreviewViewNode>) {
            renderImageSize = imageSize
            viewTree = tree
            hovered = null
        }

        /** Non-image state: drop the tree + hover so a stale overlay never paints over a non-[RenderState.LIVE]
         *  state or the next preview before its render lands. */
        fun clearContent() {
            renderImageSize = null
            viewTree = emptyList()
            hovered = null
        }

        /** The rectangle the scaled icon actually occupies inside this label, centered — from the LIVE label size
         *  and the current icon size, so it stays correct across every resize. Null if there is no icon yet. */
        private fun currentDrawRect(): Rectangle? {
            val ic = icon ?: return null
            val iw = ic.iconWidth
            val ih = ic.iconHeight
            if (iw <= 0 || ih <= 0) return null
            return Rectangle((width - iw) / 2, (height - ih) / 2, iw, ih)
        }

        private fun renderPointAt(panelPoint: Point): Point? {
            val dim = renderImageSize ?: return null
            val rect = currentDrawRect() ?: return null
            return PreviewViewHitTester.toRenderPoint(panelPoint, rect, dim)
        }

        private fun updateHover(panelPoint: Point) {
            if (viewTree.isEmpty()) return
            val renderPoint = renderPointAt(panelPoint)
            val next = if (renderPoint == null) null else PreviewViewHitTester.innermostAt(viewTree, renderPoint)
            // Repaint only when the hovered node actually changes, so mouse-move stays cheap.
            if (next !== hovered) {
                hovered = next
                repaint()
            }
        }

        private fun clearHover() {
            if (hovered != null) {
                hovered = null
                repaint()
            }
        }

        private fun onClick(panelPoint: Point) {
            if (viewTree.isEmpty()) return
            val renderPoint = renderPointAt(panelPoint) ?: return
            val chain = PreviewViewHitTester.sourceChainAt(viewTree, renderPoint)
            if (chain.isNotEmpty()) onNavigate(chain)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val node = hovered ?: return
            val dim = renderImageSize ?: return
            val rect = currentDrawRect() ?: return
            if (dim.width <= 0 || dim.height <= 0) return
            val scaleX = rect.width.toDouble() / dim.width
            val scaleY = rect.height.toDouble() / dim.height
            val b = node.bounds
            val x = rect.x + (b.x * scaleX).toInt()
            val y = rect.y + (b.y * scaleY).toInt()
            val w = (b.width * scaleX).toInt().coerceAtLeast(0)
            val h = (b.height * scaleY).toInt().coerceAtLeast(0)
            val g2 = g.create() as Graphics2D
            try {
                g2.color = HOVER_OUTLINE
                g2.drawRect(x, y, w, h)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        /** A visible selection-blue outline for the hovered node, legible in both light and dark themes. */
        private val HOVER_OUTLINE = JBColor(Color(0x3574F0), Color(0x548AF7))
    }
}
