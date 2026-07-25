package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

/**
 * Pure geometry for the interactive overlay: how a panel point maps to a render-pixel point, which composable
 * node is innermost at a render point, and the source chain at that point. No Swing, no AS API — unit-tested.
 *
 * Resize-safe by construction: [toRenderPoint] takes the CURRENT draw rectangle and image size as parameters, so
 * the caller must recompute the draw rectangle from the live panel/icon size on every mouse event and paint —
 * never cache a stale rectangle across a resize. (The panel owns that rectangle: it derives it from the actual
 * scaled icon, which is capped at 1:1, so a generic fit-rect helper here would not match what is painted.)
 */
object PreviewViewHitTester {

    /** Map a panel point to render-pixel space, or null if it falls outside the drawn image. */
    fun toRenderPoint(panelPoint: Point, drawRect: Rectangle, image: Dimension): Point? {
        if (!drawRect.contains(panelPoint) || drawRect.width == 0 || drawRect.height == 0) return null
        val fx = (panelPoint.x - drawRect.x).toDouble() / drawRect.width
        val fy = (panelPoint.y - drawRect.y).toDouble() / drawRect.height
        return Point((fx * image.width).toInt(), (fy * image.height).toInt())
    }

    /** The deepest node whose bounds contain [point]; null if none. Depth-first, smallest containing wins. */
    fun innermostAt(roots: List<PreviewViewNode>, point: Point): PreviewViewNode? {
        var best: PreviewViewNode? = null
        fun visit(node: PreviewViewNode) {
            if (!node.bounds.contains(point)) return
            best = node
            node.children.forEach { visit(it) }
        }
        roots.forEach { visit(it) }
        return best
    }

    /**
     * Source locations of the nodes containing [point], innermost first. Click-to-source uses this rather than
     * [innermostAt]'s single node because the deepest node under the cursor often has no source (a layout/graphics
     * leaf) or a framework source; the caller walks these innermost-out and navigates to the first that resolves
     * to a project file, so a click lands on the most specific user code under the cursor.
     */
    fun sourceChainAt(roots: List<PreviewViewNode>, point: Point): List<PreviewSourceLocation> {
        val chain = ArrayList<PreviewSourceLocation>()
        fun visit(node: PreviewViewNode) {
            if (!node.bounds.contains(point)) return
            node.children.forEach { visit(it) } // recurse first so deeper nodes' sources land earlier in the list
            node.sourceLocation?.let { chain.add(it) }
        }
        roots.forEach { visit(it) }
        return chain
    }
}
