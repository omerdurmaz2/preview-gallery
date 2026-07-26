package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.JBColor
import com.intellij.ui.components.Magnificator
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities

/**
 * A zoomable, pannable view of a render image with the Phase 4 hover-outline / click-to-source overlay. Swing
 * only — the plugin-owned [PreviewViewNode] tree is in render-pixel space and [PreviewViewHitTester] maps it;
 * no `com.android.tools.*` here. Intended to live inside a `JBScrollPane`: [getPreferredSize] is the zoomed
 * image extent, so the scroll pane sizes its scrollbars to it. Wheel zoom/pan (including macOS trackpad
 * horizontal scroll) and macOS trackpad pinch-zoom are handled directly by this component, not left to the
 * scroll pane's own wheel handling — see [onWheel]/[panByWheel] and [installPinchZoom] for why.
 *
 * Coordinates: a mouse point in this component is in zoomed-image space, so `renderPoint = point / zoomFactor`
 * (no letterbox — the component's bounds ARE the zoomed image). When [handToolActive], drag pans the enclosing
 * viewport and the overlay is inert; otherwise hover outlines and click navigates (Phase 4).
 */
class ZoomableRenderView : JComponent() {

    private var image: BufferedImage? = null
    private var viewTree: List<PreviewViewNode> = emptyList()

    @Volatile private var hovered: PreviewViewNode? = null

    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}

    var zoomFactor: Double = 1.0
        set(value) {
            field = value.coerceIn(ZoomMath.LADDER.first(), ZoomMath.LADDER.last())
            revalidate() // preferredSize changed -> scroll pane updates scrollbars
            repaint()
        }

    var handToolActive: Boolean = false
        set(value) {
            field = value
            hovered = null
            cursor = if (value) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            repaint()
        }

    private var panStart: Point? = null

    init {
        isOpaque = true
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { if (!handToolActive) updateHover(e.point) }
            override fun mouseDragged(e: MouseEvent) { if (handToolActive) panBy(e) }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (handToolActive) panStart = e.point }
            override fun mouseReleased(e: MouseEvent) { panStart = null }
            override fun mouseClicked(e: MouseEvent) {
                if (!handToolActive && SwingUtilities.isLeftMouseButton(e)) navigateAt(e.point)
            }
            override fun mouseExited(e: MouseEvent) { if (hovered != null) { hovered = null; repaint() } }
        })
        addMouseWheelListener { e -> onWheel(e) }
        installPinchZoom()
    }

    /** A new render's image + view tree; resets zoom to [fitToViewport] and clears any prior hover. */
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>) {
        this.image = image
        this.viewTree = viewTree
        this.hovered = null
        fitToViewport()
    }

    fun clearContent() {
        image = null
        viewTree = emptyList()
        hovered = null
        revalidate(); repaint()
    }

    fun rawImage(): BufferedImage? = image

    fun fitToViewport() {
        val img = image ?: return
        val vp = enclosingViewport()?.extentSize ?: size
        zoomFactor = ZoomMath.fitFactor(vp, Dimension(img.width, img.height))
    }

    override fun getPreferredSize(): Dimension {
        val img = image ?: return Dimension(0, 0)
        return Dimension((img.width * zoomFactor).toInt().coerceAtLeast(1), (img.height * zoomFactor).toInt().coerceAtLeast(1))
    }

    override fun paintComponent(g: Graphics) {
        val img = image ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(img, 0, 0, (img.width * zoomFactor).toInt(), (img.height * zoomFactor).toInt(), null)
            val node = hovered
            if (!handToolActive && node != null) {
                val b = node.bounds
                g2.color = HOVER_OUTLINE
                g2.drawRect(
                    (b.x * zoomFactor).toInt(), (b.y * zoomFactor).toInt(),
                    (b.width * zoomFactor).toInt().coerceAtLeast(0), (b.height * zoomFactor).toInt().coerceAtLeast(0),
                )
            }
        } finally {
            g2.dispose()
        }
    }

    private fun renderPointOf(p: Point): Point? {
        if (image == null || zoomFactor <= 0.0) return null
        return Point((p.x / zoomFactor).toInt(), (p.y / zoomFactor).toInt())
    }

    private fun updateHover(p: Point) {
        if (viewTree.isEmpty()) return
        val rp = renderPointOf(p)
        val next = if (rp == null) null else PreviewViewHitTester.innermostAt(viewTree, rp)
        if (next !== hovered) { hovered = next; repaint() }
    }

    private fun navigateAt(p: Point) {
        if (viewTree.isEmpty()) return
        val rp = renderPointOf(p) ?: return
        val chain = PreviewViewHitTester.sourceChainAt(viewTree, rp)
        if (chain.isNotEmpty()) onNavigateToSource(chain)
    }

    private fun panBy(e: MouseEvent) {
        val start = panStart ?: return
        val viewport = enclosingViewport() ?: return
        val pos = viewport.viewPosition
        // Drag right -> content moves right -> view position decreases (bounded by the scroll pane).
        pos.translate(start.x - e.x, start.y - e.y)
        val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
        val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
        pos.x = pos.x.coerceIn(0, maxX)
        pos.y = pos.y.coerceIn(0, maxY)
        viewport.viewPosition = pos
        // panStart stays in this component's coordinates, which shift with the viewport, so keep it at the event.
        panStart = e.point
    }

    private fun onWheel(e: MouseWheelEvent) {
        if (!e.isControlDown) { panByWheel(e); return } // plain/shift wheel, incl. macOS trackpad scroll -> pan
        e.consume()
        val viewport = enclosingViewport()
        val old = zoomFactor
        val next = if (e.wheelRotation < 0) ZoomMath.stepIn(old) else ZoomMath.stepOut(old)
        if (next == old) return
        val cursorInView = viewport?.let { SwingUtilities.convertPoint(this, e.point, it) } ?: e.point
        val oldScroll = viewport?.viewPosition ?: Point(0, 0)
        zoomFactor = next
        if (viewport != null) {
            val target = ZoomMath.anchorScroll(cursorInView, old, next, oldScroll)
            val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
            val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
            viewport.viewPosition = Point(target.x.coerceIn(0, maxX), target.y.coerceIn(0, maxY))
        }
    }

    /**
     * Plain wheel pans vertically; Shift+wheel pans horizontally — the standard AWT convention, and also how
     * macOS reports a two-finger trackpad scroll (a horizontal swipe arrives as a plain [MouseWheelEvent] with
     * Shift synthesized by the AWT peer, never as a distinct "horizontal" event or axis field).
     *
     * This pans the viewport directly, the same way [panBy] (hand-tool drag) does, instead of leaving it to the
     * enclosing `JBScrollPane`'s own wheel handling. Root cause of why that's necessary (verified against the
     * running JBR/JDK bytecode, `java.awt.Component#dispatchEventImpl` / `#dispatchMouseWheelToAncestor`): AWT
     * only auto-forwards an *unconsumed* wheel event to the nearest scroll-savvy ancestor when the original
     * target component has *no* `MouseWheelListener` of its own. This component installs one on itself (see
     * `init`), so that fallback never runs here, regardless of Shift/Ctrl or of whether the event ends up
     * consumed — an unconsumed event is simply a dead end, not a "let the parent handle it" signal. IntelliJ's
     * own bundled image viewer (`org.intellij.images.editor.impl.ImageEditorUI`) sidesteps this by attaching
     * its Ctrl+wheel zoom listener to the `JScrollPane` itself, leaving its inner view component listener-free
     * so plain/Shift wheel events correctly retarget to the scroll pane and fall through to its built-in,
     * smooth/momentum-aware handling (`JBScrollPane$JBMouseWheelListener`, which already branches on
     * `isShiftDown` for horizontal vs. vertical). Moving this listener up to the `JBScrollPane` would mirror
     * that architecture more closely but is a larger change than this fix calls for (see `PreviewRenderPanel`,
     * which owns the scroll pane); panning directly here is the smaller, self-contained fix. Tradeoff, by
     * design: this manual pan does not get `JBScrollPane`'s smooth-scroll/momentum/latching refinements.
     */
    private fun panByWheel(e: MouseWheelEvent) {
        val viewport = enclosingViewport() ?: return
        e.consume()
        val pos = viewport.viewPosition
        val units = e.unitsToScroll
        if (e.isShiftDown) pos.translate(units, 0) else pos.translate(0, units)
        val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
        val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
        pos.x = pos.x.coerceIn(0, maxX)
        pos.y = pos.y.coerceIn(0, maxY)
        viewport.viewPosition = pos
    }

    /**
     * Callback for [Magnificator.magnify] (see [installPinchZoom]): invoked once per completed macOS trackpad
     * pinch gesture — not per gesture tick, JBViewport's own `ZoomingDelegate` handles the live visual feedback
     * while the fingers are still moving — with [scale] > 1 for pinch-out (zoom in) and < 1 for pinch-in (zoom
     * out), and [at] the gesture's focal point already converted into this component's own coordinate space.
     *
     * One pinch = one step of the same zoom ladder the toolbar buttons and Ctrl+wheel use (via
     * [ZoomMath.stepIn]/[ZoomMath.stepOut]), for consistent granularity across every zoom input; this is a
     * deliberate simplification versus IntelliJ's own image viewer, which zooms continuously
     * (`scale * currentFactor`) — appropriate there for a model with no fixed ladder, but inconsistent with
     * this view's existing button/Ctrl+wheel step semantics.
     *
     * Unlike [onWheel]'s Ctrl+wheel branch, this must NOT set `viewport.viewPosition` itself: per the
     * `Magnificator` contract, `JBViewport`'s `ZoomingDelegate` derives the actual scrollbar adjustment from the
     * *returned* point (the new content-space position of [at] after the zoom change below) minus its own
     * record of the gesture's start point, then applies it via `JScrollBar#setValue` — which clamps to the
     * scrollbar's own valid range, so [ZoomMath.anchorScroll]'s result is clamped the same way [onWheel] clamps
     * it before being folded into the point this returns.
     */
    private fun onMagnify(scale: Double, at: Point): Point {
        val old = zoomFactor
        val next = when {
            scale > 1.0 -> ZoomMath.stepIn(old)
            scale < 1.0 -> ZoomMath.stepOut(old)
            else -> old
        }
        if (next == old) return at
        val viewport = enclosingViewport()
        if (viewport == null) {
            zoomFactor = next
            return at
        }
        val cursorInView = SwingUtilities.convertPoint(this, at, viewport)
        val oldScroll = viewport.viewPosition
        zoomFactor = next
        val target = ZoomMath.anchorScroll(cursorInView, old, next, oldScroll)
        val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
        val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
        val clamped = Point(target.x.coerceIn(0, maxX), target.y.coerceIn(0, maxY))
        return Point(clamped.x + cursorInView.x, clamped.y + cursorInView.y)
    }

    /**
     * Wires macOS trackpad pinch-to-zoom via IntelliJ Platform's `Magnificator`/`ZoomableViewport` mechanism —
     * the same technique `org.intellij.images.editor.impl.ImageEditorUI$ImageContainerPane` uses for the
     * bundled image viewer (confirmed by disassembling `platform-images.jar`: its constructor installs a
     * `Magnificator` client property on itself and does nothing else — no manual native gesture registration).
     * This is a genuinely separate input path from [onWheel]'s Ctrl+wheel branch, additive rather than a
     * replacement: macOS delivers a pinch as a native Cocoa gesture, never as a `MouseWheelEvent` (confirmed
     * against the running JBR: `sun.lwawt.macosx.NSEvent` carries no magnification field, and
     * `CPlatformResponder` has `handleScrollEvent`/`dispatchScrollEvent` for real wheel/scroll input but no
     * magnify-handling method at all). `JBScrollPane.createViewport()` already returns a `JBViewport`, which
     * implements `ZoomableViewport` and owns all the gesture plumbing (live cached-bitmap preview while
     * pinching, then one commit callback — [onMagnify] — at gesture end); a hosted component only needs to
     * supply the small [Magnificator] strategy, as done here.
     *
     * Guarded even though `Magnificator`/`ZoomableViewport` are ordinary `com.intellij.ui.components` classes
     * (same package/jar as `JBScrollPane`, already a hard dependency of this file) rather than reflection: per
     * this codebase's "never crash, never remove existing behavior" rule, and mirroring how the platform's own
     * `MacGestureSupportInstaller` guards its own gesture hookup, a class-shape mismatch on some other IDE
     * build must degrade to today's behavior (no pinch-zoom) rather than break construction of the whole view.
     */
    private fun installPinchZoom() {
        try {
            putClientProperty(Magnificator.CLIENT_PROPERTY_KEY, Magnificator { scale, at -> onMagnify(scale, at) })
        } catch (e: Exception) {
            thisLogger().warn("Trackpad pinch-zoom unavailable on this IDE build", e)
        } catch (e: LinkageError) {
            thisLogger().warn("Trackpad pinch-zoom API is incompatible with this IDE build", e)
        }
    }

    private fun enclosingViewport(): JViewport? = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    private companion object {
        private val HOVER_OUTLINE = JBColor(Color(0x3574F0), Color(0x548AF7))
    }
}
