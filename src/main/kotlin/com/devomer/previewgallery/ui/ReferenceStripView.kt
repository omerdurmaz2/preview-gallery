package com.devomer.previewgallery.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * Every reference image of one snapshot, stacked top to bottom at a single shared scale with its variant name
 * underneath (spec D7, laid out vertically since PG24).
 *
 * One scale for the whole strip is the point: the narrow variant exists to catch horizontal overflow, and
 * scaling each image to its own box would render a 320 dp and a 411 dp snapshot at the same apparent width and
 * hide exactly the difference the reader is looking for.
 *
 * **Stacked, not side by side.** A phone golden is ~1080 px wide and the tool window is a side panel; three of
 * them in a row (golden, Gradle's render, live) fit only by scaling the whole strip down until nothing in it is
 * readable, because [fitScale] binds on the width every time. Stacking makes the height the axis that grows —
 * the axis a scroll pane is good at — so each image can be read at a useful scale and the reader scrolls between
 * them. The one thing side-by-side bought, comparing two widths at a glance, survives: the shared scale means
 * two stacked images of different widths still differ visibly in width.
 */
class ReferenceStripView(private val images: List<LabelledImage>) : JComponent() {

    data class LabelledImage(val variant: String, val image: BufferedImage)

    private var scale: Double = 1.0

    fun setScale(scale: Double) {
        this.scale = scale
        preferredSize = preferredStripSize(scale)
        revalidate()
        repaint()
    }

    /**
     * The size the strip occupies at [scale]. The gaps between the images and each image's own label row are
     * **chrome**: they are laid out at the display's scale and do not grow with the zoom — only the images do.
     * [fitScale] has to invert exactly this, or Fit would not fit.
     *
     * Every image carries its own label row now, where the side-by-side layout shared one row across the whole
     * strip: a label under a stacked image has nowhere else to sit.
     */
    fun preferredStripSize(scale: Double): Dimension {
        if (images.isEmpty()) return Dimension(0, scaledLabelHeight())
        val width = images.maxOf { (it.image.width * scale).toInt() }
        val height = images.sumOf { (it.image.height * scale).toInt() + scaledLabelHeight() } +
            scaledGap() * (images.size - 1)
        return Dimension(width, height)
    }

    /**
     * The largest scale at which the whole strip fits [viewportWidth] x [viewportHeight].
     *
     * The chrome is **subtracted from the viewport**, never folded into the denominator: it does not scale (see
     * [preferredStripSize]), so dividing by `sum(height) + gaps + labels` would return a scale whose own preferred
     * size is still larger than the viewport by up to that chrome — pressing Fit would grow scrollbars, the exact
     * defect PG12 existed to remove. The inverse holds exactly, truncation included:
     * `sum(trunc(h * s)) <= trunc(sum(h) * s)` on the stacked axis, and `max(trunc(w * s)) <= trunc(max(w) * s)`
     * on the free one.
     *
     * A viewport smaller than the chrome alone has no scale that fits; the floor is returned rather than a zero
     * or negative one, and the caller's own [ZoomMath.MIN]/[ZoomMath.MAX] clamp agrees with it.
     */
    fun fitScale(viewportWidth: Int, viewportHeight: Int): Double {
        if (images.isEmpty()) return 1.0
        val naturalWidth = images.maxOf { it.image.width }
        val naturalHeight = images.sumOf { it.image.height }
        if (naturalWidth <= 0 || naturalHeight <= 0) return 1.0
        val availableWidth = viewportWidth
        val availableHeight = viewportHeight - scaledLabelHeight() * images.size - scaledGap() * (images.size - 1)
        if (availableWidth <= 0 || availableHeight <= 0) return ZoomMath.MIN
        return minOf(availableWidth.toDouble() / naturalWidth, availableHeight.toDouble() / naturalHeight)
    }

    override fun paintComponent(g: Graphics) {
        if (images.isEmpty()) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            var y = 0
            for (labelled in images) {
                val width = (labelled.image.width * scale).toInt()
                val height = (labelled.image.height * scale).toInt()
                g2.drawImage(labelled.image, 0, y, width, height, null)
                g2.color = JBColor.GRAY
                g2.drawString(labelled.variant, 0, y + height + JBUI.scale(LABEL_BASELINE))
                y += height + scaledLabelHeight() + scaledGap()
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val GAP = 16
        private const val LABEL_HEIGHT = 20
        private const val LABEL_BASELINE = 14

        /** Vertical space between two stacked variants, at the display's scale. */
        fun scaledGap(): Int = JBUI.scale(GAP)

        /** Height of the variant-label row under each image, at the display's scale. */
        fun scaledLabelHeight(): Int = JBUI.scale(LABEL_HEIGHT)
    }
}
