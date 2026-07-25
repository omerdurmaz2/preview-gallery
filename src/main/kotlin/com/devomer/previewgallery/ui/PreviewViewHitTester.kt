package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewViewNode
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

/**
 * Pure geometry for the interactive overlay: where the fitted image is drawn, how a panel point maps to a
 * render-pixel point, and which composable node is innermost at a render point. No Swing, no AS API — unit-tested.
 *
 * Resize-safe by construction: [imageDrawRect] takes the CURRENT panel and image dimensions as parameters and
 * derives the scaled, centered draw rectangle from them, so the caller must recompute it (and [toRenderPoint])
 * on every mouse event and paint from the live panel size — never cache a stale rectangle across a resize.
 */
object PreviewViewHitTester {

    /** The rectangle the image occupies when scaled to fit [panel] preserving aspect ratio, centered. */
    fun imageDrawRect(panel: Dimension, image: Dimension): Rectangle {
        if (image.width <= 0 || image.height <= 0 || panel.width <= 0 || panel.height <= 0) return Rectangle()
        val scale = minOf(panel.width.toDouble() / image.width, panel.height.toDouble() / image.height)
        val w = (image.width * scale).toInt()
        val h = (image.height * scale).toInt()
        val x = (panel.width - w) / 2
        val y = (panel.height - h) / 2
        return Rectangle(x, y, w, h)
    }

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
}
