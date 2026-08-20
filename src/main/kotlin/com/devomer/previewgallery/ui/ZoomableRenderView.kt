package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * A zoomable, pannable view of a render image with the Phase 4 hover-outline / click-to-source overlay. Swing
 * only — the plugin-owned [PreviewViewNode] tree is in render-pixel space and [PreviewViewHitTester] maps it;
 * no `com.android.tools.*` here. Intended to live inside a `JBScrollPane`: [getPreferredSize] is the zoomed
 * image extent, so the scroll pane sizes its scrollbars to it. Wheel zoom/pan (including macOS trackpad
 * two-finger scroll) and macOS trackpad pinch-zoom are handled by [ViewportGestures], installed on this
 * component in `init` — see that class for why they cannot be left to the scroll pane's own wheel handling.
 *
 * Coordinates: a mouse point in this component is in zoomed-image space, so `renderPoint = point / displayScale`
 * (no letterbox — the component's bounds ARE the zoomed image; [displayScale] folds in both the user's zoom
 * percentage and the render's device-pixel-to-dp conversion, PG12-3). When [handToolActive], drag pans the
 * enclosing viewport and the overlay is inert; otherwise hover outlines and click navigates (Phase 4).
 */
class ZoomableRenderView : JComponent() {

    private var image: BufferedImage? = null
    private var viewTree: List<PreviewViewNode> = emptyList()

    @Volatile private var hovered: PreviewViewNode? = null

    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}

    /**
     * The zoom percentage the user sees — and the same percentage Android Studio's own preview means by it: at
     * 1.0 the composable is drawn at dp size, not at the device's pixel size (PG12-3). Bounded by
     * [ZoomMath.MIN]/[ZoomMath.MAX], NOT by [ZoomMath.LADDER] — the ladder is only where the step buttons stop,
     * and clamping fit to its 25% floor is what used to make a tall render overflow a short pane.
     */
    var zoomFactor: Double = 1.0
        set(value) {
            field = value.coerceIn(ZoomMath.MIN, ZoomMath.MAX)
            // Any assignment settles what this view owes the current render: either it IS the fit (from
            // fitToViewport), or the user picked a zoom themselves and a later resize must not overwrite it.
            pendingFit = false
            revalidate() // preferredSize changed -> scroll pane updates scrollbars
            repaint()
        }

    /** dp per render pixel for the current image (see [ZoomMath.contentScale]); 1.0 until content arrives. */
    private var contentScale: Double = 1.0

    /** The current image's size in dp — what [fitToViewport] fits, so its result is a zoom percentage. */
    private var contentDp: Dimension = Dimension(0, 0)

    /**
     * Whether the current image still owes this view a fit (PG12-4).
     *
     * On the first render after the tool window opens, `add()` does not lay the scroll pane out synchronously, so
     * the enclosing viewport still reports a 0x0 extent and [fitToViewport] has nothing to fit against — the
     * preview would show at 100%, overflowing the pane, which is exactly the bug this phase fixes. Rather than
     * retry on a timer (which spins forever on a component that is never shown) or re-fit on every resize (which
     * would throw away a zoom the user chose), the debt is recorded here and settled by the first resize that
     * gives the viewport a real size — or by the user zooming, via the [zoomFactor] setter.
     */
    private var pendingFit: Boolean = false

    /** Exposed for `ZoomableRenderViewTest`: the state machine above, without pumping AWT ComponentEvents. */
    internal val isFitPending: Boolean get() = pendingFit

    /** The whole body of [fitListener]. Internal so the test can drive it directly. */
    internal fun retryFitIfPending() {
        if (pendingFit) fitToViewport()
    }

    private val fitListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) = retryFitIfPending()
    }

    /**
     * The listener goes on the *viewport*, not on this component: this component's own size is its zoomed extent,
     * which changes for reasons that have nothing to do with the pane growing. Swing pairs
     * [addNotify]/[removeNotify], so the listener is installed exactly once per attachment — including when
     * `PreviewRenderPanel` reparents a comparison view through `JBScrollPane.setViewportView`.
     */
    override fun addNotify() {
        super.addNotify()
        enclosingViewport()?.addComponentListener(fitListener)
    }

    override fun removeNotify() {
        enclosingViewport()?.removeComponentListener(fitListener) // before super: the ancestor is still reachable
        super.removeNotify()
    }

    /**
     * The factor every on-screen dimension is expressed in: the user's zoom percentage times the render's own
     * pixel-to-dp conversion. Deriving it once is what keeps [getPreferredSize], the drawn image, the hover
     * outline and [renderPointOf] from disagreeing.
     */
    private val displayScale: Double get() = zoomFactor * contentScale

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
        ViewportGestures.install(this, ZoomBinding())
    }

    /** [ViewportGestures]' view of this component: the zoom factor, and how to anchor the scroll to the cursor
     *  after it changes. Everything device-specific about the gesture stays in that class; everything about what
     *  is being zoomed stays here. */
    private inner class ZoomBinding : ViewportGestures.Zoom {

        override fun current(): Double = zoomFactor

        override fun applyAt(next: Double, cursorInViewport: Point) {
            val viewport = enclosingViewport()
            val old = zoomFactor
            val oldScroll = viewport?.viewPosition ?: Point(0, 0)
            zoomFactor = next
            if (viewport == null) return
            val target = ZoomMath.anchorScroll(cursorInViewport, old, zoomFactor, oldScroll)
            val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
            val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
            viewport.viewPosition = Point(target.x.coerceIn(0, maxX), target.y.coerceIn(0, maxY))
        }
    }

    /**
     * A new render's image + view tree, plus the density it was rendered at ([RenderOutcome.Success.dpi]); resets
     * zoom to [fitToViewport] and clears any prior hover.
     */
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>, dpi: Int) {
        this.image = image
        this.viewTree = viewTree
        this.hovered = null
        this.contentScale = ZoomMath.contentScale(dpi)
        this.contentDp = ZoomMath.dpSize(Dimension(image.width, image.height), dpi)
        this.pendingFit = true
        fitToViewport()
    }

    fun clearContent() {
        image = null
        viewTree = emptyList()
        hovered = null
        contentScale = 1.0
        contentDp = Dimension(0, 0)
        pendingFit = false
        revalidate(); repaint()
    }

    fun rawImage(): BufferedImage? = image

    fun fitToViewport() {
        if (image == null) return
        val vp = enclosingViewport()?.extentSize ?: size
        // Not laid out yet: leave pendingFit standing so the resize retry picks this up. Assigning a factor here
        // would settle the debt with a meaningless 1.0.
        if (vp.width <= 0 || vp.height <= 0) return
        zoomFactor = ZoomMath.fitFactor(vp, contentDp)
    }

    override fun getPreferredSize(): Dimension {
        val img = image ?: return Dimension(0, 0)
        val scale = displayScale
        return Dimension(
            (img.width * scale).roundToInt().coerceAtLeast(1),
            (img.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    override fun paintComponent(g: Graphics) {
        val img = image ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            // The layoutlib image is at device pixel density and is usually drawn well under 1:1 (a 1080 px wide
            // render inside a ~400 px pane), so ask for the quality path on top of bilinear filtering.
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val scale = displayScale
            g2.drawImage(img, 0, 0, (img.width * scale).roundToInt(), (img.height * scale).roundToInt(), null)
            val node = hovered
            if (!handToolActive && node != null) {
                val b = node.bounds
                g2.color = HOVER_OUTLINE
                g2.drawRect(
                    (b.x * scale).roundToInt(), (b.y * scale).roundToInt(),
                    (b.width * scale).roundToInt().coerceAtLeast(0),
                    (b.height * scale).roundToInt().coerceAtLeast(0),
                )
            }
        } finally {
            g2.dispose()
        }
    }

    private fun renderPointOf(p: Point): Point? {
        val scale = displayScale
        if (image == null || scale <= 0.0) return null
        return Point((p.x / scale).toInt(), (p.y / scale).toInt())
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

    private fun enclosingViewport(): JViewport? = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    private companion object {
        private val HOVER_OUTLINE = JBColor(Color(0x3574F0), Color(0x548AF7))
    }
}
